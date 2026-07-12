package com.duq.android.audio

import android.content.Context
import android.util.Log
import com.duq.android.config.AppConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import kotlin.concurrent.withLock

/**
 * Android-реализация [LocalTts] — on-device TTS через sherpa-onnx (k2-fsa) + Piper VITS RU.
 * Заменяет серверный /tts (Silero):
 *  - бандл модели (.tar.bz2) докачивается в filesDir при первом голосовом ответе и
 *    распаковывается (model.onnx + tokens.txt + espeak-ng-data);
 *  - синтез → WAV-файл (тот же контейнер, что отдавал сервер), играет [AudioPlaybackManager].
 *
 * Единая точка [trySynthesize] инкапсулирует флаг + докачку + синтез + fallback; null →
 * вызывающий уходит на серверный /tts. Движок грузится один раз и переиспользуется (init
 * дорогой); выгружается при нехватке RAM (onTrimMemory).
 */
class TtsLocal(
    private val context: Context
) : LocalTts {

    companion object {
        private const val TAG = "TtsLocal"
        private const val TTS_PREFIX = "tts_local_"
    }

    @Volatile private var engine: OfflineTts? = null

    // ReentrantLock (а не synchronized): release() из system memory-callback (Main) должен
    // НЕ блокироваться, если идёт generate (стриминг зовёт его часто) — иначе ANR. release
    // использует tryLock. Реентрантен (generate внутри держит лок, ensureEngine — вложенно).
    private val lock = java.util.concurrent.locks.ReentrantLock()

    init {
        // Движок держит модель в RAM; при критической нехватке памяти отдаём её назад
        // (потом перезагрузим лениво) — как WhisperLocal.
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) release()
            }
            override fun onLowMemory() = release()

            @Suppress("EmptyFunctionBlock")
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
    }

    private val httpClient by lazy {
        // NOTE: DoH-резолвер (withDuqDns) подключится, когда network-слой переедет в shared;
        // пока докачка бандла идёт через системный DNS (github разрешается штатно).
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // большой бандл — без общего лимита
            .build()
    }

    private fun modelDir(): File = File(context.filesDir, "tts")
    private fun modelFile(): File = File(modelDir(), AppConfig.TTS_MODEL_FILE)
    private fun tokensFile(): File = File(modelDir(), AppConfig.TTS_TOKENS_FILE)
    private fun espeakDir(): File = File(modelDir(), AppConfig.TTS_ESPEAK_DATA_DIR)

    /** Бандл распакован (модель + токены + espeak-данные на месте). */
    override fun isModelReady(): Boolean =
        modelFile().exists() && modelFile().length() > 1_000_000L &&
            tokensFile().exists() && espeakDir().isDirectory

    /**
     * Качает бандл модели (.tar.bz2) и распаковывает, если его ещё нет.
     * Возвращает true, когда модель готова к использованию.
     */
    override suspend fun ensureModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) return@withContext true
        modelDir().mkdirs()
        val archive = File(modelDir(), "${AppConfig.TTS_MODEL_BUNDLE}.tar.bz2.part")
        try {
            val req = Request.Builder().url(AppConfig.TTS_MODEL_URL).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "model download HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().coerceAtLeast(1L)
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            extractTarBz2(archive, modelDir())
            archive.delete()
            if (!isModelReady()) {
                Log.e(TAG, "model not ready after extract")
                return@withContext false
            }
            Log.i(TAG, "TTS model ready: ${modelFile().length()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "model download/extract failed: ${e.message}")
            archive.delete()
            false
        }
    }

    /** Распаковывает .tar.bz2 [archive] в [dest] (плоско по путям записей tar). */
    @Suppress("NestedBlockDepth")
    private fun extractTarBz2(archive: File, dest: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                // Защита от path-traversal (zip-slip) — запись обязана лежать внутри dest.
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                    error("tar entry escapes dest: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun ensureEngine(): OfflineTts {
        lock.withLock {
            engine?.let { return it }
            require(isModelReady()) { "TTS model not ready" }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile().absolutePath,
                        tokens = tokensFile().absolutePath,
                        dataDir = espeakDir().absolutePath,
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                ),
            )
            return OfflineTts(config = config).also { engine = it }
        }
    }

    /**
     * Единая точка on-device синтеза для всех вызывающих. Возвращает путь к WAV-файлу ИЛИ null —
     * если TTS_ON_DEVICE выключен, модель не готова (и не докачалась), либо синтез упал.
     * На null вызывающий уходит на серверный /tts (см. ConversationViewModel.speakReply).
     * Старые локальные TTS-файлы чистятся, чтобы синтез не копился в кэше.
     */
    override suspend fun trySynthesize(text: String, messageId: String): String? {
        if (!AppConfig.TTS_ON_DEVICE || text.isBlank()) return null
        return try {
            if (!isModelReady()) ensureModel()
            if (!isModelReady()) return null
            synthesizeWav(text, messageId).absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "on-device TTS failed, falling back to server: ${e.message}")
            null
        }
    }

    /** PCM16-сэмплы фразы (для НОРМАЛЬНОГО стриминга в AudioTrack — без WAV-файлов/очередей).
     *  Возвращает сырые сэмплы + sampleRate. null — движок не готов (НЕ скачивает) или ошибка.
     *  Первый голос-ответ (модель ещё не скачана) пойдёт обычным speakReply, который и скачает. */
    override suspend fun synthesizeSamples(text: String): TtsSamples? {
        if (!isReady() || text.isBlank()) return null
        return try {
            withContext(Dispatchers.Default) {
                val audio = lock.withLock {
                    ensureEngine().generate(text, AppConfig.TTS_SPEAKER_ID, AppConfig.TTS_SPEED)
                }
                val f = audio.samples
                val pcm = ShortArray(f.size) { i -> (f[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                TtsSamples(pcm, audio.sampleRate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "synthesizeSamples failed: ${e.message}")
            null
        }
    }

    /** Готов ли on-device движок (модель скачана) БЕЗ скачивания — решает, стримить ли догоном. */
    override fun isReady(): Boolean = AppConfig.TTS_ON_DEVICE && isModelReady()

    /** Синтезирует [text] в WAV-файл в cacheDir, ключ — [messageId]. */
    private suspend fun synthesizeWav(text: String, messageId: String): File = withContext(Dispatchers.Default) {
        context.cacheDir.listFiles { f -> f.name.startsWith(TTS_PREFIX) }?.forEach { it.delete() }
        val out = File(context.cacheDir, "$TTS_PREFIX${messageId.take(24)}.wav")
        val audio = lock.withLock {
            ensureEngine().generate(text, AppConfig.TTS_SPEAKER_ID, AppConfig.TTS_SPEED)
        }
        // GeneratedAudio.save пишет валидный WAV (PCM16) на нужном sampleRate — тот же формат,
        // что отдавал серверный Silero, плеер играет его без изменений.
        if (!audio.save(out.absolutePath)) error("WAV save failed")
        out
    }

    /** Выгружает движок из нативной памяти. Зовётся системой (Main) при нехватке RAM.
     *  tryLock — НЕ блокируем Main, если идёт generate (стриминг): пропускаем выгрузку
     *  (движок освободится на следующем trim/лениво), главное — не висеть на Main → ANR. */
    override fun release() {
        if (lock.tryLock()) {
            try {
                engine?.release()
                engine = null
            } finally { lock.unlock() }
        } else {
            Log.d(TAG, "release пропущен — идёт синтез")
        }
    }
}

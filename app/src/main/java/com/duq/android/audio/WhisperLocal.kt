package com.duq.android.audio

import android.content.Context
import android.util.Log
import com.duq.android.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device STT через whisper.cpp (JNI). Заменяет серверный /stt:
 *  - модель ggml-small (multilingual) докачивается в filesDir при первом запуске;
 *  - WAV декодирует [WavDecoder] → float32 → нативный whisper_full (language=ru).
 *
 * Контекст модели грузится один раз и переиспользуется (init дорогой).
 * Потокобезопасность: transcribe вызывается из одного voice-флоу за раз; на всякий — synchronized.
 */
@Singleton
class WhisperLocal @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WhisperLocal"
        init { System.loadLibrary("duqwhisper") }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, audio: FloatArray, lang: String, threads: Int): String
    private external fun nativeFree(ctx: Long)

    @Volatile private var ctxPtr: Long = 0L
    private val lock = Any()

    init {
        // Модель переиспользуется между вызовами (init дорогой), но при нехватке RAM
        // система просит её выгрузить — отдаём ~0.5 ГБ назад, потом перезагрузим лениво.
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) release()
            }
            override fun onLowMemory() = release()
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // большая модель — без общего лимита
            .build()
    }

    private fun modelFile(): File =
        File(context.filesDir, "whisper/${AppConfig.WHISPER_MODEL_FILE}")

    fun isModelReady(): Boolean = modelFile().exists() && modelFile().length() > 1_000_000L

    /** Качает модель, если её ещё нет. Возвращает true, если модель на месте. */
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val f = modelFile()
        if (isModelReady()) return@withContext true
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.part")
        try {
            val req = Request.Builder().url(AppConfig.WHISPER_MODEL_URL).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.e(TAG, "model download HTTP ${resp.code}"); return@withContext false }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().coerceAtLeast(1L)
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024); var read: Int; var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read); done += read
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (!tmp.renameTo(f)) { Log.e(TAG, "rename model failed"); return@withContext false }
            Log.i(TAG, "whisper model downloaded: ${f.length()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "model download failed: ${e.message}")
            tmp.delete(); false
        }
    }

    private fun ensureCtx(): Long {
        synchronized(lock) {
            if (ctxPtr == 0L) {
                require(isModelReady()) { "whisper model not ready" }
                ctxPtr = nativeInit(modelFile().absolutePath)
                require(ctxPtr != 0L) { "whisper nativeInit failed" }
            }
            return ctxPtr
        }
    }

    /**
     * Единая точка on-device распознавания для всех вызывающих (node/gateway клиенты).
     * Возвращает текст ИЛИ null — если STT_ON_DEVICE выключен, модель не готова (и не
     * докачалась), либо распознавание упало/пусто. На null вызывающий уходит на
     * серверный /stt. Инкапсулирует флаг + докачку + fallback в ОДНОМ месте, чтобы
     * клиенты не дублировали эту логику.
     */
    suspend fun tryTranscribe(file: File): String? {
        if (!AppConfig.STT_ON_DEVICE) return null
        return try {
            if (!isModelReady()) ensureModel()
            if (!isModelReady()) return null
            transcribeWav(file).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "on-device STT failed, falling back to server: ${e.message}")
            null
        }
    }

    /** Транскрибирует 16 kHz mono PCM16 WAV-файл в текст. Бросает при пустом результате. */
    private suspend fun transcribeWav(file: File): String = withContext(Dispatchers.Default) {
        val pcm = WavDecoder.decodePcm16Mono(file)
        if (pcm.isEmpty()) throw IllegalStateException("empty audio")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val text = synchronized(lock) { nativeTranscribe(ensureCtx(), pcm, AppConfig.STT_LANGUAGE, threads) }
        text.trim()
    }

    /** Выгружает модель из нативной памяти (~0.5 ГБ). Зовётся системой при нехватке RAM. */
    fun release() {
        synchronized(lock) { if (ctxPtr != 0L) { nativeFree(ctxPtr); ctxPtr = 0L } }
    }
}

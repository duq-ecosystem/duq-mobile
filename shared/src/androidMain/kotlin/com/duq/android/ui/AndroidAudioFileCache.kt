package com.duq.android.ui

import android.content.Context
import com.duq.android.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android-реализация [AudioFileCache] — мост server-TTS байты → файл (cacheDir) и
 * путь к временному PTT-WAV. Context приходит конструктором (Koin: androidContext()).
 *
 * server-TTS пишется в `cacheDir/tts/msg_<id>.wav` (тот же ключ, что у
 * AudioPlaybackManager.isCached); push-to-talk-запись — во временный
 * `cacheDir/voice/<name>`.
 */
class AndroidAudioFileCache(
    private val context: Context,
    private val logger: Logger,
) : AudioFileCache {

    override suspend fun writeWav(messageId: String, audio: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, TTS_DIR).apply { mkdirs() }
                val file = File(dir, "msg_${sanitize(messageId)}.wav")
                file.writeBytes(audio)
                file.absolutePath
            } catch (e: Exception) {
                logger.e(TAG, "writeWav($messageId) failed: ${e.message}", e)
                null
            }
        }

    override fun tempWavPath(name: String): String {
        val dir = File(context.cacheDir, PTT_DIR).apply { mkdirs() }
        return File(dir, sanitize(name)).absolutePath
    }

    override fun deleteTemp(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
            .onFailure { logger.w(TAG, "deleteTemp($path) failed: ${it.message}") }
    }

    // Имя сообщения может содержать «/» и пр. — нормализуем в безопасное имя файла.
    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TAG = "AudioFileCache"
        const val TTS_DIR = "tts"
        const val PTT_DIR = "voice"
    }
}

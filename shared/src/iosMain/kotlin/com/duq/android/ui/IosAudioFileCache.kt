package com.duq.android.ui

import com.duq.android.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile

/**
 * iOS-реализация [AudioFileCache] — пишет server-TTS WAV в NSTemporaryDirectory через
 * NSData, отдаёт пути для PTT-записи и удаляет временные файлы через NSFileManager.
 *
 * Зеркалит util/FileBytes.ios.kt (cinterop NSData↔ByteArray). server-TTS → `tts/msg_<id>.wav`,
 * push-to-talk → `voice/<name>` под NSTemporaryDirectory.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioFileCache(
    private val logger: Logger,
) : AudioFileCache {

    private val fm = NSFileManager.defaultManager

    override suspend fun writeWav(messageId: String, audio: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = ensureDir(TTS_DIR)
                val path = "$dir/msg_${sanitize(messageId)}.wav"
                val data = audio.toNSData()
                val ok = data.writeToFile(path, atomically = true)
                if (ok) path else {
                    logger.e(TAG, "writeWav($messageId): writeToFile вернул false")
                    null
                }
            } catch (e: Exception) {
                logger.e(TAG, "writeWav($messageId) failed: ${e.message}", e)
                null
            }
        }

    override fun tempWavPath(name: String): String {
        val dir = ensureDir(PTT_DIR)
        return "$dir/${sanitize(name)}"
    }

    override fun deleteTemp(path: String) {
        runCatching {
            if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, error = null)
        }.onFailure { logger.w(TAG, "deleteTemp($path) failed: ${it.message}") }
    }

    private fun ensureDir(sub: String): String {
        val dir = NSTemporaryDirectory().trimEnd('/') + "/" + sub
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return dir
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
        }

    private companion object {
        const val TAG = "AudioFileCache"
        const val TTS_DIR = "tts"
        const val PTT_DIR = "voice"
    }
}

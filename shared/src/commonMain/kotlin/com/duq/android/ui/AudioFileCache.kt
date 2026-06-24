package com.duq.android.ui

/**
 * Запись синтезированных СЕРВЕРОМ WAV-байтов на диск под messageId — мост между
 * [com.duq.android.network.TtsClient] (возвращает ByteArray, без ФС в commonMain) и
 * [com.duq.android.audio.AudioPlaybackManager.play] (принимает String-путь к файлу).
 *
 * On-device TTS ([com.duq.android.audio.LocalTts.trySynthesize]) уже отдаёт путь; для
 * серверного fallback нужен платформенный writer — он живёт здесь.
 *
 * Интерфейс — общий код KMP (commonMain); реализация платформенная (androidMain:
 * cacheDir/msg_<id>.wav; iosMain: NSTemporaryDirectory/деградация). Вынесен в ui/,
 * т.к. audio/-интерфейсы трогать нельзя, а bridge нужен ViewModel.
 */
interface AudioFileCache {
    /**
     * Пишет [audio] (WAV-байты) в кэш под ключ [messageId] и возвращает абсолютный путь
     * к файлу (тот же ключ, что у [com.duq.android.audio.AudioPlaybackManager.isCached]).
     * null — если запись не удалась.
     */
    suspend fun writeWav(messageId: String, audio: ByteArray): String?

    /**
     * Абсолютный путь к временному WAV-файлу [name] во временной директории платформы —
     * для записи push-to-talk-аудио ([com.duq.android.audio.AudioRecorderInterface.record]
     * принимает абсолютный путь). Файл может ещё не существовать.
     */
    fun tempWavPath(name: String): String

    /** Удалить временный файл по абсолютному пути (после STT, чтобы не копить мусор). */
    fun deleteTemp(path: String)
}

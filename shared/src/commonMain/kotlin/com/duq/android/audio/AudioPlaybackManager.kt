package com.duq.android.audio

import kotlinx.coroutines.flow.StateFlow

/** Состояние проигрывания аудио-сообщений в чате. */
enum class PlaybackState {
    IDLE, // Not playing anything
    LOADING, // Downloading/buffering audio
    PLAYING, // Currently playing
    PAUSED // Paused mid-playback
}

/** Текущая инфа проигрывания для UI. */
data class PlaybackInfo(
    val messageId: String? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val progress: Float = 0f, // 0.0 - 1.0
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0
)

/**
 * Менеджер проигрывания аудио-сообщений чата (загрузка из кэша, кэширование, воспроизведение
 * со стейтом). Интерфейс — общий код KMP (commonMain); реализация платформенная
 * (androidMain: ExoPlayer/media3; iosMain: AVPlayer/деградация).
 *
 * Путь к аудио-файлу — [String] (File недоступен в commonMain).
 */
interface AudioPlaybackManager {
    /** Стейт проигрывания для UI (какое сообщение играет, прогресс и т.д.). */
    val playbackInfo: StateFlow<PlaybackInfo>

    /** Инициализировать плеер (на старте приложения). */
    fun initialize()

    /** Играть/тоггл паузы для сообщения по id (из кэша). */
    fun playOrToggle(messageId: String)

    /** Играть готовый аудио-файл напрямую (свежесинтезированный TTS-ответ). */
    fun play(messageId: String, audioPath: String)

    /** Записать накопленные PCM16-чанки потокового догона одним WAV в кэш под [messageId]. */
    fun cacheStreamedAudio(messageId: String, pcmChunks: List<ShortArray>, sampleRate: Int)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()

    /** Закэширована ли озвучка сообщения. */
    fun isCached(messageId: String): Boolean

    /** Длительность кэшированной озвучки (мс) из WAV-заголовка; 0 — нет файла/битый заголовок. */
    fun cachedDurationMs(messageId: String): Int

    /** Очистить кэш озвучек. */
    fun clearCache()

    /** Перепривязать кэш озвучки с одного id на другой (reconcile runId → серверный messageId). */
    fun renameCache(oldId: String, newId: String)
}

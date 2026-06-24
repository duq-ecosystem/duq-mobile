package com.duq.android.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.duq.android.logging.Logger
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback state for audio messages in chat
 */
enum class PlaybackState {
    IDLE,       // Not playing anything
    LOADING,    // Downloading/buffering audio
    PLAYING,    // Currently playing
    PAUSED      // Paused mid-playback
}

/**
 * Current playback info for UI
 */
data class PlaybackInfo(
    val messageId: String? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val progress: Float = 0f,        // 0.0 - 1.0
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0
)

/**
 * Manages audio playback for chat messages.
 * Handles downloading, caching, and playing audio with state tracking.
 */
@Singleton
class ChatAudioPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flog: Logger,
) {
    companion object {
        private const val TAG = "ChatAudioPlayback"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Use volatile for thread-safe access from multiple threads
    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var currentListener: Player.Listener? = null

    private var progressJob: Job? = null

    // SupervisorJob ensures child coroutines don't cancel the parent on failure
    // This scope is tied to application lifecycle (singleton)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + supervisorJob)

    // Track if manager is released to prevent use-after-release
    @Volatile
    private var isReleased = false

    // Audio file cache directory
    private val audioCacheDir: File by lazy {
        File(context.cacheDir, "audio_messages").also { it.mkdirs() }
    }

    // Playback state exposed to UI
    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    /**
     * Initialize the player (call on app start)
     */
    fun initialize() {
        if (isReleased) {
            Log.w(TAG, "Cannot initialize - manager is released")
            return
        }
        mainHandler.post {
            if (exoPlayer == null && !isReleased) {
                exoPlayer = ExoPlayer.Builder(context).build()
                Log.d(TAG, "ExoPlayer initialized")
            }
        }
    }

    /**
     * Play audio for a message. If same message is already playing, toggle pause/resume.
     */
    fun playOrToggle(messageId: String) {
        val currentInfo = _playbackInfo.value

        when {
            // Same message - toggle play/pause
            currentInfo.messageId == messageId -> {
                when (currentInfo.state) {
                    PlaybackState.PLAYING -> pause()
                    PlaybackState.PAUSED -> resume()
                    PlaybackState.LOADING -> {} // Do nothing while loading
                    PlaybackState.IDLE -> loadAndPlay(messageId)
                }
            }
            // Different message - start new playback
            else -> {
                stop()
                loadAndPlay(messageId)
            }
        }
    }

    /**
     * Play a ready audio file directly (e.g. a freshly synthesized TTS reply).
     * Initializes the player if needed and replaces any current playback.
     */
    fun play(messageId: String, audioFile: File) {
        if (isReleased || !audioFile.exists()) return
        // Кэшируем синтез под messageId, иначе кнопка play (loadAndPlay → getCachedAudioFile)
        // его не найдёт после авто-проигрывания: TTS пишет в cacheDir/tts_local_*, а кнопка
        // ищет в audio_messages/msg_<id>.mp3. ExoPlayer играет WAV по контенту (расширение неважно).
        // Копирование (блокирующий файловый IO) — на Dispatchers.IO, не на main; проигрывание
        // (playFile через mainHandler) — после копии.
        scope.launch(Dispatchers.IO) {
            val cached = getCachedAudioFile(messageId)
            if (audioFile.absolutePath != cached.absolutePath) {
                try { audioFile.copyTo(cached, overwrite = true) } catch (e: Exception) {
                    Log.w(TAG, "cache copy failed: ${e.message}")
                }
            }
            if (isReleased) return@launch
            stop()
            playFile(messageId, if (cached.exists()) cached else audioFile)
        }
    }

    /**
     * Записать накопленные PCM16-сэмменты потокового догона ОДНИМ WAV в кэш под [messageId] —
     * для мгновенного replay и длительности (сам догон льёт звук в AudioTrack, файла не
     * оставляет). Заголовок WAV (44 байта) + сырой PCM. На Dispatchers.IO.
     */
    fun cacheStreamedAudio(messageId: String, pcmChunks: List<ShortArray>, sampleRate: Int) {
        if (isReleased || pcmChunks.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val totalSamples = pcmChunks.sumOf { it.size.toLong() }
                val dataBytes = totalSamples * 2 // PCM16
                getCachedAudioFile(messageId).outputStream().buffered().use { os ->
                    os.write(wavHeader(dataBytes, sampleRate))
                    for (chunk in pcmChunks) {
                        val bytes = ByteArray(chunk.size * 2)
                        var j = 0
                        for (s in chunk) {
                            bytes[j++] = (s.toInt() and 0xFF).toByte()
                            bytes[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
                        }
                        os.write(bytes)
                    }
                }
            } catch (e: Exception) {
                flog.w(TAG, "cacheStreamedAudio failed: ${e.message}")
            }
        }
    }

    /** Стандартный 44-байтный WAV-заголовок (PCM16, моно) для [dataBytes] аудио-данных. */
    private fun wavHeader(dataBytes: Long, sampleRate: Int): ByteArray {
        val h = ByteArray(44)
        val byteRate = sampleRate * 2 // mono * 16bit
        fun le32(off: Int, v: Long) { for (i in 0..3) h[off + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        fun le16(off: Int, v: Int) { h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte() }
        "RIFF".toByteArray().copyInto(h, 0)
        le32(4, 36 + dataBytes)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        le32(16, 16)          // PCM fmt chunk size
        le16(20, 1)           // PCM
        le16(22, 1)           // mono
        le32(24, sampleRate.toLong())
        le32(28, byteRate.toLong())
        le16(32, 2)           // block align (mono*16bit/8)
        le16(34, 16)          // bits per sample
        "data".toByteArray().copyInto(h, 36)
        le32(40, dataBytes)
        return h
    }

    /**
     * Load audio from cache and play
     */
    private fun loadAndPlay(messageId: String) {
        scope.launch {
            try {
                _playbackInfo.value = PlaybackInfo(
                    messageId = messageId,
                    state = PlaybackState.LOADING
                )

                // Check cache only (no remote download)
                val cachedFile = getCachedAudioFile(messageId)
                if (cachedFile.exists()) {
                    Log.d(TAG, "Using cached audio for message $messageId")
                    playFile(messageId, cachedFile)
                } else {
                    Log.w(TAG, "No cached audio for message $messageId")
                    _playbackInfo.value = PlaybackInfo()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading audio: ${e.message}", e)
                _playbackInfo.value = PlaybackInfo()
            }
        }
    }

    /**
     * Play audio from file
     */
    private fun playFile(messageId: String, audioFile: File) {
        if (isReleased) {
            Log.w(TAG, "Cannot play - manager is released")
            return
        }
        mainHandler.post {
            if (isReleased) return@post
            try {
                // Don't create new player if released or if player already exists
                val player = exoPlayer ?: run {
                    if (isReleased) return@post
                    ExoPlayer.Builder(context).build().also { exoPlayer = it }
                }

                // Remove previous listener
                currentListener?.let { player.removeListener(it) }

                // Create new listener
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                val duration = player.duration
                                _playbackInfo.value = _playbackInfo.value.copy(
                                    state = PlaybackState.PLAYING,
                                    durationMs = duration
                                )
                                startProgressUpdates()
                                Log.d(TAG, "Playback started, duration: ${duration}ms")
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "Playback ended")
                                stopProgressUpdates()
                                _playbackInfo.value = PlaybackInfo()
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Buffering")
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "Player idle")
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying && _playbackInfo.value.state == PlaybackState.PLAYING) {
                            // Externally paused (e.g., audio focus lost)
                            _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.PAUSED)
                            stopProgressUpdates()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "Playback error: ${error.message}", error)
                        stopProgressUpdates()
                        _playbackInfo.value = PlaybackInfo()
                    }
                }

                currentListener = listener
                player.addListener(listener)

                val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()

                Log.d(TAG, "Started playback for message $messageId")

            } catch (e: Exception) {
                Log.e(TAG, "Error playing file: ${e.message}", e)
                _playbackInfo.value = PlaybackInfo()
            }
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        mainHandler.post {
            exoPlayer?.pause()
            if (_playbackInfo.value.state == PlaybackState.PLAYING) {
                _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.PAUSED)
                stopProgressUpdates()
                Log.d(TAG, "Playback paused")
            }
        }
    }

    /**
     * Resume playback
     */
    fun resume() {
        mainHandler.post {
            exoPlayer?.play()
            if (_playbackInfo.value.state == PlaybackState.PAUSED) {
                _playbackInfo.value = _playbackInfo.value.copy(state = PlaybackState.PLAYING)
                startProgressUpdates()
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    /**
     * Stop playback and reset state
     */
    fun stop() {
        mainHandler.post { stopInternal() }
    }

    /** Останов + сброс (main thread). */
    private fun stopInternal() {
        stopProgressUpdates()
        currentListener?.let { l -> exoPlayer?.removeListener(l) }
        currentListener = null
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _playbackInfo.value = PlaybackInfo()
        Log.d(TAG, "Playback stopped")
    }

    /**
     * Seek to position
     */
    fun seekTo(positionMs: Long) {
        mainHandler.post {
            exoPlayer?.seekTo(positionMs)
            updateProgress()
        }
    }

    /**
     * Release resources (call on app destroy)
     * Thread-safe: can be called from any thread
     */
    fun release() {
        if (isReleased) return
        isReleased = true

        // Cancel all coroutines first
        supervisorJob.cancel()
        stopProgressUpdates()

        // Release player on main thread
        mainHandler.post {
            try {
                currentListener?.let { listener ->
                    exoPlayer?.removeListener(listener)
                }
                currentListener = null
                exoPlayer?.release()
                exoPlayer = null
                _playbackInfo.value = PlaybackInfo()
                Log.d(TAG, "ExoPlayer released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player: ${e.message}", e)
            }
        }
    }

    /**
     * Start periodic progress updates
     */
    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (true) {
                updateProgress()
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop progress updates
     */
    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Update progress from player
     */
    private fun updateProgress() {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition
        val progress = (position.toFloat() / duration).coerceIn(0f, 1f)

        _playbackInfo.value = _playbackInfo.value.copy(
            currentPositionMs = position,
            durationMs = duration,
            progress = progress
        )
    }

    /**
     * Get cached audio file path for a message
     */
    private fun getCachedAudioFile(messageId: String): File {
        return File(audioCacheDir, "msg_${messageId}.mp3")
    }

    /**
     * Clear audio cache
     */
    fun clearCache() {
        audioCacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Audio cache cleared")
    }

    /**
     * Check if audio is cached for a message
     */
    fun isCached(messageId: String): Boolean {
        return getCachedAudioFile(messageId).exists()
    }

    /**
     * Длительность кэшированной озвучки (мс) из WAV-заголовка — чтобы показать длительность
     * на кнопке ДО проигрывания (после рестарта/в истории, где живого PlaybackInfo ещё нет).
     * 0 — если файла нет/битый заголовок.
     */
    fun cachedDurationMs(messageId: String): Int {
        val f = getCachedAudioFile(messageId)
        if (!f.exists() || f.length() < 44) return 0
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val hdr = ByteArray(44)
                raf.readFully(hdr)
                fun le32(o: Int): Long =
                    (hdr[o].toLong() and 0xFF) or ((hdr[o + 1].toLong() and 0xFF) shl 8) or
                        ((hdr[o + 2].toLong() and 0xFF) shl 16) or ((hdr[o + 3].toLong() and 0xFF) shl 24)
                val byteRate = le32(28)
                val dataBytes = le32(40)
                if (byteRate <= 0) 0 else (dataBytes * 1000 / byteRate).toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cachedDurationMs failed: ${e.message}"); 0
        }
    }

    /**
     * Перепривязать кэш озвучки с одного id на другой. Нужен при reconcile: стрим-пузырь
     * синтезировал озвучку под runId, потом усыновил серверный messageId — без переноса
     * кэша replay по серверному id не находит файл и РЕ-СИНТЕЗИРУЕТ (долго). Переносим
     * msg_<old>.mp3 → msg_<new>.mp3, чтобы replay был мгновенным.
     */
    fun renameCache(oldId: String, newId: String) {
        if (oldId == newId) return
        val old = getCachedAudioFile(oldId)
        if (!old.exists()) return
        val dest = getCachedAudioFile(newId)
        // renameTo — атомарный syscall на том же volume (cacheDir), без побайтовой копии
        // (она блокировала бы Main → ANR). Fallback на copy только при cross-volume (редко).
        if (old.renameTo(dest)) return
        try {
            old.copyTo(dest, overwrite = true)
            old.delete()
        } catch (e: Exception) {
            Log.w(TAG, "renameCache failed: ${e.message}")
        }
    }

}

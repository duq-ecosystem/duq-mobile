package com.duq.android.service

import android.content.Context
import android.util.Log
import com.duq.android.audio.AudioPlaybackManager
import com.duq.android.audio.AudioRecorderInterface
import com.duq.android.audio.BeepPlayer
import com.duq.android.config.AppConfig
import com.duq.android.error.DuqError
import com.duq.android.network.duq.DuqChatClient
import com.duq.android.ui.DuqState
import java.io.File

/**
 * Голосовой флоу: бип → запись микрофона (VAD) → STT → отправка текста в ядро.
 *
 * Перенесён из app-модуля (Hilt `@Inject`/`@Singleton` убраны — синглтон даёт Koin).
 * Адаптация под KMP-интерфейсы:
 *  - [AudioRecorderInterface.record] и [DuqChatClient.transcribeAudio] принимают путь
 *    как [String] (commonMain не видит java.io.File) → передаём `File.absolutePath`.
 *  - воспроизведение — общий синглтон [AudioPlaybackManager] (ExoPlayer), его же
 *    использует кнопка play в чате: release() не зовём (см. [releasePlayer]).
 */
class VoiceCommandProcessor(
    private val context: Context,
    private val audioRecorder: AudioRecorderInterface,
    private val chatAudioPlaybackManager: AudioPlaybackManager,
    private val beepPlayer: BeepPlayer,
    private val gatewayClient: DuqChatClient,
    private val errorMapper: ErrorMapper,
) {
    companion object { private const val TAG = "VoiceProcessor" }

    sealed class ProcessingResult {
        object Success : ProcessingResult()
        data class Error(val error: DuqError) : ProcessingResult()
        object RecordingFailed : ProcessingResult()
    }

    interface StateCallback {
        fun onStateChanged(state: DuqState)
        fun onError(error: DuqError)
    }

    private var isPlayerInitialized = false

    fun initializePlayer() {
        if (!isPlayerInitialized) {
            chatAudioPlaybackManager.initialize()
            isPlayerInitialized = true
        }
    }

    fun releasePlayer() {
        // НЕ release()! Плеер — общий синглтон (его же использует кнопка play в чате).
        // release() ставил isReleased=true НАВСЕГДА + отменял scope → ВСЁ воспроизведение
        // (в т.ч. кнопка чата) умирало до перезапуска app. Голосовому сервису достаточно
        // остановить текущее проигрывание; жизнь синглтона = жизнь приложения.
        if (isPlayerInitialized) {
            chatAudioPlaybackManager.stop()
            isPlayerInitialized = false
        }
    }

    fun stopRecording() = audioRecorder.stopRecording()

    suspend fun processVoiceCommand(callback: StateCallback): ProcessingResult {
        val audioFile = File(context.cacheDir, AppConfig.AUDIO_TEMP_FILENAME)
        return try {
            beepPlayer.playListeningBeep()
            callback.onStateChanged(DuqState.LISTENING)

            val recorded = audioRecorder.record(audioFile.absolutePath, useVad = true)
            if (!recorded) {
                Log.w(TAG, "Recording too short or failed")
                callback.onStateChanged(DuqState.IDLE)
                return ProcessingResult.RecordingFailed
            }

            callback.onStateChanged(DuqState.PROCESSING)

            // STT
            val transcript = try {
                gatewayClient.transcribeAudio(audioFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "STT failed: ${e.message}")
                val err = DuqError.NetworkError(e.message ?: "STT failed")
                callback.onError(err)
                return ProcessingResult.Error(err)
            }

            if (transcript.isBlank()) {
                callback.onStateChanged(DuqState.IDLE)
                return ProcessingResult.RecordingFailed
            }

            Log.d(TAG, "Transcript: $transcript")
            gatewayClient.sendMessage(transcript)
            callback.onStateChanged(DuqState.IDLE)
            ProcessingResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Voice processing failed: ${e.message}")
            val err = errorMapper.mapException(e)
            callback.onError(err)
            callback.onStateChanged(DuqState.ERROR)
            ProcessingResult.Error(err)
        } finally {
            audioFile.delete()
        }
    }
}

package com.duq.android.audio

import android.content.Context
import android.util.Log
import com.duq.android.config.AppConfig
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

class VoiceActivityDetector(
    private val context: Context,
    private val silenceTimeoutMs: Long = AppConfig.VAD_SILENCE_TIMEOUT_MS,
    private val minRecordingMs: Long = AppConfig.VAD_MIN_RECORDING_MS
) : VoiceActivityDetectorInterface {

    companion object {
        private const val TAG = "VoiceActivityDetector"
    }

    private var vadSilero: VadSilero? = null

    @Volatile private var lastSpeechTime: Long = 0 // Volatile for thread-safe access across audio/main threads

    @Volatile private var recordingStartTime: Long = 0

    @Volatile private var isRecording = false
    private var frameCount = 0

    override fun startRecording() {
        val now = System.currentTimeMillis()
        lastSpeechTime = now
        recordingStartTime = now
        isRecording = true
        frameCount = 0

        try {
            vadSilero?.close()
        } catch (e: Exception) {
            // Ignore
        }

        vadSilero = VadSilero(
            context,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_512,
            mode = Mode.NORMAL,
            silenceDurationMs = AppConfig.SILERO_SILENCE_DURATION_MS,
            speechDurationMs = AppConfig.SILERO_SPEECH_DURATION_MS
        )

        Log.d(TAG, "Started VAD with Silero DNN (NORMAL mode)")
    }

    override fun stopRecording() {
        isRecording = false
        try {
            vadSilero?.close()
        } catch (e: Exception) {
            // Ignore
        }
        vadSilero = null
        Log.d(TAG, "Stopped voice activity detection")
    }

    override fun processAudioBuffer(buffer: ShortArray, readSize: Int): Boolean {
        if (!isRecording) return false

        val vad = vadSilero ?: return false
        frameCount++

        // Silero VAD uses configured frame size at 16kHz
        val frameSize = AppConfig.SILERO_FRAME_SIZE
        var speechDetectedInBuffer = false

        var offset = 0
        while (offset + frameSize <= readSize) {
            val frame = buffer.copyOfRange(offset, offset + frameSize)
            try {
                if (vad.isSpeech(frame)) {
                    speechDetectedInBuffer = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "VAD error", e)
            }
            offset += frameSize
        }

        val now = System.currentTimeMillis()

        if (speechDetectedInBuffer) {
            lastSpeechTime = now
        }

        // Log every ~1 second
        if (frameCount % 30 == 0) {
            val silenceDuration = now - lastSpeechTime
            Log.d(TAG, "Frame $frameCount: speech=$speechDetectedInBuffer, silence=${silenceDuration}ms")
        }

        val recordingDuration = now - recordingStartTime

        // Don't stop before minimum recording time
        if (recordingDuration < minRecordingMs) {
            return false
        }

        // Check if silence exceeded timeout
        val silenceDuration = now - lastSpeechTime
        if (silenceDuration >= silenceTimeoutMs) {
            Log.d(TAG, "Silence confirmed after ${silenceDuration}ms (recorded ${recordingDuration}ms)")
            return true
        }

        return false
    }

    override fun reset() {
        lastSpeechTime = System.currentTimeMillis()
        frameCount = 0
    }
}

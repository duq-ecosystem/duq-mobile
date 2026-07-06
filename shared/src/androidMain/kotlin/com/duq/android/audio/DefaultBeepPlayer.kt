package com.duq.android.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Android-реализация [BeepPlayer] — системный бип через ToneGenerator.
 * Вынесено из VoiceCommandProcessor ради SRP.
 */
class DefaultBeepPlayer : BeepPlayer {

    companion object {
        private const val TAG = "BeepPlayer"
        private const val BEEP_VOLUME = 80
        private const val BEEP_DURATION_MS = 150
        private const val BEEP_DELAY_MS = 200L
    }

    override suspend fun playListeningBeep() {
        val toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create tone generator", e); return
        }
        try {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            delay(BEEP_DELAY_MS)
        } catch (e: CancellationException) {
            throw e // отмена голосового флоу — не ошибка, и её нельзя глотать
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play beep", e)
        } finally {
            // release и при исключении, и при отмене delay — иначе утечка аудио-ресурса.
            toneGenerator.release()
        }
    }
}

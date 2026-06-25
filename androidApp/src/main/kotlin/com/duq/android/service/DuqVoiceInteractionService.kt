package com.duq.android.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android VoiceInteractionService — позволяет назначить DUQ ассистентом по умолчанию
 * (системная роль ассистента / активация «Окей DUQ» через системный жест).
 *
 * Перенесён из app-модуля без изменений. Намеренно НЕ авто-стартует прослушку: работает
 * только когда приложение открыто (фоновый микрофон не поднимаем — см. DuqListenerService).
 */
class DuqVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "DuqVoiceService"
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "Voice interaction service ready")
        // Don't auto-start listener - only works when app is open
    }
}

package com.duq.android.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Сессия системного ассистента DUQ (создаётся, когда DUQ назначен ассистентом по
 * умолчанию и его вызывают). Перенесён из app-модуля без изменений логики.
 */
class DuqVoiceInteractionSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "DuqSessionService"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "New voice interaction session")
        return DuqVoiceInteractionSession(this)
    }
}

class DuqVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "DuqSession"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Voice session shown")
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "Voice session hidden")
    }
}

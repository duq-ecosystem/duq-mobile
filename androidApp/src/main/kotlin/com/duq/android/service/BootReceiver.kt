package com.duq.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Получатель BOOT_COMPLETED. Перенесён из app-модуля без изменений: сервис НЕ стартует на
 * загрузке (работает только когда приложение открыто) — приёмник оставлен как точка
 * расширения и для совместимости с манифестом.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed - service will start when app is opened")
            // Don't auto-start on boot - only works when app is open
        }
    }
}

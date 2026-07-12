package com.duq.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Принимает статус PackageInstaller-сессии для self-update установки. Перенесён из
 * `app/.../update/InstallResultReceiver.kt` (Hilt не было — чистый BroadcastReceiver).
 *
 * Ключевой случай — STATUS_PENDING_USER_ACTION: ОС просит подтверждение установки и
 * отдаёт confirm-Intent. Запускаем напрямую если можем (app foreground); если фоновый
 * старт активити заблокирован (Android 10+) — fallback на full-screen-intent
 * уведомление, поднимающее диалог подтверждения.
 *
 * ВАЖНО: androidApp ОБЯЗАН задекларировать этот receiver в AndroidManifest.xml
 * (android:name="com.duq.android.ui.InstallResultReceiver", exported=false,
 * intent-filter action "com.duq.android.INSTALL_STATUS").
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val version = intent.getIntExtra(EXTRA_VERSION, -1)
        Log.i(TAG, "InstallResultReceiver status=$status v$version")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.e(TAG, "PENDING_USER_ACTION но нет confirm intent")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Юзер только что нажал «УСТАНОВИТЬ» → app foreground, прямой startActivity
                // разрешён — поднимаем диалог сразу.
                val launched = runCatching { context.startActivity(confirm) }.isSuccess
                Log.i(TAG, "PENDING_USER_ACTION: прямой startActivity launched=$launched")
                // Плюс persistent full-screen-intent уведомление как fallback: если app в
                // фоне, прямой старт молча отбрасывается background-activity-start политикой.
                raiseConfirmNotification(context, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Обновление установлено (v$version)")
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
                context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_AVAILABLE_VERSION).apply()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(TAG, "Установка не удалась: status=$status msg=$msg (v$version)")
                // Юзер отменил (STATUS_FAILURE_ABORTED) / иная ошибка: убрать ongoing
                // confirm-уведомление, чтобы не залипло несмахиваемым. In-app баннер
                // остаётся — можно повторить оттуда.
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
            }
        }
    }

    private fun raiseConfirmNotification(context: Context, confirm: Intent, version: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DUQ Updates", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "App update notifications" }
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                )
        val pi = PendingIntent.getActivity(context, version, confirm, flags)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DUQ Update готов")
            .setContentText("Нажми чтобы установить v$version")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            // Persistent: остаётся, если юзер случайно закрыл диалог — можно тапнуть снова.
            // Снимается на STATUS_SUCCESS. autoCancel=false + ongoing: свайп не теряет апдейт.
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFY_ID, n)
        Log.i(TAG, "Confirm-уведомление поднято для v$version")
    }

    companion object {
        private const val TAG = "AppUpdater"
        const val ACTION_INSTALL_STATUS = "com.duq.android.INSTALL_STATUS"
        const val EXTRA_VERSION = "version"
        private const val CHANNEL_ID = "duq_update_channel"
        private const val NOTIFY_ID = 9002

        // Должны совпадать с константами AndroidAppUpdateController (общий prefs-store).
        private const val UPDATE_PREFS = "duq_update"
        private const val KEY_AVAILABLE_VERSION = "available_version"
    }
}

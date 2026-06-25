package com.duq.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.duq.android.config.AppSecrets
import com.duq.android.di.appAndroidModule
import com.duq.android.di.sharedModules
import com.duq.android.service.DuqListenerService
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Android entry-point. Кладёт секреты сборки (edge-токен/GitHub-токен) из BuildConfig в
 * [AppSecrets] (shared не видит BuildConfig напрямую) и стартует Koin со всем графом
 * shared (network/audio/viewModel/platform). androidContext() даёт Context реализациям,
 * которым он нужен (audio, UI-мосты, updater).
 *
 * Presence (WS /duq/ws) держит ПОСТОЯННЫЙ foreground-сервис [DuqListenerService] (перенесён
 * из оригинала): раньше WS стартовал прямо отсюда и жил только пока жив процесс — при
 * сворачивании Android мог убить процесс → presence терялся, бот не достукивался. FGS с
 * уведомлением переживает сворачивание. WS-клиенты (DuqNodeClient/DuqChatClient) стартует
 * сам сервис в onCreate, поэтому прямой вызов start() отсюда убран.
 */
class DuqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSecrets.serverToken = BuildConfig.SERVER_TOKEN
        AppSecrets.githubReleaseToken = BuildConfig.GH_RELEASE_TOKEN
        startKoin {
            androidContext(this@DuqApplication)
            modules(sharedModules() + appAndroidModule)
        }
        createNotificationChannels()
        startListenerService()
    }

    /** Запуск постоянного FGS-присутствия. ACTION_START переводит сервис в foreground. */
    private fun startListenerService() {
        val intent = Intent(this, DuqListenerService::class.java).apply {
            action = DuqListenerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        // Сервисный канал — минимальный, без звука.
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DUQ Service", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background connection service"
                    setShowBadge(false)
                }
            )
        }
        // Канал сообщений — высокий приоритет для ответов DUQ в фоне.
        if (nm.getNotificationChannel(MESSAGES_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(MESSAGES_CHANNEL_ID, "DUQ Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "DUQ responses when app is in background"
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "duq_listener_channel"
        const val MESSAGES_CHANNEL_ID = "duq_messages_channel"
    }
}

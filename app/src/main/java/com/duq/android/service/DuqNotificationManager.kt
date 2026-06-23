package com.duq.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.duq.android.DuqApplication
import com.duq.android.MainActivity
import com.duq.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DuqNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        private val msgCounter = AtomicInteger(100)
    }

    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Persistent foreground service notification — minimal, silent */
    fun createServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 0,
            Intent(context, DuqListenerService::class.java).apply { action = DuqListenerService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, DuqApplication.CHANNEL_ID)
            .setContentTitle("DUQ")
            .setContentText("Connected")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.stop_service), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * Show a message notification when DUQ sends a response while app is in background.
     * [type] tags the inbox item ("message" | "digest" | "system" | …) so the UI can
     * route it into a dedicated section (e.g. the 📰 Дайджест feed for "digest").
     */
    fun showMessageNotification(text: String, title: String = "DUQ", type: String = "message") {
        // Всё (сообщения, апдейты, система, дайджесты) падает в ЕДИНЫЙ центр
        // уведомлений (🔔 шторка). Дайджесты — раздел «Дайджесты» внутри той же шторки.
        val now = System.currentTimeMillis()
        com.duq.android.data.NotificationInbox.record(context, title, text, type, now)
        val id = msgCounter.getAndIncrement()
        val openIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Пуш «обновление ядра доступно» → тап открывает раздел «Движок» (deep-link).
                if (type == "core_update") putExtra("open_section", "version")
                // Пуш дайджеста → тап открывает шторку уведомлений на разделе «Дайджесты».
                if (type == "digest") putExtra("open_notifications", "digest")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DuqApplication.MESSAGES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }
}

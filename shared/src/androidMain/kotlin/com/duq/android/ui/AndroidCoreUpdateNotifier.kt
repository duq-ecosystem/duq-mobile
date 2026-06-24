package com.duq.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.duq.android.logging.Logger
import com.duq.android.network.CoreUpdateClient

/**
 * Android-реализация [CoreUpdateNotifier] — системное уведомление о завершении апдейта
 * ЯДРА (DUQ core). Перенесена из `app/.../update/CoreUpdateNotifier.kt` (был object с
 * Context-параметром + DuqNotificationManager) → класс с конструкторным Context (Koin),
 * показ нотификации инлайнен через NotificationManager (без app-only DuqNotificationManager).
 *
 * После апдейта ядра движок сам себя проверяет (/health) и пишет результат, который
 * update_server отдаёт в `status.result`. Здесь показываем юзеру уведомление ОДИН раз на
 * результат (дедуп по `result.ts` в SharedPreferences). Также пишем в центр уведомлений.
 */
class AndroidCoreUpdateNotifier(
    private val context: Context,
    private val logger: Logger,
) : CoreUpdateNotifier {

    override fun notifyResult(status: CoreUpdateClient.Status) {
        val res = status.result ?: return
        if (status.running || res.ts.isBlank()) return            // апдейт ещё идёт / нет результата
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_RESULT_TS, null) == res.ts) return  // про этот результат уже уведомляли

        val title = if (res.ok) "✅ Ядро обновлено" else "⚠️ Ядро: проблема после обновления"
        val text = res.summary.ifBlank {
            if (res.ok) "Ядро обновлено до ${res.version ?: "?"} — всё работает ✅"
            else "Ядро обновлено до ${res.version ?: "?"}, но есть проблема — проверь Движок"
        }
        showNotification(title, text)
        AndroidNotificationInbox.record(context, title, text, "update", System.currentTimeMillis())
        prefs.edit().putString(KEY_RESULT_TS, res.ts).apply()
        logger.i(TAG, "уведомление о результате (ok=${res.ok}, v=${res.version}) отправлено")
    }

    private fun showNotification(title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DUQ Core", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Core update notifications" }
            )
        }
        val open = Intent().apply {
            setClassName(context.packageName, LAUNCH_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_section", "engine") // deep-link в раздел «Движок»
        }
        val pi = PendingIntent.getActivity(
            context, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private companion object {
        const val TAG = "CoreUpdate"
        const val PREFS = "duq_core_update"
        const val KEY_RESULT_TS = "result_ts_notified"
        const val CHANNEL_ID = "duq_core_update_channel"
        const val NOTIFY_ID = 9101
        const val LAUNCH_ACTIVITY = "com.duq.android.MainActivity"
    }
}

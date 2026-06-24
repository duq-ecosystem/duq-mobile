package com.duq.android.ui

import com.duq.android.logging.Logger
import com.duq.android.network.CoreUpdateClient
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS-реализация [CoreUpdateNotifier] — локальное уведомление о завершении апдейта ядра
 * через UNUserNotificationCenter (штатный iOS-механизм, не лог-болванка). Дедуп по
 * `result.ts` в NSUserDefaults. Также пишет в in-memory центр уведомлений.
 *
 * Триггер не задаётся → уведомление доставляется немедленно. Если разрешение на
 * уведомления не выдано, iOS просто не покажет баннер — запись в центр всё равно идёт.
 */
class IosCoreUpdateNotifier(
    private val logger: Logger,
) : CoreUpdateNotifier {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun notifyResult(status: CoreUpdateClient.Status) {
        val res = status.result ?: return
        if (status.running || res.ts.isBlank()) return
        if (defaults.stringForKey(KEY_RESULT_TS) == res.ts) return  // уже уведомляли

        val title = if (res.ok) "✅ Ядро обновлено" else "⚠️ Ядро: проблема после обновления"
        val text = res.summary.ifBlank {
            if (res.ok) "Ядро обновлено до ${res.version ?: "?"} — всё работает ✅"
            else "Ядро обновлено до ${res.version ?: "?"}, но есть проблема — проверь Движок"
        }
        showNotification(title, text)
        val nowMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
        IosNotificationInbox.record(title, text, "update", nowMs)
        defaults.setObject(res.ts, forKey = KEY_RESULT_TS)
        logger.i(TAG, "уведомление о результате (ok=${res.ok}, v=${res.version}) отправлено")
    }

    private fun showNotification(title: String, text: String) {
        runCatching {
            val content = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(text)
            }
            // (K/N экспонирует title/body как property + сеттер; используем сеттер-метод.)
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = "core_update_${NSDate().timeIntervalSince1970}",
                content = content,
                trigger = null, // немедленная доставка
            )
            UNUserNotificationCenter.currentNotificationCenter()
                .addNotificationRequest(request, withCompletionHandler = null)
        }.onFailure { logger.w(TAG, "локальное уведомление не показано: ${it.message}") }
    }

    private companion object {
        const val TAG = "CoreUpdate"
        const val KEY_RESULT_TS = "duq_core_update_result_ts"
    }
}

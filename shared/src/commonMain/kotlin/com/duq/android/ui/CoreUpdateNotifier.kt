package com.duq.android.ui

import com.duq.android.network.CoreUpdateClient

/**
 * Платформенное уведомление о завершении апдейта ЯДРА (DUQ core). Когда бэкенд
 * `/core-update` дописал self-check результат, показываем юзеру системную нотификацию
 * («добро пожаловать» / «ошибка апдейта»), дедуп по `result.ts`.
 *
 * Интерфейс — общий код KMP (commonMain); реализация платформенная (androidMain:
 * NotificationManager; iosMain: UNUserNotificationCenter/деградация-no-op). Вынесен из
 * android-only `object CoreUpdateNotifier` (Context) для [com.duq.android.ui.control.SectionViewModel].
 */
interface CoreUpdateNotifier {
    /** Уведомить о результате апдейта ядра, если он новый (дедуп внутри реализации по ts). */
    fun notifyResult(status: CoreUpdateClient.Status)
}

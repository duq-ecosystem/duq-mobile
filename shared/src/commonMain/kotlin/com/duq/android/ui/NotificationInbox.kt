package com.duq.android.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Центр уведомлений (🔔) — единый список входящих (сообщения/апдейты/системные/дайджесты),
 * который читает [com.duq.android.ui.ConversationViewModel] для шторки уведомлений.
 *
 * Интерфейс — общий код KMP (commonMain); реализация платформенная (androidMain:
 * SharedPreferences-персист + системные нотификации; iosMain: UserDefaults/деградация).
 * Вынесен из android-only `com.duq.android.data.NotificationInbox` (Context/Gson) —
 * VM держит чистую мультиплатформенную зависимость на этот интерфейс.
 */
interface NotificationInbox {

    /** Один элемент центра уведомлений. */
    data class Item(
        val id: Long,
        val title: String,
        val text: String,
        val timestampMs: Long,
        val type: String // "message" | "update" | "system" | "digest"
    )

    /** Текущий список элементов (для шторки). */
    val items: StateFlow<List<Item>>

    /** Число непрочитанных (для бейджа на колокольчике). */
    val unread: StateFlow<Int>

    /** Перечитать из персист-хранилища (напр. при возврате app из фона). */
    fun refresh()

    /** Пометить все элементы прочитанными (сброс бейджа при открытии шторки). */
    fun markOpened()

    /** Очистить весь центр уведомлений. */
    fun clear()

    /** Очистить только дайджесты (type == "digest"). */
    fun clearDigests()

    /** Очистить только обычные уведомления (type != "digest"). */
    fun clearNotifs()
}

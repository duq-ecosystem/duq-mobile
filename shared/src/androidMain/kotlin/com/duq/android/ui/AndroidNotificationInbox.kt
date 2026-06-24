package com.duq.android.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Android-реализация [NotificationInbox] — центр уведомлений (🔔). Перенесена из
 * `app/.../data/NotificationInbox.kt`: Gson → kotlinx.serialization, Hilt-Context →
 * конструкторный Context (Koin даёт через androidContext()). Бэк — SharedPreferences
 * (JSON), переживает рестарт.
 *
 * Непрочитанные: число items новее, чем `lastOpened`. Висит, пока юзер не откроет
 * шторку ([markOpened] → бейдж гаснет). Пишется из non-DI мест (AppUpdater /
 * CoreUpdateNotifier / PhoneCommandExecutor) через статический [record], обновляющий
 * общий companion-flow → бейдж и список обновляются вживую без перезахода.
 */
class AndroidNotificationInbox(
    private val context: Context,
) : NotificationInbox {

    override val items: StateFlow<List<NotificationInbox.Item>> = _items.asStateFlow()
    override val unread: StateFlow<Int> = _unread.asStateFlow()

    init {
        _items.value = load(context)
        _unread.value = computeUnread(context, _items.value)
    }

    override fun refresh() = synchronized(LOCK) {
        _items.value = load(context)
        _unread.value = computeUnread(context, _items.value)
    }

    /** Юзер открыл шторку → всё прочитано, бейдж гаснет. */
    fun markOpened() = synchronized(LOCK) {
        prefs(context).edit().putLong(KEY_OPENED, System.currentTimeMillis()).commit()
        _unread.value = 0
    }

    override fun clear() = synchronized(LOCK) {
        prefs(context).edit().remove(KEY).commit()
        _items.value = emptyList()
        _unread.value = 0
    }

    /** Очистить только дайджесты (вкладка «Дайджесты»), уведомления не трогая. */
    fun clearDigests() = removeMatching { it.type == "digest" }

    /** Очистить только уведомления (вкладка «Уведомления»), дайджесты не трогая. */
    fun clearNotifs() = removeMatching { it.type != "digest" }

    private fun removeMatching(remove: (NotificationInbox.Item) -> Boolean) = synchronized(LOCK) {
        val updated = load(context).filterNot(remove)
        prefs(context).edit()
            .putString(KEY, json.encodeToString(LIST_SER, updated.map { it.toStored() }))
            .commit()
        _items.value = updated
        _unread.value = computeUnread(context, updated)
    }

    companion object {
        private const val PREFS = "duq_inbox"
        private const val KEY = "items"
        private const val KEY_OPENED = "last_opened"
        private const val MAX = 100
        private val LOCK = Any()
        private val seq = AtomicLong(0)
        private val json = Json { ignoreUnknownKeys = true }
        private val LIST_SER = ListSerializer(StoredItem.serializer())

        // Общий источник правды для UI — и instance, и static record() пишут сюда.
        private val _items = MutableStateFlow<List<NotificationInbox.Item>>(emptyList())
        private val _unread = MutableStateFlow(0)

        // Сериализуемый аналог NotificationInbox.Item (интерфейсный data class в commonMain
        // не помечен @Serializable; держим зеркало для персиста).
        @Serializable
        private data class StoredItem(
            val id: Long,
            val title: String,
            val text: String,
            val timestampMs: Long,
            val type: String,
        )

        private fun StoredItem.toItem() = NotificationInbox.Item(id, title, text, timestampMs, type)
        private fun NotificationInbox.Item.toStored() = StoredItem(id, title, text, timestampMs, type)

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun load(c: Context): List<NotificationInbox.Item> = try {
            val raw = prefs(c).getString(KEY, null) ?: return emptyList()
            json.decodeFromString(LIST_SER, raw).map { it.toItem() }
        } catch (_: Exception) {
            emptyList()
        }

        private fun computeUnread(c: Context, items: List<NotificationInbox.Item>): Int {
            val opened = prefs(c).getLong(KEY_OPENED, 0L)
            return items.count { it.timestampMs > opened }
        }

        /**
         * Append из ЛЮБОЙ точки (DI или нет). Newest first, capped. Обновляет общий flow +
         * пересчитывает непрочитанные → бейдж растёт вживую.
         */
        fun record(context: Context, title: String, text: String, type: String, timestampMs: Long) =
            synchronized(LOCK) {
                val current = load(context)
                // id уникален (ключ LazyColumn) — два уведомления в одну мс иначе коллизятся
                // по timestampMs и роняют Compose «duplicate keys».
                val id = timestampMs * 1000 + (seq.getAndIncrement() % 1000)
                val updated =
                    (listOf(NotificationInbox.Item(id, title, text, timestampMs, type)) + current)
                        .take(MAX)
                prefs(context).edit()
                    .putString(KEY, json.encodeToString(LIST_SER, updated.map { it.toStored() }))
                    .commit()
                _items.value = updated
                _unread.value = computeUnread(context, updated)
            }
    }
}

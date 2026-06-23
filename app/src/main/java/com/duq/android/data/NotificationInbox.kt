package com.duq.android.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app notification center. Всё, что DUQ присылает (сообщения, апдейты, система,
 * И дайджесты), оседает здесь — единая шторка уведомлений на всех экранах.
 *
 * Непрочитанные: бейдж = число items новее, чем `lastOpened`. Висит, пока юзер не
 * откроет шторку ([markOpened] → бейдж гаснет). Backed by SharedPreferences (JSON),
 * переживает рестарт; пишется из non-Hilt мест (AppUpdater, PhoneCommandExecutor)
 * через статический [record], который обновляет общий companion-flow → бейдж и список
 * обновляются вживую без перезахода.
 */
@Singleton
class NotificationInbox @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @androidx.annotation.Keep
    data class Item(
        val id: Long,
        val title: String,
        val text: String,
        val timestampMs: Long,
        val type: String // "message" | "update" | "system" | "digest"
    )

    val items: StateFlow<List<Item>> = _items.asStateFlow()
    val unread: StateFlow<Int> = _unread.asStateFlow()

    init {
        _items.value = load(context)
        _unread.value = computeUnread(context, _items.value)
    }

    fun refresh() = synchronized(LOCK) {
        _items.value = load(context)
        _unread.value = computeUnread(context, _items.value)
    }

    /** Юзер открыл шторку → всё прочитано, бейдж гаснет. */
    fun markOpened() = synchronized(LOCK) {
        prefs(context).edit().putLong(KEY_OPENED, System.currentTimeMillis()).commit()
        _unread.value = 0
    }

    fun clear() = synchronized(LOCK) {
        prefs(context).edit().remove(KEY).commit()
        _items.value = emptyList()
        _unread.value = 0
    }

    companion object {
        private const val PREFS = "duq_inbox"
        private const val KEY = "items"
        private const val KEY_OPENED = "last_opened"
        private const val MAX = 100
        private val gson = Gson()
        private val LOCK = Any()
        private val seq = java.util.concurrent.atomic.AtomicLong(0)

        // Общий источник правды для UI — и instance, и static record() пишут сюда.
        private val _items = MutableStateFlow<List<Item>>(emptyList())
        private val _unread = MutableStateFlow(0)

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun load(c: Context): List<Item> = try {
            val json = prefs(c).getString(KEY, null) ?: return emptyList()
            gson.fromJson(json, object : TypeToken<List<Item>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        private fun computeUnread(c: Context, items: List<Item>): Int {
            val opened = prefs(c).getLong(KEY_OPENED, 0L)
            return items.count { it.timestampMs > opened }
        }

        /**
         * Append from ANY call site (Hilt or not). Newest first, capped. Обновляет
         * общий flow + пересчитывает непрочитанные → бейдж растёт вживую.
         */
        fun record(context: Context, title: String, text: String, type: String, timestampMs: Long) =
            synchronized(LOCK) {
                val current = load(context)
                // id уникален (ключ LazyColumn) — два уведомления в одну мс иначе
                // коллизятся по timestampMs и роняют Compose «duplicate keys».
                val id = timestampMs * 1000 + (seq.getAndIncrement() % 1000)
                val updated = (listOf(Item(id, title, text, timestampMs, type)) + current).take(MAX)
                prefs(context).edit().putString(KEY, gson.toJson(updated)).commit()
                _items.value = updated
                _unread.value = computeUnread(context, updated)
            }
    }
}

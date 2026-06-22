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
 * Лента дайджестов (📰) — отдельная сущность от истории уведомлений ([NotificationInbox]).
 * Дайджесты (финансовые/новостные сводки от агента) живут в своём хранилище и своей ленте.
 *
 * ⚠️ Live: [record] вызывается из non-Hilt места (PhoneCommandExecutor → DuqNotificationManager),
 * поэтому общий StateFlow держим в companion (static). И instance (у ViewModel через Hilt),
 * и record() пишут в ОДИН [_flow] → лента обновляется в UI мгновенно, без перезахода в app.
 * (Раньше record() писал только prefs, а живой _items синглтона не трогал → лист не обновлялся.)
 */
@Singleton
class DigestInbox @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @androidx.annotation.Keep
    data class Item(
        val id: Long,
        val title: String,
        val text: String,
        val timestampMs: Long
    )

    val items: StateFlow<List<Item>> = _flow.asStateFlow()

    init {
        // Подтянуть сохранённое при создании синглтона (старт приложения).
        refresh()
    }

    fun refresh() = synchronized(LOCK) { _flow.value = load(context) }

    fun clear() = synchronized(LOCK) {
        prefs(context).edit().remove(KEY).commit()
        _flow.value = emptyList()
    }

    companion object {
        private const val PREFS = "duq_digest"
        private const val KEY = "items"
        private const val MAX = 100
        private val gson = Gson()
        private val LOCK = Any()
        private val seq = java.util.concurrent.atomic.AtomicLong(0)

        // Единый источник правды для UI — общий для instance и record().
        private val _flow = MutableStateFlow<List<Item>>(emptyList())

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun load(c: Context): List<Item> = try {
            val json = prefs(c).getString(KEY, null) ?: return emptyList()
            gson.fromJson(json, object : TypeToken<List<Item>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        /** Append a digest from ANY call site (incl. non-Hilt). Newest first, capped, LIVE. */
        fun record(context: Context, title: String, text: String, timestampMs: Long) =
            synchronized(LOCK) {
                val current = load(context)
                val id = timestampMs * 1000 + (seq.getAndIncrement() % 1000)
                val updated = (listOf(Item(id, title, text, timestampMs)) + current).take(MAX)
                val ok = prefs(context).edit().putString(KEY, gson.toJson(updated)).commit()
                _flow.value = updated  // ← живое обновление ленты в UI
                com.duq.android.logging.FileLogger(context).i(
                    "DigestInbox", "record ok=$ok now=${updated.size} title=$title"
                )
            }
    }
}

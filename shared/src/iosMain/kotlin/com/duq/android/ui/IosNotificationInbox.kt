package com.duq.android.ui

import com.duq.android.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS-реализация [NotificationInbox] — in-memory (без системного persist на старте).
 * Список живёт в памяти процесса; [refresh] — no-op (нечего перечитывать), [clear]
 * чистит. UserDefaults-персист — отдельная итерация (отмечено в отчёте).
 *
 * record()/markOpened — статические хелперы для записи из non-DI мест (паритет с
 * Android), пишут в общий companion-flow → UI обновляется вживую.
 */
class IosNotificationInbox(
    private val logger: Logger,
) : NotificationInbox {

    override val items: StateFlow<List<NotificationInbox.Item>> = _items.asStateFlow()
    override val unread: StateFlow<Int> = _unread.asStateFlow()

    init {
        logger.d(TAG, "in-memory центр уведомлений (без UserDefaults-персиста на старте)")
    }

    /** Юзер открыл шторку → бейдж гаснет. */
    fun markOpened() {
        lastOpenedMs = nowMs()
        _unread.value = 0
    }

    override fun refresh() {
        // in-memory — перечитывать нечего; пересчитываем бейдж по текущему списку.
        _unread.value = _items.value.count { it.timestampMs > lastOpenedMs }
    }

    override fun clear() {
        _items.value = emptyList()
        _unread.value = 0
    }

    companion object {
        private const val TAG = "NotificationInbox"
        private const val MAX = 100
        private var lastOpenedMs: Long = 0L
        private var seq: Long = 0L

        private val _items = MutableStateFlow<List<NotificationInbox.Item>>(emptyList())
        private val _unread = MutableStateFlow(0)

        private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

        /** Append из любой точки (паритет с Android). Newest first, capped. */
        fun record(title: String, text: String, type: String, timestampMs: Long) {
            val id = timestampMs * 1000 + (seq++ % 1000)
            val updated =
                (listOf(NotificationInbox.Item(id, title, text, timestampMs, type)) + _items.value)
                    .take(MAX)
            _items.value = updated
            _unread.value = updated.count { it.timestampMs > lastOpenedMs }
        }
    }
}

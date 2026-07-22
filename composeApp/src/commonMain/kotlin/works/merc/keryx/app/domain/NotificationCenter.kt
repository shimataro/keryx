package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.AppNotification

/**
 * Session-only notification history (the bell icon). Repositories push
 * warnings/errors here; the UI observes [items]. Not persisted.
 */
class NotificationCenter {
    private val _items = MutableStateFlow<List<AppNotification>>(emptyList())
    val items: StateFlow<List<AppNotification>> = _items.asStateFlow()

    fun add(notification: AppNotification) {
        _items.update { listOf(notification) + it }
    }

    /**
     * Adds [notification], coalescing it with any existing notification that shares the same
     * level, message, and action: the prior duplicate is dropped and the new one prepended
     * (single entry, refreshed timestamp + position). For repeatable notices such as a recurring
     * background/debounced sync failure, so identical entries never pile up.
     */
    fun addCoalescing(notification: AppNotification) {
        _items.update { list ->
            listOf(notification) + list.filterNot {
                it.level == notification.level &&
                    it.message == notification.message &&
                    it.action == notification.action
            }
        }
    }

    fun dismiss(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun dismissAll() {
        _items.value = emptyList()
    }
}

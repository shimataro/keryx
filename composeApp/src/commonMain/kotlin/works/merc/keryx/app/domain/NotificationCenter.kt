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

    /**
     * Adds a notification to the front of the session history.
     *
     * @param notification The notification to add.
     */
    fun add(notification: AppNotification) {
        _items.update { listOf(notification) + it }
    }

    /**
     * Adds a notification while keeping only the newest entry with the same level, message, and action.
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

    /**
     * Removes the notification with the specified identifier.
     *
     * @param id The identifier of the notification to remove.
     */
    fun dismiss(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun dismissAll() {
        _items.value = emptyList()
    }
}

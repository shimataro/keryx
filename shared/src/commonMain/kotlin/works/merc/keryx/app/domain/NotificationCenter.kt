package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.AlertKey
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.alertKey

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
     * Adds a notification while keeping only the newest entry saying the same thing — see
     * [AlertKey], which is also what the Android foreground Snackbar keys its already-surfaced
     * bookkeeping on, so the two agree on what counts as a recurrence.
     */
    fun addCoalescing(notification: AppNotification) {
        val key = notification.alertKey()
        _items.update { list -> listOf(notification) + list.filterNot { it.alertKey() == key } }
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

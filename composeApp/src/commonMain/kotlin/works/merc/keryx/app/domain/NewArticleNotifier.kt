package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.valueOrNull

/**
 * Bridges "new articles fetched" events — from the periodic background loop, the once-per-launch
 * startup check, and manual refresh — to the OS tray (via [trayEvents], observed in `main.kt`).
 *
 * Deliberately NOT recorded in the in-app notification center (the bell): new articles are already
 * durably visible in the article list and the unread badges, so a bell entry would only be noise —
 * the bell is reserved for things worth looking back at (warnings, errors, a new app version), each
 * of which offers a next action.
 */
class NewArticleNotifier {
    private val _trayEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val trayEvents: SharedFlow<String> = _trayEvents

    /**
     * Publishes a message for display through the OS tray event stream.
     *
     * @param message The message to publish.
     */
    fun notify(message: String) {
        _trayEvents.tryEmit(message)
    }

    /**
     * Sums the new-article counts from a [FeedRepository.refreshAll] result and, if any were
     * fetched and notifications are enabled, publishes a tray notification via [notify].
     */
    suspend fun notifyIfEnabled(
        results: Map<String, Result<Int>>,
        notificationEnabled: Boolean,
        messages: NotificationMessages,
    ) {
        val newCount = results.values.sumOf { it.valueOrNull ?: 0 }
        if (newCount > 0 && notificationEnabled) {
            notify(messages.newArticles(newCount))
        }
    }
}

package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.valueOrNull

/**
 * Bridges "new articles fetched" events — from the periodic background loop, the once-per-launch
 * startup check, and manual refresh — to the OS's notification surface.
 *
 * Desktop observes [trayEvents] itself (`main.kt` collects it for the whole process lifetime, see
 * that flow's own KDoc for why a plain `SharedFlow` is enough there) rather than relying on [sink].
 * Android instead posts through [sink] directly: a `WorkManager`-run background refresh can start
 * this class fresh in a process the OS just woke up for that one job, and [trayEvents] (replay 0)
 * silently drops anything emitted before a collector attaches — a race an `Application.onCreate`
 * subscriber can't fully close. [sink] has no such race, since it's called synchronously in the
 * same call as the emit. [sink] defaults to a no-op so desktop and the 8 existing
 * `NewArticleNotifier()` call sites in tests stay unchanged.
 *
 * Deliberately NOT recorded in the in-app notification center (the bell): new articles are already
 * durably visible in the article list and the unread badges, so a bell entry would only be noise —
 * the bell is reserved for things worth looking back at (warnings, errors, a new app version), each
 * of which offers a next action.
 */
class NewArticleNotifier(private val sink: OsNotificationSink = OsNotificationSink { }) {
    private val _trayEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val trayEvents: SharedFlow<String> = _trayEvents

    /**
     * Publishes a message for display through the OS tray event stream and [sink].
     *
     * @param message The message to publish.
     */
    suspend fun notify(message: String) {
        _trayEvents.tryEmit(message)
        sink.post(message)
    }

    /**
     * Publishes a tray notification when newly fetched articles are available and notifications are enabled.
     *
     * @param results The article counts grouped by source; unavailable counts are treated as zero.
     * @param notificationEnabled Whether tray notifications are enabled.
     * @param messages Messages used to format the new-articles notification.
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

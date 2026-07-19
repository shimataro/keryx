package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock

/**
 * Bridges "new articles fetched" events from automatic (non-user-initiated) refreshes — the
 * periodic background loop and the once-per-launch startup check — to the UI. An automatic
 * refresh is silent — the user isn't looking — so it goes to both the OS tray (via [trayEvents],
 * observed in `main.kt`) and the in-app notification center (the bell). A manual refresh is NOT
 * routed here: its result is already visible in the article list and unread badges, so it needs
 * no separate notice.
 */
class NewArticleNotifier(
    private val notificationCenter: NotificationCenter,
    private val clock: Clock,
) {
    private val _trayEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val trayEvents: SharedFlow<String> = _trayEvents

    fun notifyBackground(message: String) {
        _trayEvents.tryEmit(message)
        notificationCenter.add(
            AppNotification(
                id = IdGenerator.newId(),
                level = AppNotificationLevel.INFO,
                message = message,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }
}

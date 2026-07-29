package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bridges "new articles fetched" events from automatic (non-user-initiated) refreshes — the
 * periodic background loop and the once-per-launch startup check — to the OS tray (via
 * [trayEvents], observed in `main.kt`). An automatic refresh is silent — the user isn't looking —
 * so the OS notification is what tells them something arrived.
 *
 * Deliberately NOT recorded in the in-app notification center (the bell): new articles are already
 * durably visible in the article list and the unread badges, so a bell entry would only be noise —
 * the bell is reserved for things worth looking back at (warnings, errors, a new app version), each
 * of which offers a next action. A manual refresh is not routed here at all, for the same reason.
 */
class NewArticleNotifier {
    private val _trayEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val trayEvents: SharedFlow<String> = _trayEvents

    fun notifyBackground(message: String) {
        _trayEvents.tryEmit(message)
    }
}

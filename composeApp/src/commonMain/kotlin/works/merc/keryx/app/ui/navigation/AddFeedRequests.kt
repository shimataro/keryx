package works.merc.keryx.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Hands a feed URL that arrived from outside the app — today, a link shared to Keryx from another
 * Android app (`ACTION_SEND`, see `AndroidSharedLink.kt`) — to the Add feed dialog, pre-filled.
 *
 * The dialog belongs to [Screen.Home] and must never open over first-run Setup, so a request made
 * while Setup is showing is **latched** and released only once Home is current; the last request
 * wins (a later one replaces an unreleased earlier one). `HomeScreen` is the one consumer: its
 * `LaunchedEffect` on [pending] calls [release] and opens the dialog with the returned URL. Nothing
 * is ever subscribed from here — the user confirms in the dialog. Modeled on [SettingsOpenRequests].
 *
 * App-scoped Koin singleton.
 */
class AddFeedRequests {
    private val _pending = MutableStateFlow<String?>(null)

    /** The URL waiting to be released, if any. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Asks for the Add feed dialog pre-filled with [url], replacing any request still waiting. */
    fun request(url: String) {
        _pending.value = url
    }

    /**
     * Hands out (and clears) the pending URL when [currentScreen] is Home; returns `null` and keeps
     * the request latched otherwise.
     */
    fun release(currentScreen: Screen): String? {
        if (currentScreen != Screen.Home) return null
        return _pending.getAndUpdate { null }
    }
}

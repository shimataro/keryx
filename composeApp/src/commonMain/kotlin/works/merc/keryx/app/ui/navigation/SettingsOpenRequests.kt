package works.merc.keryx.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * One request to show the settings dialog on [tabId], or on its default entry when [tabId] is
 * `null` — the category list on Android, the first tab on desktop (see `KeryxTabDialog`). The
 * consumer bumps its own tab-request token per released request, so a repeated request for the same
 * tab still re-navigates an already-open dialog (see `SettingsDialog`'s `tabRequestToken`).
 */
data class SettingsOpenRequest(val tabId: String?)

/**
 * The single router every "open Settings" route goes through: the application menu's Settings… /
 * ⌘, item (and Android's feed-list settings row, which sends the same command), a notification's
 * `ShowSettingsTab` row, the desktop tray / Help menu update entry, and OPML import/export.
 *
 * The settings dialog is only reachable over [Screen.Home] — it must never open over first-run
 * Setup. A request made while Setup is showing is therefore **latched** and released only once
 * [Screen.Home] is current; the last request wins (a later one replaces an unreleased earlier one).
 * `App.kt` is the one consumer: a single `LaunchedEffect(pending, navigator.current)` calls
 * [release] and opens the dialog on the returned tab.
 *
 * App-scoped Koin singleton.
 */
class SettingsOpenRequests {
    private val _pending = MutableStateFlow<SettingsOpenRequest?>(null)

    /** The request waiting to be released, if any. */
    val pending: StateFlow<SettingsOpenRequest?> = _pending.asStateFlow()

    /**
     * Asks for the settings dialog on [tabId] (`null` = its default entry, see
     * [SettingsOpenRequest]). Held until Home is showing when it isn't yet (a notification action,
     * the update entry, or an `.opml` file opened during Setup), and replaces any request still
     * waiting.
     */
    fun request(tabId: String? = null) {
        _pending.value = SettingsOpenRequest(tabId)
    }

    /**
     * Asks for the settings dialog on [tabId] (`null` = its default entry) only if it is reachable right now ([currentScreen] is
     * Home), dropping the request otherwise. For a direct user gesture whose own menu item is
     * disabled off Home — the macOS native "Settings…" item is always enabled, and pressing it during
     * Setup must not open Settings later as a surprise.
     *
     * @return whether the request was accepted.
     */
    fun requestIfReachable(tabId: String?, currentScreen: Screen): Boolean {
        if (currentScreen != Screen.Home) return false
        request(tabId)
        return true
    }

    /**
     * Hands out (and clears) the pending request when [currentScreen] is Home; returns `null` and
     * keeps the request latched otherwise.
     */
    fun release(currentScreen: Screen): SettingsOpenRequest? {
        if (currentScreen != Screen.Home) return null
        return _pending.getAndUpdate { null }
    }
}

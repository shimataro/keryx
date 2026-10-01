package works.merc.keryx.app.tray

import works.merc.keryx.app.core.TRAY_ACTION_NOTIFICATION_RECENCY_MS
import works.merc.keryx.app.domain.UpdateState

/**
 * `main.kt`'s `lastNotificationSentAtMillis` while no new-article notification has been sent yet,
 * and the value a tray click that cannot be a notification click passes for it ([trayIconAction]'s
 * default). [shouldHideOnTrayAction] treats it as "no notification" rather than as one sent at the
 * epoch.
 */
internal const val NO_NOTIFICATION_MILLIS = 0L

/** What a tray click does to the main window. */
internal enum class TrayWindowAction {
    /** Hide the window to the tray (`windowVisible = false`). */
    Hide,

    /** Show it through `activationRequests`: un-minimize, bring to front and focus. */
    Activate,
}

/**
 * Whether the window counts as shown for every tray decision: visible **and** not minimized. A
 * minimized window is still `windowVisible` (it was never hidden to the tray), but there is nothing
 * of it on screen, so the tray must offer "Show" for it and showing it must un-minimize it.
 *
 * Drives the tray menu's Show/Hide label in every tray implementation (macOS, Linux SNI, Windows and
 * the Compose `Tray()` fallback — see [KeryxTray]) and `main.kt`'s one menu-item handler: hide when
 * shown, otherwise `activationRequests` (un-minimize, bring to front, focus). The menu item does not
 * look at focus: opening the tray menu itself takes focus away from the window on Windows.
 */
internal fun trayWindowShown(windowVisible: Boolean, windowMinimized: Boolean): Boolean =
    windowVisible && !windowMinimized

/**
 * Decides what a click on the tray **icon** should do: `true` hides the window, `false` brings it
 * to front/activates (through `activationRequests`, which also un-minimizes it).
 *
 * Hides only what looks like a deliberate icon click on a window the user is looking at — shown
 * ([trayWindowShown]) and focused, and no notification sent within [recencyWindowMs] — otherwise
 * activates, which also covers the window being minimized, backgrounded or hidden, and a
 * notification landing while it was already focused.
 *
 * The recency input exists for the Windows/Linux-fallback `onTrayAction` hook, shared between a
 * tray-icon click and a notification-balloon click with no platform way to tell them apart (see the
 * `onTrayAction` KDoc on [KeryxTray]). The macOS icon click and the Linux SNI `Activate` cannot be a
 * notification click, so `main.kt` calls this for them with no notification timestamp
 * ([NO_NOTIFICATION_MILLIS]). The
 * residual gap on the shared hook: a genuine icon click inside the recency window right after a
 * notification still activates instead of hiding (documented in `docs/testing.md`).
 */
internal fun shouldHideOnTrayAction(
    windowVisible: Boolean,
    windowMinimized: Boolean,
    windowFocused: Boolean,
    nowMillis: Long,
    lastNotificationSentAtMillis: Long,
    recencyWindowMs: Long = TRAY_ACTION_NOTIFICATION_RECENCY_MS,
): Boolean {
    val notifiedRecently = lastNotificationSentAtMillis != NO_NOTIFICATION_MILLIS &&
        nowMillis - lastNotificationSentAtMillis < recencyWindowMs
    return trayWindowShown(windowVisible, windowMinimized) && windowFocused && !notifiedRecently
}

/**
 * What the tray menu's Show/Hide item does: [TrayWindowAction.Hide] when the window is shown
 * ([trayWindowShown]), otherwise [TrayWindowAction.Activate]. Focus is deliberately not an input —
 * see [trayWindowShown].
 */
internal fun trayMenuToggleAction(windowVisible: Boolean, windowMinimized: Boolean): TrayWindowAction =
    if (trayWindowShown(windowVisible, windowMinimized)) TrayWindowAction.Hide else TrayWindowAction.Activate

/**
 * What a click on the tray icon does: [TrayWindowAction.Hide] exactly when [shouldHideOnTrayAction]
 * says so, otherwise [TrayWindowAction.Activate].
 *
 * @param lastNotificationSentAtMillis When the last new-article notification was sent. Left at
 *   [NO_NOTIFICATION_MILLIS] for an icon click that cannot be a notification click (the macOS icon,
 *   Linux SNI's `Activate`); only the Windows/Linux-fallback hook shared with a notification-balloon
 *   click passes the real timestamp.
 */
internal fun trayIconAction(
    windowVisible: Boolean,
    windowMinimized: Boolean,
    windowFocused: Boolean,
    nowMillis: Long,
    lastNotificationSentAtMillis: Long = NO_NOTIFICATION_MILLIS,
): TrayWindowAction =
    if (shouldHideOnTrayAction(windowVisible, windowMinimized, windowFocused, nowMillis, lastNotificationSentAtMillis)) {
        TrayWindowAction.Hide
    } else {
        TrayWindowAction.Activate
    }

/** What a click on the single update menu entry (tray and Help menu) runs on the Updates tab. */
internal enum class UpdateMenuAction {
    /** Run an update check. */
    Check,

    /** Run [works.merc.keryx.app.domain.UpdateRepository.performPrimaryAction] (download, per-stage retry, or install). */
    Primary,

    /** An action is already in flight; the entry is disabled, and a click from a stale menu does nothing. */
    None,
}

/**
 * Maps [state] to what the update menu entry does. Every action other than [UpdateMenuAction.None]
 * also opens the settings dialog on its Updates tab, whose inline results are the entry's only
 * feedback (see `main.kt`'s `onUpdateMenuItemClicked`).
 *
 * - `Idle`/`UpToDate`, and a non-installable `Available` (nothing to download here; the tab shows
 *   the release page link and the check refreshes what it says) → [UpdateMenuAction.Check].
 * - An installable `Available`, `Failed` (per-stage retry) and `Ready` → [UpdateMenuAction.Primary].
 * - `Checking`/`Downloading`/`Verifying`/`Installing` → [UpdateMenuAction.None].
 */
internal fun updateMenuAction(state: UpdateState): UpdateMenuAction = when (state) {
    UpdateState.Idle, UpdateState.UpToDate -> UpdateMenuAction.Check
    is UpdateState.Available -> if (state.update.installable) UpdateMenuAction.Primary else UpdateMenuAction.Check
    is UpdateState.Failed, is UpdateState.Ready -> UpdateMenuAction.Primary
    UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing ->
        UpdateMenuAction.None
}

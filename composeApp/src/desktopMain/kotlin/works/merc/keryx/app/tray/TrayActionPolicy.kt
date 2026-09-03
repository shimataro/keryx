package works.merc.keryx.app.tray

import works.merc.keryx.app.core.TRAY_ACTION_NOTIFICATION_RECENCY_MS
import works.merc.keryx.app.domain.UpdateState

/**
 * Decides what `onTrayAction` (KeryxTray's Windows/Linux-fallback click hook, shared between a
 * tray-icon click and a notification-balloon click with no platform way to tell them apart -
 * see the `onTrayAction` KDoc on [KeryxTray]) should do: `true` hides the window, `false` brings
 * it to front/activates.
 *
 * Hides only what looks like a deliberate icon click - the window already visible and focused,
 * and no notification sent within [recencyWindowMs] - otherwise activates, which also covers
 * the window being backgrounded/hidden and a notification landing while it was already focused.
 * The residual gap: a genuine icon click inside the recency window right after a notification
 * still activates instead of hiding (documented in `docs/testing.md`).
 */
internal fun shouldHideOnTrayAction(
    windowVisible: Boolean,
    windowFocused: Boolean,
    nowMillis: Long,
    lastNotificationSentAtMillis: Long,
    recencyWindowMs: Long = TRAY_ACTION_NOTIFICATION_RECENCY_MS,
): Boolean {
    val notifiedRecently = lastNotificationSentAtMillis != 0L &&
        nowMillis - lastNotificationSentAtMillis < recencyWindowMs
    return windowVisible && windowFocused && !notifiedRecently
}

/**
 * Whether a user-initiated update check that has just finished should pull the settings dialog's
 * Updates tab to the front — i.e. whether it found something the user can actually act on here.
 *
 * Only an *installable* [UpdateState.Available] qualifies. A check that came back up to date, or
 * failed, leaves the menu entry itself carrying the result, and a non-installable update has no
 * in-app action to offer on that tab (the menu entry opens the release page directly instead) —
 * see `main.kt`'s `onUpdateMenuItemClicked`.
 */
internal fun shouldOpenSettingsAfterUpdateCheck(state: UpdateState): Boolean =
    state is UpdateState.Available && state.update.installable

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

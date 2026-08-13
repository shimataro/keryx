package works.merc.keryx.app.tray

import works.merc.keryx.app.core.TRAY_ACTION_NOTIFICATION_RECENCY_MS

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

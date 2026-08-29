package works.merc.keryx.app.core

enum class AppNotificationLevel { INFO, WARNING, ERROR }

/**
 * The "next action" a notification offers when the user acts on it (session-only, resolved by the
 * UI). `action` is nullable — `null` means the notification row has no next action.
 *
 * Two families:
 * - Self-contained in the bell popover: [OpenUrl] (hands off to the external browser).
 * - Needs another screen's state changed, so the host (`HomeScreen` / `App`) resolves it through
 *   `NotificationCenterViewModel.pendingAction`: [ShowFeedDetail], [ShowSettingsTab],
 *   [ShowInfoDialog], [ResetCloudData].
 */
sealed interface AppNotificationAction {
    /** Destructive recovery for an unusable cloud DB — offered as a dedicated inline button. */
    data object ResetCloudData : AppNotificationAction

    /** Opens [url] in the external browser (e.g. a new release's page). */
    data class OpenUrl(val url: String) : AppNotificationAction

    /**
     * Selects the feed [feedId] in the feed list, as if the user had clicked it there. At a
     * single-pane width the feed list is a screen of its own, so this lands on that feed's article
     * list instead of navigating backwards — see `ui/home/HomePaneLayout.kt`'s `paneForFeedDetail`.
     */
    data class ShowFeedDetail(val feedId: String) : AppNotificationAction

    /** Opens the settings dialog on the tab [tabId] (see `SettingsDialog`'s tab ids). */
    data class ShowSettingsTab(val tabId: String) : AppNotificationAction

    /** Shows [detail] in an explanatory dialog, without navigating anywhere. */
    data class ShowInfoDialog(val detail: String) : AppNotificationAction
}

/**
 * An in-session notification shown in the notification center (bell icon).
 * Session-only — never persisted. Warnings/errors plus INFO notices worth looking back at (e.g. a
 * new app version). [action] is the notification's "next action": either an inline action button
 * ([AppNotificationAction.ResetCloudData]) or what clicking the row does.
 */
data class AppNotification(
    val id: String,
    val level: AppNotificationLevel,
    val message: String,
    val timestampMillis: Long,
    val action: AppNotificationAction? = null,
)

/**
 * The identity of an *alert* — two notifications with the same [AlertKey] say the same thing about
 * the same problem, whatever their [AppNotification.id] is.
 *
 * Ids are deliberately not part of it: a recurring failure (e.g. every background sync attempt)
 * produces a fresh id each time, so anything that must treat a recurrence as "the same alert
 * again" — `NotificationCenter.addCoalescing`'s deduplication and the Android foreground Snackbar's
 * already-surfaced bookkeeping — has to key on this instead. Both go through [alertKey] so they can
 * never drift apart (which would make a coalesced-away notification still re-announce itself).
 */
internal data class AlertKey(
    val level: AppNotificationLevel,
    val message: String,
    val action: AppNotificationAction?,
)

/** This notification's [AlertKey]. */
internal fun AppNotification.alertKey(): AlertKey = AlertKey(level, message, action)

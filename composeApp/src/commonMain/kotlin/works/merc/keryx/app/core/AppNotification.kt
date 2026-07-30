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

    /** Selects the feed [feedId] in the feed list, as if the user had clicked it there. */
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

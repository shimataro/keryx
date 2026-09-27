package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.presentation.home.NotificationAlerts

/**
 * The Compose notification-center UI's own ViewModel: the bell's row list plus the "next action
 * awaiting a host to resolve it" navigation state. The alert-surfacing logic (Android's foreground
 * Snackbar) is shared — see [NotificationAlerts].
 */
class NotificationCenterViewModel(
    private val center: NotificationCenter,
    private val alerts: NotificationAlerts,
) : ViewModel() {
    val items = center.items

    /** An action the user asked for, awaiting a host (HomeScreen / App) to resolve it (e.g. show a
     *  confirmation and run it). null when nothing is pending. */
    private val _pendingAction = MutableStateFlow<PendingNotificationAction?>(null)
    val pendingAction: StateFlow<PendingNotificationAction?> = _pendingAction.asStateFlow()

    /** See [NotificationAlerts.alertToSurface]. */
    val alertToSurface: StateFlow<AppNotification?> = alerts.alertToSurface

    /** See [NotificationAlerts.markAlertsSurfaced]. */
    fun markAlertsSurfaced() = alerts.markAlertsSurfaced()

    /** Requests [notification]'s own action (a no-op for a notification with none). */
    fun requestAction(notification: AppNotification) {
        _pendingAction.value = notification.action?.let { PendingNotificationAction(notification.id, it) }
    }

    /** Requests [action] on its own, with no notification behind it (e.g. the app menu's "Updates"). */
    fun requestAction(action: AppNotificationAction) {
        _pendingAction.value = PendingNotificationAction(notificationId = null, action = action)
    }

    fun clearPendingAction() {
        _pendingAction.value = null
    }

    fun dismiss(id: String) = center.dismiss(id)

    fun dismissAll() = center.dismissAll()
}

/**
 * A pending [action]. [notificationId] is the notification-center row it came from — so resolving
 * it can dismiss that row — or null when it was requested without one.
 */
data class PendingNotificationAction(
    val notificationId: String?,
    val action: AppNotificationAction,
)

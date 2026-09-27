package works.merc.keryx.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.AlertKey
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.alertKey
import works.merc.keryx.app.domain.NotificationCenter

/**
 * Which warning/error notification still needs announcing in a transient surface with no queue of
 * its own — Android's foreground Snackbar today; a future SwiftUI equivalent could use the same
 * signal. Split out of what was `ui/home/NotificationCenterViewModel.kt` (Compose-only); the rest of
 * that class — `pendingAction`/`requestAction`/`clearPendingAction` — stays per UI, since which
 * notification-center row is awaiting a host to resolve its action is a UI-navigation concern, not
 * shared behavior.
 */
class NotificationAlerts(
    private val center: NotificationCenter,
) : ViewModel() {

    /**
     * Alerts already announced in a transient surface this session. Keyed by [AlertKey] rather than
     * by id so a recurring failure — a fresh id every background sync attempt — is not re-announced
     * every time.
     *
     * Never pruned: like [NotificationCenter] itself this is session-only, and an entry has to
     * outlive the notification it came from (dismissing a notification must not make its next
     * recurrence announce itself again). The set is bounded by how many *distinct* alerts a session
     * produces, which is a handful.
     */
    private val surfacedAlerts = MutableStateFlow<Set<AlertKey>>(emptySet())

    /**
     * The newest warning/error not yet announced in a transient surface, or `null` when there is
     * nothing to announce.
     *
     * Derived from [NotificationCenter.items] rather than published as an "added" event stream: a
     * `SharedFlow` with no replay drops anything emitted before a collector attaches, and the alerts
     * this exists to surface can be raised while the collecting screen is still composing/appearing.
     * A `StateFlow` instead holds the alert until a collector is there to show it.
     *
     * `INFO` is excluded: a new-version notice or a finished OPML import is not an alert, and the
     * bell's badge is the right weight for them.
     */
    val alertToSurface: StateFlow<AppNotification?> =
        combine(center.items, surfacedAlerts) { notifications, surfaced ->
            // items is newest-first (NotificationCenter.add prepends), so this is the newest.
            notifications.firstOrNull { it.level != AppNotificationLevel.INFO && it.alertKey() !in surfaced }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Marks every alert currently in the notification center as announced.
     *
     * Deliberately all of them, not just the one that was shown: when several arrive at once only
     * the newest is announced (a transient surface shows one at a time, and the bell's badge
     * already carries the count), so marking one at a time would walk backwards through the queue
     * and end on the *oldest*.
     */
    fun markAlertsSurfaced() {
        val keys = center.items.value.filter { it.level != AppNotificationLevel.INFO }.map { it.alertKey() }
        surfacedAlerts.update { it + keys }
    }
}

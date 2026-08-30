package works.merc.keryx.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

class NotificationCenterViewModel(
    private val center: NotificationCenter,
) : ViewModel() {
    val items = center.items

    /** A notification whose inline action the user tapped, awaiting a host (HomeScreen) to resolve
     *  it (e.g. show a confirmation and run it). null when nothing is pending. */
    var pendingAction by mutableStateOf<AppNotification?>(null)
        private set

    /**
     * Alerts already announced in a transient surface this session (Android's foreground
     * Snackbar). Keyed by [AlertKey] rather than by id so a recurring failure — a fresh id every
     * background sync attempt — is not re-announced every time.
     *
     * Never pruned: like [NotificationCenter] itself this is session-only, and an entry has to
     * outlive the notification it came from (dismissing a notification must not make its next
     * recurrence announce itself again). The set is bounded by how many *distinct* alerts a
     * session produces, which is a handful.
     */
    private val surfacedAlerts = MutableStateFlow<Set<AlertKey>>(emptySet())

    /**
     * The newest warning/error not yet announced in a transient surface, or `null` when there is
     * nothing to announce.
     *
     * Derived from [NotificationCenter.items] rather than published as an "added" event stream: a
     * `SharedFlow` with no replay drops anything emitted before a collector attaches, and the
     * alerts this exists to surface are raised by `runAndroidStartupTasks` while `HomeScreen` is
     * still composing (the same race `NewArticleNotifier.trayEvents`' own KDoc documents). A
     * `StateFlow` instead holds the alert until a collector — one that is also gated on the window
     * actually being focused — is there to show it.
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
     * the newest is announced (Material 3 shows one Snackbar at a time, and the badge already
     * carries the count), so marking one at a time would walk backwards through the queue and end
     * on the *oldest*.
     */
    fun markAlertsSurfaced() {
        val keys = center.items.value.filter { it.level != AppNotificationLevel.INFO }.map { it.alertKey() }
        surfacedAlerts.update { it + keys }
    }

    fun requestAction(notification: AppNotification) {
        pendingAction = notification
    }

    fun clearPendingAction() {
        pendingAction = null
    }

    fun dismiss(id: String) = center.dismiss(id)

    fun dismissAll() = center.dismissAll()
}

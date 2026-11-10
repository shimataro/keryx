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
 * its own — Android's foreground Snackbar and the iOS alert toast (`AlertToastAnnouncement.swift`).
 * Which notification-center row is awaiting a host to resolve its action
 * (`pendingAction`/`requestAction`/`clearPendingAction`) stays per UI, since that is a UI-navigation
 * concern, not shared behavior.
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
     * notification center's footer row is the right weight for them.
     */
    val alertToSurface: StateFlow<AppNotification?> =
        combine(center.items, surfacedAlerts) { notifications, surfaced ->
            // items is newest-first (NotificationCenter.add prepends), so this is the newest.
            notifications.firstOrNull { it.level != AppNotificationLevel.INFO && it.alertKey() !in surfaced }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Marks [shown] — the alert a transient surface has just finished showing — as announced, along
     * with every older warning/error still in the notification center.
     *
     * The older ones too: when several arrive at once only the newest is announced (a transient
     * surface shows one at a time, and the notification center's row already carries the count), so
     * marking only the shown one would walk backwards through the queue and end on the *oldest*.
     * But not the newer ones: an alert raised while [shown] was on screen has not been seen, so it
     * stays pending and [alertToSurface] offers it next. [shown] is found by id, or by its
     * [AlertKey] when a recurrence has since replaced it under a fresh id; when it has been dismissed
     * in the meantime, only [shown] itself is marked.
     */
    fun markAlertsSurfaced(shown: AppNotification) {
        val shownKey = shown.alertKey()
        val items = center.items.value
        // items is newest-first, so everything from shown's position on is shown or older.
        val index = items.indexOfFirst { it.id == shown.id || it.alertKey() == shownKey }
        val older = if (index < 0) emptyList() else items.drop(index)
        val keys = older.filter { it.level != AppNotificationLevel.INFO }.map { it.alertKey() } + shownKey
        surfacedAlerts.update { it + keys }
    }
}

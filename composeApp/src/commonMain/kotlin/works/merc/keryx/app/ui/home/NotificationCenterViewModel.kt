package works.merc.keryx.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.domain.NotificationCenter

class NotificationCenterViewModel(
    private val center: NotificationCenter,
) : ViewModel() {
    val items = center.items

    /** A notification whose inline action the user tapped, awaiting a host (HomeScreen) to resolve
     *  it (e.g. show a confirmation and run it). null when nothing is pending. */
    var pendingAction by mutableStateOf<AppNotification?>(null)
        private set

    fun requestAction(notification: AppNotification) {
        pendingAction = notification
    }

    fun clearPendingAction() {
        pendingAction = null
    }

    fun dismiss(id: String) = center.dismiss(id)

    fun dismissAll() = center.dismissAll()
}

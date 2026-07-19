package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import works.merc.keryx.app.domain.NotificationCenter

class NotificationCenterViewModel(
    private val center: NotificationCenter,
) : ViewModel() {
    val items = center.items

    fun dismiss(id: String) = center.dismiss(id)

    fun dismissAll() = center.dismissAll()
}

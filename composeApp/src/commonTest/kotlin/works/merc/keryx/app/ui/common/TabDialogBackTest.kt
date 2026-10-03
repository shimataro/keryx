package works.merc.keryx.app.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [goBackInTabDialog] is the one back step every back route of Android's settings dialog (the
 * top-bar arrow and the system back) goes through: detail → category list, list → dismissed.
 */
class TabDialogBackTest {

    private val selections = mutableListOf<String?>()
    private var dismissals = 0

    private fun back(selectedTabId: String?) =
        goBackInTabDialog(selectedTabId, onSelectTab = { selections += it }, onDismissRequest = { dismissals++ })

    @Test
    fun backFromADetailReturnsToTheListWithoutDismissing() {
        back("cloud_sync")

        assertEquals(listOf<String?>(null), selections)
        assertEquals(0, dismissals)
    }

    @Test
    fun backFromTheListDismissesTheDialog() {
        back(null)

        assertEquals(emptyList(), selections)
        assertEquals(1, dismissals)
    }

    @Test
    fun detailThenListTakesTwoBacksToDismiss() {
        var selected: String? = "data"
        repeat(2) {
            goBackInTabDialog(selected, onSelectTab = { selected = it }, onDismissRequest = { dismissals++ })
        }

        assertEquals(null, selected)
        assertEquals(1, dismissals)
    }
}

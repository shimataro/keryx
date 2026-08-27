package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [listRowMinHeight] is the mobile density floor applied to every interactive list row — see its
 * own KDoc for why it is independent of [LIST_ROW_VERTICAL_MARGIN].
 */
class ListRowMinHeightTest {

    @Test
    fun isZeroWhenNotTouchPrimary() {
        assertEquals(0.dp, listRowMinHeight(isTouchPrimary = false))
    }

    @Test
    fun matchesM3NavigationDrawerItemMinimumWhenTouchPrimary() {
        assertEquals(56.dp, listRowMinHeight(isTouchPrimary = true))
    }
}

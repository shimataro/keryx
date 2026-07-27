package works.merc.keryx.app.platform

import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers what [SwingPopupHandle] actually builds. A menu that is constructed but left empty still
 * "shows" without error — it just paints nothing — so an assertion on the built widgets is the
 * only thing that catches that class of bug before it reaches a Linux desktop. Showing the menu
 * is not covered; that needs a real display.
 */
class NativeMenuTest {

    private fun handleOf(vararg entries: NativeMenuEntry): SwingPopupHandle {
        val items = entries.toList()
        return SwingPopupHandle(items) { items }
    }

    @Test
    fun buildsOneWidgetPerTopLevelEntry() {
        val handle = handleOf(
            NativeMenuItem("Refresh") {},
            NativeMenuItem("Rename") {},
            NativeMenuItem("Unsubscribe") {},
        )

        assertEquals(3, handle.popupMenu.componentCount)
    }

    @Test
    fun buildsSubMenuWithItsChildren() {
        val handle = handleOf(
            NativeMenuItem("Refresh") {},
            NativeSubMenu(
                "Move to folder",
                listOf(
                    NativeMenuItem("No folder") {},
                    NativeMenuItem("News") {},
                ),
            ),
        )

        assertEquals(2, handle.popupMenu.componentCount)
        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(1))
        assertEquals(2, submenu.itemCount)
    }

    @Test
    fun syncAppliesLabelsToTopLevelItemsAndSubMenuChildren() {
        val entries = listOf(
            NativeMenuItem("Refresh") {},
            NativeSubMenu("Move to folder", listOf(NativeMenuItem("News") {})),
        )
        val handle = SwingPopupHandle(entries) { entries }

        handle.sync(entries)

        assertEquals("Refresh", handle.popupMenu.getComponent(0).let { (it as javax.swing.JMenuItem).text })
        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(1))
        assertEquals("Move to folder", submenu.text)
        assertEquals("News", submenu.getItem(0).text)
    }

    @Test
    fun checkEntriesBecomeCheckboxItemsAndFollowTheCheckedState() {
        val checked = NativeCheckMenuItem("Tech", checked = true) {}
        val unchecked = NativeCheckMenuItem("Design", checked = false) {}
        val entries = listOf<NativeMenuEntry>(checked, unchecked)
        val handle = SwingPopupHandle(entries) { entries }

        handle.sync(entries)

        val first = assertIs<JCheckBoxMenuItem>(handle.popupMenu.getComponent(0))
        val second = assertIs<JCheckBoxMenuItem>(handle.popupMenu.getComponent(1))
        assertTrue(first.isSelected)
        assertFalse(second.isSelected)
    }

    @Test
    fun clickingAnItemInvokesTheLatestEntryForThatSlot() {
        var clicked: String? = null
        // The widgets are built once from the first list; later lists only relabel them, so a
        // click has to resolve against whatever the call site currently exposes, not the
        // snapshot the widgets were created from.
        var entries = listOf<NativeMenuEntry>(NativeMenuItem("stale") { clicked = "stale" })
        val handle = SwingPopupHandle(entries) { entries }
        entries = listOf(NativeMenuItem("fresh") { clicked = "fresh" })

        (handle.popupMenu.getComponent(0) as javax.swing.JMenuItem).doClick()

        assertEquals("fresh", clicked)
    }
}

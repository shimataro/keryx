package works.merc.keryx.app.platform

import java.awt.Color
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
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

    /**
     * The popup belongs to no window's component tree, so `FlatLaf.updateUI()` cannot reach it —
     * re-applying the UI at show time is the only thing keeping it on the current Look & Feel.
     */
    @Test
    fun reapplyingTheUiPicksUpALookAndFeelChange() {
        val key = "PopupMenu.background"
        val original = UIManager.get(key)
        try {
            UIManager.put(key, ColorUIResource(Color(1, 2, 3)))
            val handle = handleOf(NativeMenuItem("Refresh") {})
            UIManager.put(key, ColorUIResource(Color(4, 5, 6)))

            SwingUtilities.updateComponentTreeUI(handle.popupMenu)

            assertEquals(Color(4, 5, 6).rgb, handle.popupMenu.background.rgb)
        } finally {
            UIManager.put(key, original)
        }
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

    @Test
    fun clickingASubMenuChildInvokesTheLatestEntryForThatSlot() {
        var clicked: String? = null
        var entries = listOf<NativeMenuEntry>(
            NativeSubMenu("Move to folder", listOf(NativeMenuItem("stale") { clicked = "stale" })),
        )
        val handle = SwingPopupHandle(entries) { entries }
        entries = listOf(NativeSubMenu("Move to folder", listOf(NativeMenuItem("fresh") { clicked = "fresh" })))

        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(0))
        submenu.getItem(0).doClick()

        assertEquals("fresh", clicked)
    }

    @Test
    fun emptyItemListBuildsAnEmptyMenu() {
        // nativeContextMenu treats an empty items() list as "no menu, onOpen only" — showing it
        // must not throw, and it must contribute no widgets.
        val handle = handleOf()

        assertEquals(0, handle.popupMenu.componentCount)
    }

    @Test
    fun rootPopupIsForcedHeavyweightSoItPaintsAboveTheWebView() {
        val handle = handleOf(NativeMenuItem("Refresh") {})

        assertFalse(handle.popupMenu.isLightWeightPopupEnabled)
    }

    @Test
    fun subMenuPopupIsAlsoForcedHeavyweight() {
        val handle = handleOf(NativeSubMenu("Move to folder", listOf(NativeMenuItem("News") {})))

        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(0))
        assertFalse(submenu.popupMenu.isLightWeightPopupEnabled)
    }

    @Test
    fun checkEntriesInsideASubMenuBecomeCheckboxItemsAndFollowTheCheckedState() {
        val entries = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Tags",
                listOf(
                    NativeCheckMenuItem("Tech", checked = true) {},
                    NativeCheckMenuItem("Design", checked = false) {},
                ),
            ),
        )
        val handle = SwingPopupHandle(entries) { entries }

        handle.sync(entries)

        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(0))
        val first = assertIs<JCheckBoxMenuItem>(submenu.getItem(0))
        val second = assertIs<JCheckBoxMenuItem>(submenu.getItem(1))
        assertTrue(first.isSelected)
        assertFalse(second.isSelected)
    }

    @Test
    fun syncTogglesACheckedSubMenuChildIndependentlyOfItsSiblings() {
        var checked = false
        val entries = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Move to folder",
                listOf(
                    NativeCheckMenuItem("No folder", checked = true) {},
                    NativeCheckMenuItem("News", checked = false) { checked = true },
                ),
            ),
        )
        val handle = SwingPopupHandle(entries) { entries }
        val submenu = assertIs<JMenu>(handle.popupMenu.getComponent(0))
        val noFolder = assertIs<JCheckBoxMenuItem>(submenu.getItem(0))
        val news = assertIs<JCheckBoxMenuItem>(submenu.getItem(1))

        // Simulate the underlying data changing (the feed moved into "News") and the resulting
        // resync — only the affected child's checked state should flip.
        val updated = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Move to folder",
                listOf(
                    NativeCheckMenuItem("No folder", checked = false) {},
                    NativeCheckMenuItem("News", checked = true) { checked = true },
                ),
            ),
        )
        handle.sync(updated)

        assertFalse(noFolder.isSelected)
        assertTrue(news.isSelected)
        assertFalse(checked, "sync should only relabel/recheck widgets, not invoke onClick")
    }
}

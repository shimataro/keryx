package works.merc.keryx.app.platform

import androidx.compose.ui.input.key.Key
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun swingSetsAZeroModifierAcceleratorForABareShortcut() {
        // Safe here (unlike a JMenuBar-hosted item) since this JPopupMenu is never attached to a
        // JMenuBar/JRootPane — see the comment on swingLeaf's shortcut handling.
        val handle = handleOf(NativeMenuItem("Rename", NativeMenuShortcut(Key.F2)) {})

        val item = handle.popupMenu.getComponent(0) as javax.swing.JMenuItem
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), item.accelerator)
    }

    @Test
    fun swingSetsACtrlShiftAcceleratorForAModifierShortcut() {
        val handle = handleOf(NativeMenuItem("Refresh", NativeMenuShortcut(Key.R, ctrl = true, shift = true)) {})

        val item = handle.popupMenu.getComponent(0) as javax.swing.JMenuItem
        assertEquals(
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            item.accelerator,
        )
    }

    @Test
    fun swingLeavesAcceleratorNullWithNoShortcut() {
        val handle = handleOf(NativeMenuItem("Refresh") {})

        val item = handle.popupMenu.getComponent(0) as javax.swing.JMenuItem
        assertNull(item.accelerator)
    }

    @Test
    fun swingDisablesAnItemBuiltWithEnabledFalse() {
        val handle = handleOf(NativeMenuItem("Open site", enabled = false) {})

        val item = handle.popupMenu.getComponent(0) as javax.swing.JMenuItem
        assertFalse(item.isEnabled)
    }

    @Test
    fun swingSyncTogglesEnabledWithoutRebuildingTheWidget() {
        val entries = listOf<NativeMenuEntry>(NativeMenuItem("Open site", enabled = false) {})
        val handle = SwingPopupHandle(entries) { entries }
        val item = handle.popupMenu.getComponent(0) as javax.swing.JMenuItem
        assertFalse(item.isEnabled)

        handle.sync(listOf(NativeMenuItem("Open site", enabled = true) {}))

        assertTrue(item.isEnabled)
    }

    @Test
    fun swingSeparatorEntryBecomesAJPopupMenuSeparator() {
        val handle = handleOf(NativeMenuItem("Refresh") {}, NativeMenuSeparator, NativeMenuItem("Unsubscribe") {})

        assertEquals(3, handle.popupMenu.componentCount)
        assertIs<JPopupMenu.Separator>(handle.popupMenu.getComponent(1))
    }

    @Test
    fun swingSyncLeavesTheSeparatorAloneAndStillUpdatesOtherItems() {
        val entries = listOf<NativeMenuEntry>(NativeMenuItem("stale") {}, NativeMenuSeparator)
        val handle = SwingPopupHandle(entries) { entries }

        handle.sync(listOf(NativeMenuItem("fresh") {}, NativeMenuSeparator))

        assertEquals("fresh", (handle.popupMenu.getComponent(0) as javax.swing.JMenuItem).text)
        assertIs<JPopupMenu.Separator>(handle.popupMenu.getComponent(1))
    }

    private fun awtHandleOf(vararg entries: NativeMenuEntry): AwtPopupHandle {
        val items = entries.toList()
        return AwtPopupHandle(items) { items }
    }

    @Test
    fun awtSetsAMenuShortcutForAModifierShortcut() {
        val handle = awtHandleOf(NativeMenuItem("Refresh", NativeMenuShortcut(Key.R, ctrl = true, shift = true)) {})

        val shortcut = handle.popupMenu.getItem(0).shortcut
        assertEquals(KeyEvent.VK_R, shortcut?.key)
        assertTrue(shortcut?.usesShiftModifier() == true)
    }

    @Test
    fun awtLeavesShortcutNullForABareShortcut() {
        // java.awt.MenuShortcut structurally cannot represent a key without the platform's
        // primary modifier, so the rename/delete family (ctrl = false) gets no native hint here —
        // see NativeMenuShortcut's doc comment.
        val handle = awtHandleOf(NativeMenuItem("Rename", NativeMenuShortcut(Key.F2)) {})

        assertNull(handle.popupMenu.getItem(0).shortcut)
    }

    @Test
    fun awtLeavesShortcutNullWithNoShortcut() {
        val handle = awtHandleOf(NativeMenuItem("Refresh") {})

        assertNull(handle.popupMenu.getItem(0).shortcut)
    }

    @Test
    fun awtDisablesAnItemBuiltWithEnabledFalse() {
        val handle = awtHandleOf(NativeMenuItem("Open site", enabled = false) {})

        assertFalse(handle.popupMenu.getItem(0).isEnabled)
    }

    @Test
    fun awtDisablesASubMenuChildBuiltWithEnabledFalse() {
        val handle = awtHandleOf(
            NativeSubMenu("Move to folder", listOf(NativeMenuItem("News", enabled = false) {})),
        )

        val submenu = handle.popupMenu.getItem(0) as java.awt.Menu
        assertFalse(submenu.getItem(0).isEnabled)
    }

    @Test
    fun awtSyncTogglesSubMenuChildEnabledWithoutRebuildingTheWidget() {
        val entries = listOf<NativeMenuEntry>(
            NativeSubMenu("Move to folder", listOf(NativeMenuItem("News", enabled = false) {})),
        )
        val handle = AwtPopupHandle(entries) { entries }
        val submenu = handle.popupMenu.getItem(0) as java.awt.Menu
        assertFalse(submenu.getItem(0).isEnabled)

        handle.sync(listOf(NativeSubMenu("Move to folder", listOf(NativeMenuItem("News", enabled = true) {}))))

        assertTrue(submenu.getItem(0).isEnabled)
    }

    @Test
    fun awtSyncTogglesEnabledWithoutRebuildingTheWidget() {
        val entries = listOf<NativeMenuEntry>(NativeMenuItem("Open site", enabled = false) {})
        val handle = AwtPopupHandle(entries) { entries }
        assertFalse(handle.popupMenu.getItem(0).isEnabled)

        handle.sync(listOf(NativeMenuItem("Open site", enabled = true) {}))

        assertTrue(handle.popupMenu.getItem(0).isEnabled)
    }

    @Test
    fun awtSeparatorEntryBecomesAMenuItemWithADashLabel() {
        // "-" is the exact idiom java.awt.Menu.addSeparator() itself uses; the native peer renders
        // a MenuItem with that label as a separator rather than a real item.
        val handle = awtHandleOf(NativeMenuItem("Refresh") {}, NativeMenuSeparator, NativeMenuItem("Unsubscribe") {})

        assertEquals(3, handle.popupMenu.itemCount)
        assertEquals("-", handle.popupMenu.getItem(1).label)
    }

    @Test
    fun awtSyncLeavesTheSeparatorLabelAloneAndStillUpdatesOtherItems() {
        val entries = listOf<NativeMenuEntry>(NativeMenuItem("stale") {}, NativeMenuSeparator)
        val handle = AwtPopupHandle(entries) { entries }

        handle.sync(listOf(NativeMenuItem("fresh") {}, NativeMenuSeparator))

        assertEquals("fresh", handle.popupMenu.getItem(0).label)
        assertEquals("-", handle.popupMenu.getItem(1).label)
    }

    @Test
    fun awtCheckEntriesBecomeCheckboxMenuItemsAndFollowTheCheckedState() {
        val checked = NativeCheckMenuItem("Tech", checked = true) {}
        val unchecked = NativeCheckMenuItem("Design", checked = false) {}
        val entries = listOf<NativeMenuEntry>(checked, unchecked)
        val handle = AwtPopupHandle(entries) { entries }

        handle.sync(entries)

        val first = assertIs<java.awt.CheckboxMenuItem>(handle.popupMenu.getItem(0))
        val second = assertIs<java.awt.CheckboxMenuItem>(handle.popupMenu.getItem(1))
        assertTrue(first.state)
        assertFalse(second.state)
    }

    @Test
    fun awtCheckEntriesInsideASubMenuBecomeCheckboxMenuItemsAndFollowTheCheckedState() {
        val entries = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Tags",
                listOf(
                    NativeCheckMenuItem("Tech", checked = true) {},
                    NativeCheckMenuItem("Design", checked = false) {},
                ),
            ),
        )
        val handle = AwtPopupHandle(entries) { entries }

        handle.sync(entries)

        val submenu = assertIs<java.awt.Menu>(handle.popupMenu.getItem(0))
        val first = assertIs<java.awt.CheckboxMenuItem>(submenu.getItem(0))
        val second = assertIs<java.awt.CheckboxMenuItem>(submenu.getItem(1))
        assertTrue(first.state)
        assertFalse(second.state)
    }

    /**
     * Regression guard for the old fake-checkmark implementation, which prepended "✓ " to a plain
     * MenuItem's label instead of using a real CheckboxMenuItem — that left unchecked siblings
     * flush left while checked ones sat two characters in.
     */
    @Test
    fun awtSyncDoesNotEncodeTheCheckedStateIntoTheLabel() {
        val checked = NativeCheckMenuItem("Tech", checked = true) {}
        val entries = listOf<NativeMenuEntry>(checked)
        val handle = AwtPopupHandle(entries) { entries }

        handle.sync(entries)

        assertEquals("Tech", handle.popupMenu.getItem(0).label)
    }

    /**
     * Pins which backend each platform gets. macOS is the only one left on `java.awt.PopupMenu`:
     * there it is a real `NSMenu`, whereas the JDK's Windows menu peer ignores display scaling
     * (mispositioned menu, overlapping labels — see [SwingPopupHandle]) and the X11 one ignores
     * the Look & Feel. `macOs` is a parameter precisely so this holds on any CI host.
     */
    @Test
    fun macOsGetsTheAwtBackendAndEveryOtherPlatformGetsSwing() {
        val entries = listOf<NativeMenuEntry>(NativeMenuItem("Refresh") {})

        assertIs<AwtPopupHandle>(defaultPopupHandle(entries, { entries }, macOs = true))
        assertIs<SwingPopupHandle>(defaultPopupHandle(entries, { entries }, macOs = false))
    }
}

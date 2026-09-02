package works.merc.keryx.app.tray

import java.awt.MenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers what [MacTrayMenu] actually builds — mirrors `WindowsTrayMenuTest`. Like that test,
 * showing the menu is not covered — that needs a real display and a tray — so an assertion on the
 * built widgets is what catches an empty or mislabelled menu before it reaches a macOS desktop.
 */
class MacTrayMenuTest {

    private fun menuOf(onToggle: () -> Unit = {}, onQuit: () -> Unit = {}) =
        MacTrayMenu(onToggle = onToggle, onQuit = onQuit)

    @Test
    fun buildsTheToggleAndQuitEntriesAsAwtMenuItems() {
        val menu = menuOf()

        assertEquals(2, menu.popupMenu.itemCount)
        assertIs<MenuItem>(menu.popupMenu.getItem(0))
        assertIs<MenuItem>(menu.popupMenu.getItem(1))
    }

    @Test
    fun setLabelsPushesBothLabelsOntoTheWidgets() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")

        assertEquals("Hide", menu.popupMenu.getItem(0).label)
        assertEquals("Quit", menu.popupMenu.getItem(1).label)
    }

    @Test
    fun setLabelsReplacesTheToggleLabelWhenTheWindowVisibilityFlips() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")
        menu.setLabels(toggle = "Show", quit = "Quit")

        assertEquals("Show", menu.popupMenu.getItem(0).label)
    }

    @Test
    fun entriesInvokeTheirOwnCallback() {
        val fired = mutableListOf<String>()
        val menu = menuOf(onToggle = { fired += "toggle" }, onQuit = { fired += "quit" })

        menu.popupMenu.getItem(0).actionListeners.forEach { it.actionPerformed(null) }
        menu.popupMenu.getItem(1).actionListeners.forEach { it.actionPerformed(null) }

        assertEquals(listOf("toggle", "quit"), fired)
    }

    // --- update entry ---

    @Test
    fun noUpdateEntryKeepsTheOriginalTwoItemMenuShape() {
        val menu = menuOf()

        assertEquals(2, menu.popupMenu.itemCount)
    }

    @Test
    fun settingAnUpdateEntryInsertsItAheadOfToggleAndQuit() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))

        assertEquals(4, menu.popupMenu.itemCount)
        assertEquals("Download update 2.0.0", menu.popupMenu.getItem(0).label)
        assertEquals("-", menu.popupMenu.getItem(1).label)
        // Toggle/quit keep their original positions relative to each other, just shifted by two.
        assertIs<MenuItem>(menu.popupMenu.getItem(2))
        assertIs<MenuItem>(menu.popupMenu.getItem(3))
    }

    @Test
    fun clearingTheUpdateEntryRemovesItAndRestoresTheOriginalShape() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        menu.setUpdateEntry(null)

        assertEquals(2, menu.popupMenu.itemCount)
        assertIs<MenuItem>(menu.popupMenu.getItem(0))
    }

    @Test
    fun theUpdateEntryReflectsItsEnabledState() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = false))

        assertFalse(menu.popupMenu.getItem(0).isEnabled)
    }

    @Test
    fun clickingTheUpdateEntryInvokesItsOwnCallback() {
        var invoked = false
        val menu = MacTrayMenu(onToggle = {}, onQuit = {}, onUpdateAction = { invoked = true })

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        menu.popupMenu.getItem(0).actionListeners.forEach { it.actionPerformed(null) }

        assertTrue(invoked)
    }
}

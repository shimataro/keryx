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
    fun buildsTheUpdateSeparatorToggleAndQuitEntriesAsAwtMenuItems() {
        val menu = menuOf()

        assertEquals(4, menu.popupMenu.itemCount)
        assertIs<MenuItem>(menu.popupMenu.getItem(UPDATE_INDEX))
        assertEquals("-", menu.popupMenu.getItem(SEPARATOR_INDEX).label)
        assertIs<MenuItem>(menu.popupMenu.getItem(TOGGLE_INDEX))
        assertIs<MenuItem>(menu.popupMenu.getItem(QUIT_INDEX))
    }

    @Test
    fun setLabelsPushesBothLabelsOntoTheWidgets() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")

        assertEquals("Hide", menu.popupMenu.getItem(TOGGLE_INDEX).label)
        assertEquals("Quit", menu.popupMenu.getItem(QUIT_INDEX).label)
    }

    @Test
    fun setLabelsReplacesTheToggleLabelWhenTheWindowVisibilityFlips() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")
        menu.setLabels(toggle = "Show", quit = "Quit")

        assertEquals("Show", menu.popupMenu.getItem(TOGGLE_INDEX).label)
    }

    @Test
    fun entriesInvokeTheirOwnCallback() {
        val fired = mutableListOf<String>()
        val menu = menuOf(onToggle = { fired += "toggle" }, onQuit = { fired += "quit" })

        menu.popupMenu.getItem(TOGGLE_INDEX).actionListeners.forEach { it.actionPerformed(null) }
        menu.popupMenu.getItem(QUIT_INDEX).actionListeners.forEach { it.actionPerformed(null) }

        assertEquals(listOf("toggle", "quit"), fired)
    }

    // --- update entry ---

    /**
     * The entry is a permanent part of the menu, whatever the update state — a disabled one is
     * grayed out, never removed (see [TrayMenuState.update]).
     */
    @Test
    fun theUpdateEntryStaysInPlaceAcrossEnabledAndDisabledStates() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        assertEquals(4, menu.popupMenu.itemCount)
        assertEquals("Download update 2.0.0", menu.popupMenu.getItem(UPDATE_INDEX).label)
        assertTrue(menu.popupMenu.getItem(UPDATE_INDEX).isEnabled)

        menu.setUpdateEntry(TrayUpdateEntry("Downloading… 60%", enabled = false))
        assertEquals(4, menu.popupMenu.itemCount)
        assertEquals("Downloading… 60%", menu.popupMenu.getItem(UPDATE_INDEX).label)
        assertFalse(menu.popupMenu.getItem(UPDATE_INDEX).isEnabled)
    }

    @Test
    fun clickingTheUpdateEntryInvokesItsOwnCallback() {
        var invoked = false
        val menu = MacTrayMenu(onToggle = {}, onQuit = {}, onUpdateAction = { invoked = true })

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        menu.popupMenu.getItem(UPDATE_INDEX).actionListeners.forEach { it.actionPerformed(null) }

        assertTrue(invoked)
    }

    private companion object {
        const val UPDATE_INDEX = 0
        const val SEPARATOR_INDEX = 1
        const val TOGGLE_INDEX = 2
        const val QUIT_INDEX = 3
    }
}

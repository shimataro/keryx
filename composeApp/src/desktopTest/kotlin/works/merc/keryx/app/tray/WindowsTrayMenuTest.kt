package works.merc.keryx.app.tray

import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers what [WindowsTrayMenu] actually builds, plus [trayMenuAnchor]'s choice of coordinate
 * source. Like `NativeMenuTest`, showing the menu is not covered — that needs a real display and a
 * tray — so an assertion on the built widgets is what catches an empty or mislabelled menu before
 * it reaches a Windows desktop.
 *
 * This is Swing rather than `java.awt.PopupMenu` on purpose (the JDK's Windows AWT menu peer
 * overlaps its own labels above 100% display scaling), so `assertIs<JMenuItem>` below is also the
 * guard against the entries quietly reverting to AWT widgets.
 */
class WindowsTrayMenuTest {

    private fun menuOf(onToggle: () -> Unit = {}, onQuit: () -> Unit = {}) =
        WindowsTrayMenu(onToggle = onToggle, onQuit = onQuit)

    @Test
    fun buildsTheToggleAndQuitEntriesAsSwingItems() {
        val menu = menuOf()

        assertEquals(2, menu.popupMenu.componentCount)
        assertIs<JMenuItem>(menu.popupMenu.getComponent(0))
        assertIs<JMenuItem>(menu.popupMenu.getComponent(1))
    }

    @Test
    fun setLabelsPushesBothLabelsOntoTheWidgets() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")

        assertEquals("Hide", (menu.popupMenu.getComponent(0) as JMenuItem).text)
        assertEquals("Quit", (menu.popupMenu.getComponent(1) as JMenuItem).text)
    }

    @Test
    fun setLabelsReplacesTheToggleLabelWhenTheWindowVisibilityFlips() {
        val menu = menuOf()

        menu.setLabels(toggle = "Hide", quit = "Quit")
        menu.setLabels(toggle = "Show", quit = "Quit")

        assertEquals("Show", (menu.popupMenu.getComponent(0) as JMenuItem).text)
    }

    @Test
    fun entriesInvokeTheirOwnCallback() {
        val fired = mutableListOf<String>()
        val menu = menuOf(onToggle = { fired += "toggle" }, onQuit = { fired += "quit" })

        (menu.popupMenu.getComponent(0) as JMenuItem).doClick()
        (menu.popupMenu.getComponent(1) as JMenuItem).doClick()

        assertEquals(listOf("toggle", "quit"), fired)
    }

    /**
     * The invoker is a 1x1 frame, so a lightweight popup would have nothing to paint into.
     */
    @Test
    fun forcesAHeavyweightPopup() {
        assertFalse(menuOf().popupMenu.isLightWeightPopupEnabled)
    }

    /**
     * The regression guard for the tray menu opening clipped against the screen edge: a
     * `TrayIcon` MouseEvent's on-screen coordinates are device pixels on Windows, whereas
     * `Window.setLocation` takes user space, so "simplifying" this back to the event's own numbers
     * parks the invoker `scale` times too far out. See [trayMenuAnchor].
     */
    @Test
    fun anchorsOnMouseInfoRatherThanTheEventsOwnCoordinates() {
        val anchor = trayMenuAnchor(pointerLocation = Point(960, 540), eventX = 1920, eventY = 1080)

        assertEquals(Point(960, 540), anchor)
    }

    @Test
    fun anchorFallsBackToTheEventCoordinatesWithoutMouseInfo() {
        val anchor = trayMenuAnchor(pointerLocation = null, eventX = 1920, eventY = 1080)

        assertEquals(Point(1920, 1080), anchor)
    }

    // --- update entry ---

    @Test
    fun noUpdateEntryKeepsTheOriginalTwoItemMenuShape() {
        val menu = menuOf()

        assertEquals(2, menu.popupMenu.componentCount)
    }

    @Test
    fun settingAnUpdateEntryInsertsItAheadOfToggleAndQuit() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))

        assertEquals(4, menu.popupMenu.componentCount)
        assertEquals("Download update 2.0.0", (menu.popupMenu.getComponent(0) as JMenuItem).text)
        assertIs<JPopupMenu.Separator>(menu.popupMenu.getComponent(1))
        // Toggle/quit keep their original positions relative to each other, just shifted by two.
        assertIs<JMenuItem>(menu.popupMenu.getComponent(2))
        assertIs<JMenuItem>(menu.popupMenu.getComponent(3))
    }

    @Test
    fun clearingTheUpdateEntryRemovesItAndRestoresTheOriginalShape() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        menu.setUpdateEntry(null)

        assertEquals(2, menu.popupMenu.componentCount)
        assertIs<JMenuItem>(menu.popupMenu.getComponent(0))
    }

    @Test
    fun theUpdateEntryReflectsItsEnabledState() {
        val menu = menuOf()

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = false))

        assertFalse((menu.popupMenu.getComponent(0) as JMenuItem).isEnabled)
    }

    @Test
    fun clickingTheUpdateEntryInvokesItsOwnCallback() {
        var invoked = false
        val menu = WindowsTrayMenu(onToggle = {}, onQuit = {}, onUpdateAction = { invoked = true })

        menu.setUpdateEntry(TrayUpdateEntry("Download update 2.0.0", enabled = true))
        (menu.popupMenu.getComponent(0) as JMenuItem).doClick()

        assertTrue(invoked)
    }
}

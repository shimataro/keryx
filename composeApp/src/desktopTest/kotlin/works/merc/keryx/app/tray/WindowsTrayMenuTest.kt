package works.merc.keryx.app.tray

import java.awt.Point
import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

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

        menu.setLabels(toggle = "隠す", quit = "終了")

        assertEquals("隠す", (menu.popupMenu.getComponent(0) as JMenuItem).text)
        assertEquals("終了", (menu.popupMenu.getComponent(1) as JMenuItem).text)
    }

    @Test
    fun setLabelsReplacesTheToggleLabelWhenTheWindowVisibilityFlips() {
        val menu = menuOf()

        menu.setLabels(toggle = "隠す", quit = "終了")
        menu.setLabels(toggle = "表示", quit = "終了")

        assertEquals("表示", (menu.popupMenu.getComponent(0) as JMenuItem).text)
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
}

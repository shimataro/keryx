package works.merc.keryx.app.tray

import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Covers what [WindowsTrayMenu] actually builds. Like `NativeMenuTest`, showing the menu is not
 * covered — that needs a real display and a tray — so an assertion on the built widgets is what
 * catches an empty or mislabelled menu before it reaches a Windows desktop.
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
}

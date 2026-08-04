package works.merc.keryx.app.platform

import java.awt.dnd.DragSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the pure cursor decision behind [LinuxDragCursorFix]. The listener callbacks themselves
 * need a real AWT `DragSourceDragEvent`, which cannot be constructed from test code.
 */
class LinuxDragCursorFixTest {

    @Test
    fun alwaysShowsTheDropAllowedCursor() {
        assertEquals(DragSource.DefaultMoveDrop, dragCursor())
    }
}

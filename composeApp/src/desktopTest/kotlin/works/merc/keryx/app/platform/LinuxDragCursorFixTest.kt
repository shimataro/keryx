package works.merc.keryx.app.platform

import java.awt.dnd.DnDConstants
import java.awt.dnd.DragSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the pure cursor decision behind [LinuxDragCursorFix]. The listener callbacks themselves
 * need a real AWT `DragSourceDragEvent`, which cannot be constructed from test code.
 */
class LinuxDragCursorFixTest {

    @Test
    fun moveActionMapsToTheDropAllowedCursor() {
        assertEquals(DragSource.DefaultMoveDrop, cursorForDropAction(DnDConstants.ACTION_MOVE))
    }

    @Test
    fun noActionMapsToTheForbiddenCursor() {
        assertEquals(DragSource.DefaultMoveNoDrop, cursorForDropAction(DnDConstants.ACTION_NONE))
    }

    @Test
    fun anyNonNoneActionMapsToTheDropAllowedCursor() {
        assertEquals(DragSource.DefaultMoveDrop, cursorForDropAction(DnDConstants.ACTION_COPY))
    }
}

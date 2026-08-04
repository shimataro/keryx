package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log
import java.awt.Cursor
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.dnd.DragSourceEvent
import java.awt.dnd.DragSourceListener

private const val LOG_TAG = "DragCursor"

/**
 * Keeps the native drag cursor showing "allowed" throughout Linux feed/folder drags, which would
 * otherwise appear stuck on the forbidden icon for the whole gesture.
 *
 * X11 AWT reports no drag-image support (`Toolkit.isDragImageSupported()` is `false`), so Compose's
 * own drag decoration is discarded and the only feedback left is AWT's pair of stock cursors
 * ([DragSource.DefaultMoveDrop] / [DragSource.DefaultMoveNoDrop]), normally swapped automatically
 * based on the drop action AWT computes as the pointer moves. For Keryx's drags — always
 * intra-window, a feed or folder row dragged within the same list — that computed action cannot be
 * trusted on Linux: it was tried (mirroring it into `setCursor()`) and the forbidden icon still
 * showed for the entire gesture, meaning AWT's X11 peer treats these drags as rejected throughout,
 * not merely a repaint that fails to keep up with a correctly-computed "accepted" state.
 *
 * Compose's own `DragAndDropTarget` hit-testing (`FeedListPane.kt`/`FeedListDragAndDrop.kt`) decides
 * drop acceptance entirely independently of this native cursor — that's already why the drop keeps
 * succeeding even while the wrong icon shows — so it is safe to simply never show the forbidden
 * icon at all: it would never reflect anything true here anyway.
 *
 * Per `DragSourceContext`'s own contract, calling `setCursor()` once turns off AWT's automatic
 * cursor handling for the rest of the gesture and makes the caller responsible for it, which is
 * what makes this override possible.
 *
 * Installed on [DragSource.getDefaultDragSource], the process-wide singleton every
 * `dragAndDropSource` gesture is exported through, so it needs no teardown at exit.
 */
internal object LinuxDragCursorFix : DragSourceListener {

    /** Registers this listener on the default [DragSource]. Best effort: never throws. */
    fun install() {
        runCatching { DragSource.getDefaultDragSource().addDragSourceListener(this) }
            .onFailure { Log.warn(LOG_TAG, "Could not install the Linux drag-cursor fix", it) }
    }

    override fun dragEnter(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = dragCursor()
    }

    override fun dragOver(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = dragCursor()
    }

    override fun dropActionChanged(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = dragCursor()
    }

    /** Still the same Keryx-internal gesture while the pointer is outside the list, so no forbidden icon here either. */
    override fun dragExit(dse: DragSourceEvent) {
        dse.dragSourceContext.cursor = dragCursor()
    }

    /** Nothing to restore: AWT tears the native drag session down on its own. */
    override fun dragDropEnd(dsde: DragSourceDropEvent) = Unit
}

/** The cursor shown for the entire duration of a Keryx feed/folder drag on Linux. */
internal fun dragCursor(): Cursor = DragSource.DefaultMoveDrop

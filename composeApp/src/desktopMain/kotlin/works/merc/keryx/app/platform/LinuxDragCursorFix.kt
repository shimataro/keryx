package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log
import java.awt.Cursor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.dnd.DragSourceEvent
import java.awt.dnd.DragSourceListener

private const val LOG_TAG = "DragCursor"

/**
 * Keeps the native drag cursor in sync on Linux, where the feed list's drag gestures would
 * otherwise appear to be rejected.
 *
 * X11 AWT reports no drag-image support (`Toolkit.isDragImageSupported()` is `false`), so Compose's
 * own drag decoration is discarded and the only feedback left is AWT's pair of stock cursors
 * ([DragSource.DefaultMoveDrop] / [DragSource.DefaultMoveNoDrop]). For an intra-window drag — a feed
 * or folder row dragged within the same list — some window managers fail to keep AWT's automatic
 * swap between those two live for the whole gesture, so it can stay stuck on the forbidden
 * "no-drop" icon even though the drop itself still succeeds.
 *
 * Per `DragSourceContext`'s own contract, calling `setCursor()` once turns off that automatic
 * handling for the rest of the gesture and makes the caller responsible for it — which is exactly
 * what this listener then does, deriving the cursor from the drop action AWT has already computed
 * for each event. Compose's `DragAndDropTarget` hit-testing is independent of this native cursor
 * state, so **this changes nothing about which drops are accepted** — only which icon is painted.
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
        dsde.dragSourceContext.cursor = cursorForDropAction(dsde.dropAction)
    }

    override fun dragOver(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = cursorForDropAction(dsde.dropAction)
    }

    override fun dropActionChanged(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = cursorForDropAction(dsde.dropAction)
    }

    /**
     * Leaving a drop target means nothing is currently eligible. [DragSourceEvent] carries no drop
     * action, so the forbidden cursor is set directly rather than derived.
     */
    override fun dragExit(dse: DragSourceEvent) {
        dse.dragSourceContext.cursor = DragSource.DefaultMoveNoDrop
    }

    /** Nothing to restore: AWT tears the native drag session down on its own. */
    override fun dragDropEnd(dsde: DragSourceDropEvent) = Unit
}

/**
 * Maps an AWT drop action to the cursor that represents it.
 *
 * @param dropAction The drop action AWT computed for the current event.
 * @return [DragSource.DefaultMoveDrop] for any action that would accept a drop,
 *   [DragSource.DefaultMoveNoDrop] for [DnDConstants.ACTION_NONE].
 */
internal fun cursorForDropAction(dropAction: Int): Cursor =
    if (dropAction != DnDConstants.ACTION_NONE) DragSource.DefaultMoveDrop else DragSource.DefaultMoveNoDrop

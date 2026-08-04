package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log
import java.awt.Cursor
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.dnd.DragSourceEvent
import java.awt.dnd.DragSourceListener
import java.awt.dnd.DragSourceMotionListener

private const val LOG_TAG = "DragCursor"

/**
 * Keeps the native drag cursor showing "allowed" throughout Linux feed/folder drags, which would
 * otherwise appear stuck on the forbidden icon for the whole gesture.
 *
 * X11 AWT reports no drag-image support (`Toolkit.isDragImageSupported()` is `false`), so Compose's
 * own drag decoration is discarded and the only feedback left is AWT's pair of stock cursors
 * ([DragSource.DefaultMoveDrop] / [DragSource.DefaultMoveNoDrop]).
 *
 * Two attempts already failed against real Linux hardware: mirroring AWT's computed drop action
 * into `setCursor()`, then forcing it unconditionally to [DragSource.DefaultMoveDrop] from
 * [DragSourceListener]'s `dragEnter`/`dragOver`/`dropActionChanged`. Neither changed anything, which
 * points to those callbacks never firing at all for Keryx's drags — always intra-window, a feed or
 * folder row dragged within the same list. `dragEnter`/`dragOver` depend on the drop target
 * acknowledging the drag over XDnD (`XdndStatus`); if that acknowledgment never arrives for a
 * same-window target, as X11's DnD implementation is known to mishandle, those callbacks — and any
 * `setCursor()` call inside them — simply never run.
 *
 * [DragSourceMotionListener.dragMouseMoved], by contrast, is a purely source-side event: it fires on
 * every pointer move during any active drag regardless of what (if anything) a drop target has
 * acknowledged, so it doesn't depend on XDnD status ever arriving. This is also the documented
 * workaround for `setCursor()` not sticking on other platforms (e.g. JDK-7199783 on macOS), so it is
 * the mechanism actually driving the cursor here; the [DragSourceListener] callbacks are kept too, in
 * case they do fire and can update the cursor sooner than the next pointer move.
 *
 * Compose's own `DragAndDropTarget` hit-testing (`FeedListPane.kt`/`FeedListDragAndDrop.kt`) decides
 * drop acceptance entirely independently of this native cursor — that's already why the drop keeps
 * succeeding regardless of which icon shows — so it is safe to simply never show the forbidden icon
 * at all: it would never reflect anything true here anyway.
 *
 * Per `DragSourceContext`'s own contract, calling `setCursor()` once turns off AWT's automatic
 * cursor handling for the rest of the gesture and makes the caller responsible for it, which is
 * what makes this override possible.
 *
 * Installed on [DragSource.getDefaultDragSource], the process-wide singleton every
 * `dragAndDropSource` gesture is exported through, so it needs no teardown at exit.
 */
internal object LinuxDragCursorFix : DragSourceListener, DragSourceMotionListener {

    // TEMPORARY diagnostic: two rounds of blind cursor-forcing already had zero effect on real
    // Linux hardware, which is itself evidence worth capturing rather than guessing a third time.
    // This records, per gesture, which of these callbacks actually fire at all — logged to
    // <appDataDir>/logs/keryx.0.log (and stderr) under the "DragCursor" tag. Remove once the real
    // cause is confirmed (see the callers' commit history for context).
    private val observedCallbacks = mutableSetOf<String>()

    private fun logOnce(callback: String, dropAction: Int? = null) {
        if (observedCallbacks.add(callback)) {
            val suffix = dropAction?.let { " (dropAction=$it)" }.orEmpty()
            Log.info(LOG_TAG, "Observed $callback for the first time this drag$suffix")
        }
    }

    /** Registers this listener on the default [DragSource]. Best effort: never throws. */
    fun install() {
        runCatching {
            DragSource.getDefaultDragSource().addDragSourceListener(this)
            DragSource.getDefaultDragSource().addDragSourceMotionListener(this)
        }.onSuccess {
            Log.info(LOG_TAG, "Linux drag-cursor fix installed")
        }.onFailure {
            Log.warn(LOG_TAG, "Could not install the Linux drag-cursor fix", it)
        }
    }

    /** The primary mechanism: fires on every pointer move regardless of drop-target acknowledgment. */
    override fun dragMouseMoved(dsde: DragSourceDragEvent) {
        logOnce("dragMouseMoved", dsde.dropAction)
        dsde.dragSourceContext.cursor = dragCursor()
    }

    override fun dragEnter(dsde: DragSourceDragEvent) {
        logOnce("dragEnter", dsde.dropAction)
        dsde.dragSourceContext.cursor = dragCursor()
    }

    override fun dragOver(dsde: DragSourceDragEvent) {
        logOnce("dragOver", dsde.dropAction)
        dsde.dragSourceContext.cursor = dragCursor()
    }

    override fun dropActionChanged(dsde: DragSourceDragEvent) {
        logOnce("dropActionChanged", dsde.dropAction)
        dsde.dragSourceContext.cursor = dragCursor()
    }

    /** Still the same Keryx-internal gesture while the pointer is outside the list, so no forbidden icon here either. */
    override fun dragExit(dse: DragSourceEvent) {
        logOnce("dragExit")
        dse.dragSourceContext.cursor = dragCursor()
    }

    /** Nothing to restore: AWT tears the native drag session down on its own. */
    override fun dragDropEnd(dsde: DragSourceDropEvent) {
        Log.info(LOG_TAG, "Drag ended; callbacks observed this gesture: $observedCallbacks")
        observedCallbacks.clear()
    }
}

/** The cursor shown for the entire duration of a Keryx feed/folder drag on Linux. */
internal fun dragCursor(): Cursor = DragSource.DefaultMoveDrop

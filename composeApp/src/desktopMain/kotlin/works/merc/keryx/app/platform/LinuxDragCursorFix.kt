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
 * Keeps the native drag cursor showing "allowed" throughout Linux feed/folder drags on **X11**,
 * which would otherwise appear stuck on the forbidden icon for the whole gesture.
 *
 * X11 AWT reports no drag-image support (`Toolkit.isDragImageSupported()` is `false`), so Compose's
 * own drag decoration is discarded and the only feedback left is AWT's pair of stock cursors
 * ([DragSource.DefaultMoveDrop] / [DragSource.DefaultMoveNoDrop]). Forcing [DragSource.DefaultMoveDrop]
 * via `setCursor()` from both [DragSourceListener] and [DragSourceMotionListener] callbacks — the
 * latter fires on every pointer move regardless of drop-target acknowledgment, and is the documented
 * workaround for `setCursor()` not sticking on other platforms too (e.g. JDK-7199783 on macOS) —
 * eliminates the forbidden icon entirely on a Plasma X11 session (confirmed against real hardware).
 *
 * **This does not help on a Wayland session.** Keryx's Linux build runs on AWT's X11 toolkit (there
 * is no general-availability native-Wayland AWT/Compose Desktop toolkit), so under Wayland it runs as
 * an XWayland client; XWayland bridges the client's X11 XDnD protocol to the compositor's native
 * `wl_data_device` protocol, and the drag cursor shown during that bridged operation is
 * compositor-drawn from the negotiated Wayland action, not from the X11 cursor this class requests.
 * That is an XWayland/compositor-level limitation this code cannot reach — see the "Linux
 * Wayland/XWayland" entry in `docs/known-issues.md` for the investigation and evidence.
 *
 * Compose's own `DragAndDropTarget` hit-testing (`FeedListPane.kt`/`FeedListDragAndDrop.kt`) decides
 * drop acceptance entirely independently of this native cursor — the drop keeps succeeding regardless
 * of which icon shows, on both X11 and Wayland — so it is safe to simply never show the forbidden
 * icon at all on X11: it would never reflect anything true here anyway.
 *
 * Per `DragSourceContext`'s own contract, calling `setCursor()` once turns off AWT's automatic
 * cursor handling for the rest of the gesture and makes the caller responsible for it, which is
 * what makes this override possible.
 *
 * Installed on [DragSource.getDefaultDragSource], the process-wide singleton every
 * `dragAndDropSource` gesture is exported through, so it needs no teardown at exit.
 */
internal object LinuxDragCursorFix : DragSourceListener, DragSourceMotionListener {

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
        dsde.dragSourceContext.cursor = dragCursor()
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

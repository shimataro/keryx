package works.merc.keryx.app.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent

private const val FEED_PREFIX = "feed:"
private const val FOLDER_PREFIX = "folder:"

@OptIn(ExperimentalComposeUiApi::class)
actual fun feedDragTransferData(feedId: String): DragAndDropTransferData =
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(StringSelection("$FEED_PREFIX$feedId")),
        supportedActions = listOf(DragAndDropTransferAction.Move),
    )

@OptIn(ExperimentalComposeUiApi::class)
actual fun DragAndDropEvent.draggedFeedId(): String? =
    (dragData() as? DragData.Text)?.readText()?.takeIf { it.startsWith(FEED_PREFIX) }?.removePrefix(FEED_PREFIX)

@OptIn(ExperimentalComposeUiApi::class)
actual fun folderDragTransferData(folderId: String): DragAndDropTransferData =
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(StringSelection("$FOLDER_PREFIX$folderId")),
        supportedActions = listOf(DragAndDropTransferAction.Move),
    )

@OptIn(ExperimentalComposeUiApi::class)
actual fun DragAndDropEvent.draggedFolderId(): String? =
    (dragData() as? DragData.Text)?.readText()?.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)

// `DragAndDropEvent.positionInRoot` (the Compose-native accessor) is `internal` to the
// compose-ui-desktop module, so it can't be called from this module even from the desktop
// `actual`. Instead we read the position directly off the underlying AWT event: Compose Desktop
// registers a single `java.awt.dnd.DropTarget` on the window's root component, so
// `DropTargetDragEvent`/`DropTargetDropEvent.getLocation()` is already root-relative.
//
// AWT reports `getLocation()` in unscaled logical points, while Compose's `LayoutCoordinates`
// (positionInRoot()/size) are expressed in density-scaled pixels — on any HiDPI/Retina display
// (density != 1.0) these two spaces differ, so the raw AWT value must be multiplied by the
// window's scale factor before it can be compared against Compose-space coordinates.
@OptIn(ExperimentalComposeUiApi::class)
actual fun DragAndDropEvent.positionYInRoot(): Float = when (val native = nativeEvent) {
    is DropTargetDragEvent -> native.location.y.toFloat() * native.dropTargetContext.component.scaleY()
    is DropTargetDropEvent -> native.location.y.toFloat() * native.dropTargetContext.component.scaleY()
    else -> 0f
}

private fun java.awt.Component.scaleY(): Float =
    graphicsConfiguration?.defaultTransform?.scaleY?.toFloat() ?: 1f

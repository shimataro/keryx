package works.merc.keryx.app.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData

/** Builds the drag payload for dragging a feed (identified by [feedId]). */
@OptIn(ExperimentalComposeUiApi::class)
expect fun feedDragTransferData(feedId: String): DragAndDropTransferData

/** The feed id carried by [DragAndDropEvent], or null if it doesn't carry one (e.g. a folder drag). */
@OptIn(ExperimentalComposeUiApi::class)
expect fun DragAndDropEvent.draggedFeedId(): String?

/** Builds the drag payload for dragging a folder (identified by [folderId]). */
@OptIn(ExperimentalComposeUiApi::class)
expect fun folderDragTransferData(folderId: String): DragAndDropTransferData

/** The folder id carried by [DragAndDropEvent], or null if it doesn't carry one (e.g. a feed drag). */
@OptIn(ExperimentalComposeUiApi::class)
expect fun DragAndDropEvent.draggedFolderId(): String?

/**
 * The vertical position of [DragAndDropEvent], in root/window coordinates. Used to tell which
 * half of a row is being hovered over, to decide where a horizontal drop-indicator line goes.
 * Platform-specific because the underlying position accessor (AWT-backed on desktop) isn't part
 * of the common Compose UI API.
 */
@OptIn(ExperimentalComposeUiApi::class)
expect fun DragAndDropEvent.positionYInRoot(): Float

/**
 * The horizontal position of [DragAndDropEvent], in root/window coordinates. Used to position a
 * pointer-following drop-target badge. See [positionYInRoot] for why this is platform-specific.
 */
@OptIn(ExperimentalComposeUiApi::class)
expect fun DragAndDropEvent.positionXInRoot(): Float

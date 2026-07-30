package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.platform.NativeCheckMenuItem
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeSubMenu
import works.merc.keryx.app.platform.draggedFeedId
import works.merc.keryx.app.platform.draggedFolderId
import works.merc.keryx.app.platform.feedDragTransferData
import works.merc.keryx.app.platform.folderDragTransferData
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.platform.positionYInRoot
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_assign_tags
import works.merc.keryx.app.resources.home_delete_folder_menu
import works.merc.keryx.app.resources.home_edit_folder_menu
import works.merc.keryx.app.resources.home_feed_error
import works.merc.keryx.app.resources.home_feed_gone
import works.merc.keryx.app.resources.home_move_to_folder
import works.merc.keryx.app.resources.home_no_folder
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_rename_feed
import works.merc.keryx.app.resources.home_unsubscribe_menu
import works.merc.keryx.app.ui.common.FlatTooltipContent
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/** Background for a folder-drop row: highlighted while a feed is being dragged over it. */
@Composable
private fun dropTargetBackground(isDropTarget: Boolean, selected: Boolean, focused: Boolean): Color =
    if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else selectionBackground(selected, focused)

/** Which half of a row/header a drag is currently hovering over, used to render an insertion line
 * exactly at the boundary the drop would land on. */
private enum class RowHalf { TOP, BOTTOM }

/** How long to hold off clearing the active drop boundary to `null`, so a momentary "no target
 * matched" hit-test gap while crossing between adjacent rows/headers doesn't flicker the
 * indicator/highlight off before the next row's `onMoved` sets it again. */
internal const val BOUNDARY_CLEAR_DEBOUNCE_MS = 50L

/**
 * A single drop-and-reorder insertion point, shared (lifted) across all rows/headers in the pane
 * so that hovering the bottom half of one item and the top half of the next item — which are the
 * same logical boundary — light up exactly one indicator rather than two independent ones.
 */
internal sealed interface DropBoundary {
    data class BeforeFeed(val feedId: String) : DropBoundary
    data class AppendFeeds(val folderId: String?) : DropBoundary
    data class BeforeFolder(val folderId: String) : DropBoundary
    data object AppendFolders : DropBoundary
}

/**
 * Resolves which half of [coordinates] [event]'s pointer is currently over. Falls back to BOTTOM
 * if the row's layout position isn't known yet (e.g. the very first callback after entering).
 */
private fun resolveHalf(event: DragAndDropEvent, coordinates: LayoutCoordinates?): RowHalf {
    val c = coordinates ?: return RowHalf.BOTTOM
    val localY = event.positionYInRoot() - c.positionInRoot().y
    return if (localY < c.size.height / 2f) RowHalf.TOP else RowHalf.BOTTOM
}

/**
 * A thin horizontal line marking a drag-and-drop insertion point (macOS "Notes"-style), aligned
 * to the same left indent as the row content it's next to — the indent communicates which group
 * (folder vs "no folder") the item will land in.
 */
@Composable
private fun InsertionLine(indented: Boolean, visible: Boolean) {
    Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(start = if (indented) 36.dp else 0.dp)
            .height(2.dp)
            .background(if (visible) MaterialTheme.colorScheme.primary else Color.Transparent),
    )
}

/**
 * Renders a folder header with selection styling, folder actions, and drag-and-drop targets.
 *
 * @param folder The folder represented by the header.
 * @param count The number of feeds in the folder.
 * @param collapsed Whether the folder's feed list is collapsed.
 * @param selected Whether the folder is selected.
 * @param focused Whether the folder has focus.
 * @param firstFeedId The first feed in the folder, or `null` when the folder is empty.
 * @param nextFolderId The folder following this folder, or `null` when it is last.
 * @param activeBoundaryState The currently highlighted insertion boundary.
 * @param onBoundaryChange Updates the highlighted insertion boundary.
 * @param onToggleCollapse Toggles the folder's collapsed state.
 * @param onClick Handles selection of the folder.
 * @param onEdit Opens folder editing.
 * @param onDelete Deletes the folder.
 * @param onDropFeed Handles dropping a feed into this folder.
 * @param onReorderFolder Handles repositioning a folder.
 */
@Composable
internal fun FolderGroupHeader(
    folder: Folders,
    count: Long,
    collapsed: Boolean,
    selected: Boolean,
    focused: Boolean,
    firstFeedId: String?,
    nextFolderId: String?,
    feedIdsInFolder: Set<String>,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onToggleCollapse: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDropFeed: (feedId: String, insertBeforeId: String?) -> Unit,
    onReorderFolder: (draggedFolderId: String, insertBeforeId: String?) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val editFolderLabel = stringResource(Res.string.home_edit_folder_menu)
    val deleteFolderLabel = stringResource(Res.string.home_delete_folder_menu)
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(folder.id) else firstFeedId.let(DropBoundary::BeforeFeed)
    val belowFolderBoundary = nextFolderId?.let(DropBoundary::BeforeFolder) ?: DropBoundary.AppendFolders
    val isFeedDragHighlight = when (val boundary = activeBoundaryState.value) {
        is DropBoundary.BeforeFeed -> boundary.feedId in feedIdsInFolder
        is DropBoundary.AppendFeeds -> boundary.folderId == folder.id
        else -> false
    }

    val target = remember(folder.id, feedZoneBoundary, belowFolderBoundary) {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) {
                when {
                    event.draggedFeedId() != null -> onBoundaryChange(feedZoneBoundary)
                    event.draggedFolderId() == folder.id -> Unit
                    event.draggedFolderId() != null -> {
                        val half = resolveHalf(event, coordinates)
                        onBoundaryChange(if (half == RowHalf.TOP) DropBoundary.BeforeFolder(folder.id) else belowFolderBoundary)
                    }
                }
            }

            override fun onExited(event: DragAndDropEvent) {
                val boundary = activeBoundaryState.value
                if (boundary == DropBoundary.BeforeFolder(folder.id) ||
                    boundary == belowFolderBoundary ||
                    boundary == feedZoneBoundary
                ) {
                    onBoundaryChange(null)
                }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onBoundaryChange(null)
                event.draggedFeedId()?.let { feedId ->
                    onDropFeed(feedId, if (isEmpty) null else firstFeedId)
                    return true
                }
                val draggedFolderId = event.draggedFolderId() ?: return false
                if (draggedFolderId == folder.id) return false
                val half = resolveHalf(event, coordinates)
                val insertBeforeId = if (half == RowHalf.TOP) folder.id else nextFolderId
                onReorderFolder(draggedFolderId, insertBeforeId)
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates = it }
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
    ) {
        InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.BeforeFolder(folder.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(dropTargetBackground(isFeedDragHighlight, selected, focused))
                .dragAndDropSource { folderDragTransferData(folder.id) }
                .nativeContextMenu(
                    items = {
                        listOf(
                            NativeMenuItem(editFolderLabel) { onEdit() },
                            NativeMenuItem(deleteFolderLabel) { onDelete() },
                        )
                    },
                    onOpen = { if (!selected) onClick() },
                )
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
                KeryxIcon(
                    if (collapsed) KeryxIcons.ChevronRight else KeryxIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clickable(onClick = onToggleCollapse),
                )
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeryxIcon(
                        KeryxIcons.Folder,
                        contentDescription = null,
                        tint = selectionContentColorOrNull(selected, focused) ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count > 0) CountBadge(count, selected, focused)
        }
        if (collapsed || isEmpty) {
            InsertionLine(indented = true, visible = activeBoundaryState.value == feedZoneBoundary)
        }
        if (nextFolderId == null) {
            InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.AppendFolders)
        }
    }
}

/** The "no folder" section label — also a drop target for feeds dropped into (or reordered
 * within) the "no folder" group. */
@Composable
internal fun NoFolderHeader(
    firstFeedId: String?,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onDropFeed: (feedId: String, insertBeforeId: String?) -> Unit,
) {
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(null) else firstFeedId.let(DropBoundary::BeforeFeed)
    Column(
        Modifier.fillMaxWidth()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(feedZoneBoundary) {
                    object : DragAndDropTarget {
                        override fun onEntered(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) onBoundaryChange(feedZoneBoundary)
                        }

                        override fun onMoved(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) onBoundaryChange(feedZoneBoundary)
                        }

                        override fun onExited(event: DragAndDropEvent) {
                            if (activeBoundaryState.value == feedZoneBoundary) onBoundaryChange(null)
                        }

                        /**
                         * Handles a feed dropped into this feed group.
                         *
                         * @return `true` if a feed was dropped successfully, `false` otherwise.
                         */
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            onBoundaryChange(null)
                            val feedId = event.draggedFeedId() ?: return false
                            onDropFeed(feedId, if (isEmpty) null else firstFeedId)
                            return true
                        }
                    }
                },
            ),
    ) {
        Text(
            stringResource(Res.string.home_no_folder),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(dropTargetBackground(activeBoundaryState.value == feedZoneBoundary, selected = false, focused = false))
                .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        )
        if (isEmpty) InsertionLine(indented = false, visible = activeBoundaryState.value == feedZoneBoundary)
    }
}

/**
 * Displays a feed row with selection styling, feed actions, unread count, and drag-and-drop support.
 *
 * @param feed The feed represented by the row.
 * @param count The number of unread articles.
 * @param indented Whether to indent the row within a folder.
 * @param nextFeedId The ID of the following feed, or `null` when this is the last feed.
 * @param folderId The containing folder's ID, or `null` for feeds without a folder.
 * @param folderBelowBoundary The insertion boundary used when a folder is dragged below this row.
 */
@Composable
internal fun FeedRow(
    feed: Feeds,
    count: Long,
    selected: Boolean,
    focused: Boolean,
    indented: Boolean,
    nextFeedId: String?,
    folderId: String?,
    folderBelowBoundary: DropBoundary?,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    tags: List<Tags>,
    attachedTagIds: Set<String>,
    onToggleFeedTag: (tagId: String, attached: Boolean) -> Unit,
    folders: List<Folders>,
    onMoveFeedToFolder: (folderId: String?) -> Unit,
    onUnsubscribe: () -> Unit,
    onReorderFeed: (draggedFeedId: String, insertBeforeId: String?) -> Unit,
    onReorderFolder: (draggedFolderId: String, insertBeforeId: String?) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val refreshLabel = stringResource(Res.string.home_refresh)
    val assignTagsLabel = stringResource(Res.string.home_assign_tags)
    val renameFeedLabel = stringResource(Res.string.home_rename_feed)
    val moveToFolderLabel = stringResource(Res.string.home_move_to_folder)
    val noFolderLabel = stringResource(Res.string.home_no_folder)
    val unsubscribeLabel = stringResource(Res.string.home_unsubscribe_menu)
    val belowBoundary = nextFeedId?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderId)

    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates = it }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(feed.id, belowBoundary, folderBelowBoundary) {
                    object : DragAndDropTarget {
                        override fun onMoved(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) {
                                val half = resolveHalf(event, coordinates)
                                onBoundaryChange(if (half == RowHalf.TOP) DropBoundary.BeforeFeed(feed.id) else belowBoundary)
                            } else if (folderBelowBoundary != null && event.draggedFolderId() != null) {
                                onBoundaryChange(folderBelowBoundary)
                            }
                        }

                        /**
                         * Clears the active insertion boundary when a drag leaves this feed row's drop targets.
                         */
                        override fun onExited(event: DragAndDropEvent) {
                            val boundary = activeBoundaryState.value
                            if (boundary == DropBoundary.BeforeFeed(feed.id) ||
                                boundary == belowBoundary ||
                                (folderBelowBoundary != null && boundary == folderBelowBoundary)
                            ) {
                                onBoundaryChange(null)
                            }
                        }

                        /**
                         * Handles dropping a feed or folder on this row and requests the corresponding reorder.
                         *
                         * @return `true` when the drop is handled, `false` when no supported drop target is available.
                         */
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            onBoundaryChange(null)
                            event.draggedFeedId()?.let { draggedFeedId ->
                                val half = resolveHalf(event, coordinates)
                                val insertBeforeId = if (half == RowHalf.TOP) feed.id else nextFeedId
                                onReorderFeed(draggedFeedId, insertBeforeId)
                                return true
                            }
                            if (folderBelowBoundary == null) return false
                            val draggedFolderId = event.draggedFolderId() ?: return false
                            onReorderFolder(draggedFolderId, (folderBelowBoundary as? DropBoundary.BeforeFolder)?.folderId)
                            return true
                        }
                    }
                },
            ),
    ) {
        InsertionLine(indented, visible = activeBoundaryState.value == DropBoundary.BeforeFeed(feed.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(selectionBackground(selected, focused))
                .clickable(onClick = onClick)
                .dragAndDropSource { feedDragTransferData(feed.id) }
                .nativeContextMenu(
                    items = {
                        listOf(
                            NativeMenuItem(refreshLabel) { onRefresh() },
                            NativeSubMenu(
                                label = assignTagsLabel,
                                items = tags.map { tag ->
                                    NativeCheckMenuItem(tag.name, checked = tag.id in attachedTagIds) {
                                        onToggleFeedTag(tag.id, tag.id !in attachedTagIds)
                                    }
                                },
                            ),
                            NativeSubMenu(
                                label = moveToFolderLabel,
                                items = buildList {
                                    add(
                                        NativeCheckMenuItem(noFolderLabel, checked = feed.folder_id == null) {
                                            onMoveFeedToFolder(null)
                                        },
                                    )
                                    folders.forEach { folder ->
                                        add(
                                            NativeCheckMenuItem(folder.name, checked = feed.folder_id == folder.id) {
                                                onMoveFeedToFolder(folder.id)
                                            },
                                        )
                                    }
                                },
                            ),
                            NativeMenuItem(renameFeedLabel) { onRename() },
                            NativeMenuItem(unsubscribeLabel) { onUnsubscribe() },
                        )
                    },
                    onOpen = { if (!selected) onClick() },
                )
                .padding(start = if (indented) 36.dp else 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedAvatar(feed.displayTitle(), feed.favicon_url)
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
                Text(
                    feed.displayTitle(),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A 410-Gone feed deliberately keeps error_count at 0 (it is permanent, not a retry
            // candidate), so it is recognized by its last_error marker instead — otherwise a
            // disappeared feed would look completely normal here once its notification is dismissed.
            val gone = feed.last_error == FEED_ERROR_REASON_GONE
            if (feed.error_count > 0 || gone) {
                FeedErrorIndicator(gone)
                Spacer(Modifier.width(4.dp))
            }
            if (count > 0) CountBadge(count, selected, focused)
        }
        if (nextFeedId == null) {
            InsertionLine(indented, visible = activeBoundaryState.value == belowBoundary)
        }
    }
}

/**
 * Displays an error indicator for a feed, with an explanatory tooltip when the feed is gone.
 *
 * @param gone Whether the feed responded with HTTP 410 Gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Displays a feed error indicator, with explanatory tooltip content for feeds that are gone.
 *
 * @param gone Whether the feed returned HTTP 410 Gone.
 */
@Composable
private fun FeedErrorIndicator(gone: Boolean) {
    val icon = @Composable {
        KeryxIcon(
            KeryxIcons.ErrorFilled,
            contentDescription = stringResource(if (gone) Res.string.home_feed_gone else Res.string.home_feed_error),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
    if (!gone) {
        icon()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(stringResource(Res.string.home_feed_gone)) },
        state = rememberTooltipState(),
    ) {
        icon()
    }
}

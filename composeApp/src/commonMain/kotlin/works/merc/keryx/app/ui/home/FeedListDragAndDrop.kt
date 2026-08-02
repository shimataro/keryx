package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.platform.NativeCheckMenuItem
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeSubMenu
import works.merc.keryx.app.platform.feedDragTransferData
import works.merc.keryx.app.platform.folderDragTransferData
import works.merc.keryx.app.platform.nativeContextMenu
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

/** Background for a folder-drop row: highlighted while a feed is being dragged over it.
 * When the folder is the drag source, its selection highlight is suppressed so the destination
 * folder reads as the active target. */
@Composable
private fun dropTargetBackground(
    isDropTarget: Boolean,
    selected: Boolean,
    focused: Boolean,
    isDragSource: Boolean = false,
): Color = when {
    isDropTarget -> MaterialTheme.colorScheme.secondaryContainer
    isDragSource -> Color.Transparent
    else -> selectionBackground(selected, focused)
}

/** Content color paired with [dropTargetBackground]. */
@Composable
internal fun dropTargetContentColorOrNull(
    isDropTarget: Boolean,
    selected: Boolean,
    focused: Boolean,
    isDragSource: Boolean = false,
): Color? = when {
    isDropTarget -> MaterialTheme.colorScheme.onSecondaryContainer
    isDragSource -> null
    selected && focused -> MaterialTheme.colorScheme.onPrimary
    else -> null
}

/** How long a dragged feed must be held over a collapsed folder header before the folder opens by
 * itself, so the feeds inside it become reachable drop targets without letting go of the drag
 * (the spring-loaded folder of Finder / Explorer). Long enough not to fire while merely passing
 * over the header on the way somewhere else. */
private const val FOLDER_AUTO_EXPAND_DELAY_MS = 700L

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
 * Draws a legible drag decoration: an opaque rounded-rect chip (icon + title), replacing
 * Compose's default drag-shadow snapshot. That default replays a `Picture` recorded from the
 * row's last *normal* frame — since the row's own background is transparent whenever unselected,
 * the resulting floating preview had no solid backing and was illegible over arbitrary content.
 *
 * A reactive fix (tint the row opaque only while it's the one being dragged) cannot work here:
 * that state only becomes true *after* the OS-level drag — and its ghost image — has already
 * started, with no recomposition window in between. `drawDragDecoration` sidesteps this because
 * it runs synchronously, freshly, right when the drag starts (confirmed by decompiling
 * `AwtDragAndDropManager.renderDragImage`), so everything here is drawn directly rather than
 * captured from composition. That also means a raw `@Composable` like Coil's `AsyncImage` can't
 * be embedded — `icon` must be a plain [Painter] resolved ahead of time.
 */
private fun DrawScope.drawDragPreviewChip(
    title: String,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    textColor: Color,
    icon: Painter,
    iconTint: Color,
    backgroundColor: Color,
    borderColor: Color,
) {
    val corner = CornerRadius(6.dp.toPx())
    drawRoundRect(color = backgroundColor, cornerRadius = corner)
    drawRoundRect(color = borderColor, cornerRadius = corner, style = Stroke(1.dp.toPx()))

    val iconSize = 18.dp.toPx()
    val padding = 8.dp.toPx()
    translate(left = padding, top = (size.height - iconSize) / 2f) {
        with(icon) { draw(size = Size(iconSize, iconSize), colorFilter = ColorFilter.tint(iconTint)) }
    }

    val textLeft = padding * 2 + iconSize
    val layout = textMeasurer.measure(
        text = title,
        style = textStyle.copy(color = textColor),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = (size.width - textLeft - padding).toInt().coerceAtLeast(0)),
    )
    drawText(layout, topLeft = Offset(textLeft, (size.height - layout.size.height) / 2f))
}

/**
 * Resolves everything [drawDragPreviewChip] needs for a feed row's drag decoration (feed title +
 * the same fallback icon [FeedAvatar][FeedListRowParts.kt] uses when a favicon isn't available),
 * so [FeedRow] and [TagFeedRow] don't each repeat the same composition-time setup.
 */
@Composable
internal fun rememberFeedDragDecoration(title: String): DrawScope.() -> Unit {
    val textMeasurer = rememberTextMeasurer()
    val icon = painterResource(KeryxIcons.PublicFilled)
    val textStyle = MaterialTheme.typography.bodyLarge
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    return {
        drawDragPreviewChip(title, textMeasurer, textStyle, textColor, icon, iconTint, backgroundColor, borderColor)
    }
}

/**
 * Renders a folder header with selection styling, folder actions, and the highlight/insertion-line
 * rendering for a drag hovering it (the actual drop handling is centralized in `FeedListPane`'s
 * outer `Box` — see its doc comment for why).
 *
 * @param folder The folder represented by the header.
 * @param count The number of feeds in the folder.
 * @param collapsed Whether the folder's feed list is collapsed.
 * @param selected Whether the folder is selected.
 * @param focused Whether the folder has focus.
 * @param firstFeedId The first feed in the folder, or `null` when the folder is empty.
 * @param nextFolderId The folder following this folder, or `null` when it is last.
 * @param activeBoundaryState The currently highlighted insertion boundary.
 * @param isDragSource Whether the folder currently contains the feed being dragged.
 * @param onToggleCollapse Toggles the folder's collapsed state.
 * @param onClick Handles selection of the folder.
 * @param onEdit Opens folder editing.
 * @param onDelete Deletes the folder.
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
    onToggleCollapse: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isDragSource: Boolean = false,
) {
    val editFolderLabel = stringResource(Res.string.home_edit_folder_menu)
    val deleteFolderLabel = stringResource(Res.string.home_delete_folder_menu)
    val dragTextMeasurer = rememberTextMeasurer()
    val dragIcon = painterResource(KeryxIcons.Folder)
    val dragTextStyle = MaterialTheme.typography.bodyLarge
    val dragTextColor = MaterialTheme.colorScheme.onSurface
    val dragIconTint = MaterialTheme.colorScheme.primary
    val dragBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val dragBorderColor = MaterialTheme.colorScheme.outlineVariant
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(folder.id) else firstFeedId.let(DropBoundary::BeforeFeed)
    val isFeedDragHighlight = when (val boundary = activeBoundaryState.value) {
        is DropBoundary.BeforeFeed -> boundary.feedId in feedIdsInFolder
        is DropBoundary.AppendFeeds -> boundary.folderId == folder.id
        else -> false
    }

    // Spring-loaded folder: holding a dragged feed over a collapsed header opens it after a short
    // pause, so its feeds become reachable drop targets mid-drag. The expansion runs through the
    // same persisted toggle as a click, so the folder deliberately stays open afterwards. Driven
    // purely by activeBoundaryState (fed by the centralized drop target in FeedListPane), so this
    // needs no drag target of its own.
    LaunchedEffect(isFeedDragHighlight, collapsed) {
        if (!isFeedDragHighlight || !collapsed) return@LaunchedEffect
        delay(FOLDER_AUTO_EXPAND_DELAY_MS)
        onToggleCollapse()
    }

    Column(Modifier.fillMaxWidth()) {
        InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.BeforeFolder(folder.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(dropTargetBackground(isFeedDragHighlight, selected, focused, isDragSource))
                .dragAndDropSource(
                    drawDragDecoration = {
                        drawDragPreviewChip(
                            folder.name,
                            dragTextMeasurer,
                            dragTextStyle,
                            dragTextColor,
                            dragIcon,
                            dragIconTint,
                            dragBackgroundColor,
                            dragBorderColor,
                        )
                    },
                ) { folderDragTransferData(folder.id) }
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
            CompositionLocalProvider(
                LocalContentColor provides (
                    dropTargetContentColorOrNull(isFeedDragHighlight, selected, focused, isDragSource)
                        ?: LocalContentColor.current
                    ),
            ) {
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
                        tint = dropTargetContentColorOrNull(isFeedDragHighlight, selected, focused, isDragSource)
                            ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count > 0) CountBadge(count, selected, focused, isFeedDragHighlight, isDragSource)
        }
        if (collapsed || isEmpty) {
            InsertionLine(indented = true, visible = activeBoundaryState.value == feedZoneBoundary)
        }
        if (nextFolderId == null) {
            InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.AppendFolders)
        }
    }
}

/** The "no folder" section label, including the highlight/insertion-line rendering for a drag
 * hovering it (drop handling is centralized in `FeedListPane`'s outer `Box`). */
@Composable
internal fun NoFolderHeader(
    firstFeedId: String?,
    activeBoundaryState: State<DropBoundary?>,
) {
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(null) else firstFeedId.let(DropBoundary::BeforeFeed)
    Column(Modifier.fillMaxWidth()) {
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
 * Displays a feed row with selection styling, feed actions, unread count, and the highlight/
 * insertion-line rendering for a drag hovering it (drop handling is centralized in `FeedListPane`'s
 * outer `Box`).
 *
 * @param feed The feed represented by the row.
 * @param count The number of unread articles.
 * @param indented Whether to indent the row within a folder.
 * @param nextFeedId The ID of the following feed, or `null` when this is the last feed.
 * @param folderId The containing folder's ID, or `null` for feeds without a folder.
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
    activeBoundaryState: State<DropBoundary?>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    tags: List<Tags>,
    attachedTagIds: Set<String>,
    onToggleFeedTag: (tagId: String, attached: Boolean) -> Unit,
    folders: List<Folders>,
    onMoveFeedToFolder: (folderId: String?) -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val refreshLabel = stringResource(Res.string.home_refresh)
    val assignTagsLabel = stringResource(Res.string.home_assign_tags)
    val renameFeedLabel = stringResource(Res.string.home_rename_feed)
    val moveToFolderLabel = stringResource(Res.string.home_move_to_folder)
    val noFolderLabel = stringResource(Res.string.home_no_folder)
    val unsubscribeLabel = stringResource(Res.string.home_unsubscribe_menu)
    val dragDecoration = rememberFeedDragDecoration(feed.displayTitle())
    val belowBoundary = nextFeedId?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderId)

    Column(Modifier.fillMaxWidth()) {
        InsertionLine(indented, visible = activeBoundaryState.value == DropBoundary.BeforeFeed(feed.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(selectionBackground(selected, focused))
                .clickable(onClick = onClick)
                .dragAndDropSource(drawDragDecoration = dragDecoration) { feedDragTransferData(feed.id) }
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

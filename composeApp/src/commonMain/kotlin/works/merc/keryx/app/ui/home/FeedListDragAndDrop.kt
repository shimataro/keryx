package works.merc.keryx.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.platform.NativeCheckMenuItem
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeMenuSeparator
import works.merc.keryx.app.platform.NativeMenuShortcut
import works.merc.keryx.app.platform.NativeSubMenu
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_assign_tags
import works.merc.keryx.app.resources.home_copy_feed_url
import works.merc.keryx.app.resources.home_copy_site_url
import works.merc.keryx.app.resources.home_delete_folder_menu
import works.merc.keryx.app.resources.home_edit_folder_menu
import works.merc.keryx.app.resources.home_feed_error
import works.merc.keryx.app.resources.home_feed_gone
import works.merc.keryx.app.resources.home_move_to_folder
import works.merc.keryx.app.resources.home_no_folder
import works.merc.keryx.app.resources.home_open_site
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_rename_feed
import works.merc.keryx.app.resources.home_unsubscribe_menu
import works.merc.keryx.app.ui.common.FlatTooltipContent
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/** Background for a row that may be an active drop target: [containerColor] while a feed is being
 * dragged over it, otherwise the normal selection background. When the row is the drag source,
 * its selection highlight is suppressed so the destination reads as the active target. */
@Composable
internal fun dropTargetBackground(
    isDropTarget: Boolean,
    selected: Boolean,
    focused: Boolean,
    containerColor: Color,
    isDragSource: Boolean = false,
): Color = when {
    isDropTarget -> containerColor
    isDragSource -> Color.Transparent
    else -> selectionBackground(selected, focused)
}

/** Content color paired with [dropTargetBackground] (using its matching `on<Container>` color). */
@Composable
internal fun dropTargetContentColorOrNull(
    isDropTarget: Boolean,
    selected: Boolean,
    focused: Boolean,
    onContainerColor: Color,
    isDragSource: Boolean = false,
): Color? = when {
    isDropTarget -> onContainerColor
    isDragSource -> null
    else -> selectionContentColorOrNull(selected, focused)
}

/**
     * Adds a colored outline when the row is an active drop target.
     *
     * @param isDropTarget Whether the row is currently an active drop target.
     * @param color The outline color.
     */
@Composable
internal fun dropTargetBorderModifier(isDropTarget: Boolean, color: Color): Modifier =
    if (isDropTarget) Modifier.border(2.dp, color, MaterialTheme.shapes.small) else Modifier

/** Test tag on a [FeedRow]'s whole clickable band. */
internal fun feedRowTestTag(feedId: String): String = "feed-row-$feedId"

/** Test tag on a [FolderGroupHeader]'s whole clickable band. */
internal fun folderRowTestTag(folderId: String): String = "folder-row-$folderId"

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
 * A drag insertion marker to draw at one edge (top or bottom) of a list row's band, via
 * [insertionMarkers].
 *
 * @param indented Whether the marker aligns with an indented (folder-nested feed) row.
 * @param unpaired Whether no other row will ever paint the opposing half of this same boundary —
 *   e.g. the very top/bottom of the whole list, a collapsed/empty folder's own feed-zone edge, the
 *   last folder's `AppendFolders` edge, a folder's own `BeforeFolder` edge (no preceding folder
 *   ever checks for it), or the last feed of any folder followed by another folder. When `true`,
 *   [insertionMarkers] paints the *full* [LIST_ROW_GUIDE_THICKNESS] here instead of half, since
 *   there is nothing on the far side to complete the line.
 */
internal data class InsertionMarker(val indented: Boolean, val unpaired: Boolean = false)

/**
 * Where an indented feed row's own content starts, inside the row's [listRowSurface] margin — the
 * single value that indent is expressed in. `FeedRow` (nested in a folder) and `TagFeedRow` (nested
 * under a tag) apply it as their leading padding, and an [InsertionMarker] with
 * [InsertionMarker.indented] set takes its left edge *from here* rather than carrying its own
 * number, so the marker and the row title it lines up with can only ever move together.
 */
internal val FEED_ROW_INDENT = 36.dp

/**
 * Draws drag insertion markers at this row's top and/or bottom edge, `null` for an edge that is
 * not the current drop boundary.
 *
 * **A boundary is drawn by both of the rows that touch it**, each painting half of
 * [LIST_ROW_GUIDE_THICKNESS] on its own side, so the line straddles the boundary instead of sitting
 * inside one of them. That makes the marker a literal picture of where a click will go: its upper
 * half is the region that selects the row above, its lower half the row below. It does *not* fill
 * the whole gap between the two rows — each row keeps its highlight [LIST_ROW_GUIDE_CLEARANCE] clear
 * of the line, which is the outer part of its own [LIST_ROW_VERTICAL_MARGIN] that this paints into.
 * Callers must light up *both* edges of a boundary (see `FeedRow`, which draws its bottom edge for
 * whatever boundary follows it, not only when it is the last row in its group). Where the far side
 * can never paint a matching half — the very top/bottom of the whole list, a collapsed or empty
 * folder's own feed-zone edge, a folder's `BeforeFolder` edge, the last folder's `AppendFolders`
 * edge, or the last feed of a folder followed by another folder — the caller marks that edge
 * [InsertionMarker.unpaired] so the full [LIST_ROW_GUIDE_THICKNESS] is painted there instead of
 * half, keeping the line the same thickness everywhere.
 *
 * Drawn **after** the content (`drawWithContent`, not `drawBehind`) so nothing the row paints can
 * hide it. The margin it paints into is outside the row's own highlight, so drawing on top covers
 * nothing meaningful.
 *
 * This used to be a real `Box` composed as a layout sibling above/below the row's content (inside
 * a wrapping `Column`), taking up fixed layout space regardless of visibility (so toggling one
 * on/off never shifted anything else — the usual "reserve the slot, vary only its content" rule).
 * But a *reserved slot* still costs layout space, and — critically — that space belonged entirely
 * to whichever row's `Column` contained it, so the gap between two rows could only ever split
 * unevenly, never at its true midpoint: a click just past one row's highlight, closer to it than
 * to its neighbor, could still resolve to the neighbor. Painting instead of laying out fixed that,
 * and also removed the `Column` wrapper `FeedRow`/`FolderGroupHeader` needed only to lay the old
 * `Box` out as a sibling — see `listRowClickable`'s KDoc.
 *
 * Must sit *before* [listRowSurface] in the modifier chain: the marker is drawn in the margin
 * [listRowSurface]'s leading `padding` reserves, so applying it afterwards would inset it away —
 * and [listRowSurface]'s `decoration` slot is clipped to the inset rounded rect regardless.
 */
@Composable
internal fun Modifier.insertionMarkers(top: InsertionMarker? = null, bottom: InsertionMarker? = null): Modifier {
    val color = MaterialTheme.colorScheme.primary
    val horizontalMargin = LIST_ROW_HORIZONTAL_MARGIN
    val halfGuideThickness = LIST_ROW_GUIDE_THICKNESS / 2f
    val fullGuideThickness = LIST_ROW_GUIDE_THICKNESS
    return drawWithContent {
        drawContent()
        val halfThicknessPx = halfGuideThickness.toPx()
        val fullThicknessPx = fullGuideThickness.toPx()
        val horizontalMarginPx = horizontalMargin.toPx()
        val indentPx = FEED_ROW_INDENT.toPx()
        val right = size.width - horizontalMarginPx

        fun draw(marker: InsertionMarker, atTop: Boolean) {
            val left = horizontalMarginPx + if (marker.indented) indentPx else 0f
            val thicknessPx = if (marker.unpaired) fullThicknessPx else halfThicknessPx
            val y = if (atTop) 0f else size.height - thicknessPx
            drawRect(color = color, topLeft = Offset(left, y), size = Size((right - left).coerceAtLeast(0f), thicknessPx))
        }

        top?.let { draw(it, atTop = true) }
        bottom?.let { draw(it, atTop = false) }
    }
}

/**
 * Renders a folder header with selection styling, context-menu actions, drag-hover highlighting,
 * insertion indicators, and automatic expansion while a dragged feed hovers over a collapsed folder.
 *
 * @param folder The folder represented by the header.
 * @param count The number of feeds in the folder.
 * @param collapsed Whether the folder's feed list is collapsed.
 * @param selected Whether the folder is selected.
 * @param focused Whether the folder has focus.
 * @param firstFeedId The first feed in the folder, or `null` if the folder is empty.
 * @param nextFolderId The ID of the following folder, or `null` if this is the last folder.
 * @param feedIdsInFolder The IDs of feeds contained in the folder.
 * @param activeBoundaryState The currently highlighted insertion boundary.
 * @param onToggleCollapse Toggles the folder's collapsed state.
 * @param onClick Selects the folder.
 * @param onEdit Starts inline editing of the folder's name.
 * @param onDelete Deletes the folder.
 * @param editingName Whether the name is currently open for inline editing (see [InlineRenameField]).
 * @param onRenameCommit Applies an edited folder name.
 * @param onRenameCancel Abandons an in-progress name edit.
 * @param nameError Produces a validation message for an edited name, or `null` when valid.
 * @param isDragSource Whether the folder contains the feed currently being dragged.
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
    editingName: Boolean = false,
    onRenameCommit: (String) -> Unit = {},
    onRenameCancel: () -> Unit = {},
    nameError: (String) -> String? = { null },
    isDragSource: Boolean = false,
) {
    val editFolderLabel = stringResource(Res.string.home_edit_folder_menu)
    val deleteFolderLabel = stringResource(Res.string.home_delete_folder_menu)
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

    val rowInteraction = remember { MutableInteractionSource() }
    val currentBoundary = activeBoundaryState.value
    // Always unpaired: no folder ever checks the boundary before the *next* folder from its own
    // bottom edge, so a folder's own top edge is the only place `BeforeFolder` is ever painted.
    val topMarker = InsertionMarker(indented = false, unpaired = true).takeIf { currentBoundary == DropBoundary.BeforeFolder(folder.id) }
    // The feed-zone half is drawn whether or not the folder is open: expanded and non-empty, the
    // first feed row below paints the matching half from its own top edge (both indented, so they
    // line up), so this stays paired (half thickness). Collapsed or empty, no feed row exists to
    // pair with it, so it's unpaired (full thickness) instead. `AppendFolders` stays gated on being
    // the last folder — that boundary only exists there, and is always unpaired (no row ever
    // checks for it from the other side). See `insertionMarkers`.
    val bottomMarker = when {
        currentBoundary == feedZoneBoundary -> InsertionMarker(indented = true, unpaired = collapsed || isEmpty)
        nextFolderId == null && currentBoundary == DropBoundary.AppendFolders -> InsertionMarker(indented = false, unpaired = true)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth()
            .testTag(folderRowTestTag(folder.id))
            .listRowClickable(rowInteraction, onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(editFolderLabel, renameNativeShortcut) { onEdit() },
                        NativeMenuItem(deleteFolderLabel, deleteNativeShortcut) { onDelete() },
                    )
                },
                onOpen = { if (!selected) onClick() },
            )
            .insertionMarkers(top = topMarker, bottom = bottomMarker)
            .listRowSurface(
                dropTargetBackground(isFeedDragHighlight, selected, focused, MaterialTheme.colorScheme.secondaryContainer, isDragSource),
                rowInteraction,
                decoration = dropTargetBorderModifier(isFeedDragHighlight, MaterialTheme.colorScheme.secondary),
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides (
                dropTargetContentColorOrNull(isFeedDragHighlight, selected, focused, MaterialTheme.colorScheme.onSecondaryContainer, isDragSource)
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
                Modifier.weight(1f).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeryxIcon(
                    KeryxIcons.Folder,
                    contentDescription = null,
                    tint = dropTargetContentColorOrNull(isFeedDragHighlight, selected, focused, MaterialTheme.colorScheme.onSecondaryContainer, isDragSource)
                        ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                // Same weighted slot either way, so the chevron/folder icon on the left and the
                // count badge on the right never move when editing starts or ends.
                if (editingName) {
                    Box(Modifier.weight(1f)) {
                        InlineRenameField(
                            value = folder.name,
                            onCommit = onRenameCommit,
                            onCancel = onRenameCancel,
                            blockingError = nameError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (count > 0) CountBadge(count, selected, focused, isFeedDragHighlight, isDragSource)
    }
}

/** The "no folder" section label, including the highlight/insertion-line rendering for a drag
 * hovering it (drop handling is centralized in `FeedListPane`'s outer `Box`). */
@Composable
internal fun NoFolderHeader(
    firstFeedId: String?,
    feedIdsInNoFolder: Set<String>,
    activeBoundaryState: State<DropBoundary?>,
) {
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(null) else firstFeedId.let(DropBoundary::BeforeFeed)
    val isFeedDragHighlight = when (val boundary = activeBoundaryState.value) {
        is DropBoundary.BeforeFeed -> boundary.feedId in feedIdsInNoFolder
        is DropBoundary.AppendFeeds -> boundary.folderId == null
        else -> false
    }
    Text(
        stringResource(Res.string.home_no_folder),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
            // Paired (half thickness) when the group has feeds: the first one below paints the
            // matching half from its own top edge (also un-indented, so they line up). Unpaired
            // (full thickness) when empty — no feed row exists to pair with it.
            .insertionMarkers(
                bottom = InsertionMarker(indented = false, unpaired = isEmpty).takeIf { activeBoundaryState.value == feedZoneBoundary },
            )
            .listRowSurface(
                dropTargetBackground(
                    isFeedDragHighlight,
                    selected = false,
                    focused = false,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                decoration = dropTargetBorderModifier(isFeedDragHighlight, MaterialTheme.colorScheme.secondary),
            )
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

/**
 * Displays a feed row with selection styling, unread count, error indicators, context-menu actions,
 * and insertion markers for drop boundaries.
 *
 * @param feed The feed represented by the row.
 * @param count The number of unread articles.
 * @param selectionTone How this rendered instance paints its selection — the same feed can render
 *   again under an expanded tag, and only the instance actually selected paints
 *   [RowSelectionTone.PRIMARY] (see [FeedListRowSelection]).
 * @param indented Whether to indent the row within a folder.
 * @param nextFeedId The ID of the following feed, or `null` when this is the last feed.
 * @param folderId The containing folder's ID, or `null` for feeds without a folder.
 * @param isFirstInList Whether this is the very first row of the entire feed list — only possible
 *   when there are no folders at all (otherwise the first folder's header always precedes every
 *   feed row). Paints a full-thickness top marker instead of half, since nothing precedes it.
 * @param activeBoundaryState The currently active insertion boundary.
 * @param onRename Starts inline editing of the feed's display title.
 * @param editingName Whether the title is currently open for inline editing (see [InlineRenameField]).
 * @param onRenameCommit Applies an edited title; a blank value resets it to the feed's own title.
 * @param onRenameCancel Abandons an in-progress title edit.
 * @param onCopyFeedUrl Copies the feed's own (RSS/Atom) URL to the clipboard.
 * @param onCopySiteUrl Copies the feed's website URL to the clipboard.
 * @param onOpenSite Opens the feed's website in the external browser.
 */
@Composable
internal fun FeedRow(
    feed: Feeds,
    count: Long,
    selectionTone: RowSelectionTone,
    focused: Boolean,
    indented: Boolean,
    nextFeedId: String?,
    folderId: String?,
    isFirstInList: Boolean = false,
    activeBoundaryState: State<DropBoundary?>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    editingName: Boolean,
    onRenameCommit: (String) -> Unit,
    onRenameCancel: () -> Unit,
    onRefresh: () -> Unit,
    tags: List<Tags>,
    attachedTagIds: Set<String>,
    onToggleFeedTag: (tagId: String, attached: Boolean) -> Unit,
    folders: List<Folders>,
    onMoveFeedToFolder: (folderId: String?) -> Unit,
    onUnsubscribe: () -> Unit,
    onCopyFeedUrl: () -> Unit,
    onCopySiteUrl: () -> Unit,
    onOpenSite: () -> Unit,
) {
    val refreshLabel = stringResource(Res.string.home_refresh)
    val assignTagsLabel = stringResource(Res.string.home_assign_tags)
    val renameFeedLabel = stringResource(Res.string.home_rename_feed)
    val moveToFolderLabel = stringResource(Res.string.home_move_to_folder)
    val noFolderLabel = stringResource(Res.string.home_no_folder)
    val unsubscribeLabel = stringResource(Res.string.home_unsubscribe_menu)
    val copyFeedUrlLabel = stringResource(Res.string.home_copy_feed_url)
    val copySiteUrlLabel = stringResource(Res.string.home_copy_site_url)
    val openSiteLabel = stringResource(Res.string.home_open_site)
    val siteUrlUsable = hasUsableUrl(feed.site_url)
    val belowBoundary = nextFeedId?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderId)
    val rowInteraction = remember { MutableInteractionSource() }
    val currentBoundary = activeBoundaryState.value
    // Both edges, not just the last row's: for any feed but the last in its group, the row after
    // this one paints the other half of `belowBoundary` from its own top edge, and the two halves
    // together make one line centred on the boundary — see `insertionMarkers`. The last feed in a
    // group is unpaired instead: nothing after it (the next folder's header, or the end of the
    // list) ever checks for `belowBoundary`, so it paints the full thickness itself.
    val isLastInList = nextFeedId == null
    val topMarker = InsertionMarker(indented, unpaired = isFirstInList).takeIf { currentBoundary == DropBoundary.BeforeFeed(feed.id) }
    val bottomMarker = InsertionMarker(indented, unpaired = isLastInList).takeIf { currentBoundary == belowBoundary }

    Row(
        Modifier.fillMaxWidth()
            .testTag(feedRowTestTag(feed.id))
            .listRowClickable(rowInteraction, onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(refreshLabel, NativeMenuShortcut(Key.R, ctrl = true, shift = true)) { onRefresh() },
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
                        NativeMenuSeparator,
                        NativeMenuItem(copyFeedUrlLabel) { onCopyFeedUrl() },
                        NativeMenuItem(copySiteUrlLabel, enabled = siteUrlUsable) { onCopySiteUrl() },
                        NativeMenuItem(openSiteLabel, enabled = siteUrlUsable) { onOpenSite() },
                        NativeMenuSeparator,
                        NativeMenuItem(renameFeedLabel, renameNativeShortcut) { onRename() },
                        NativeMenuSeparator,
                        NativeMenuItem(unsubscribeLabel, deleteNativeShortcut) { onUnsubscribe() },
                    )
                },
                // A secondary-toned (or unselected) row is not the one currently focused, so a
                // right-click on it promotes it first, exactly as the old `!selected` check did.
                onOpen = { if (selectionTone != RowSelectionTone.PRIMARY) onClick() },
            )
            .insertionMarkers(top = topMarker, bottom = bottomMarker)
            .listRowSurface(selectionBackground(selectionTone, focused), rowInteraction)
            .padding(start = if (indented) FEED_ROW_INDENT else 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedAvatar(feed.displayTitle(), feed.favicon_url)
        Spacer(Modifier.width(12.dp))
        // Same weighted slot either way, so the favicon on the left and the error indicator /
        // count badge on the right never move when editing starts or ends. A blank value is
        // meaningful here (it clears `custom_title`), so it commits rather than being rejected,
        // and the placeholder shows the feed's own title it would fall back to.
        if (editingName) {
            Box(Modifier.weight(1f)) {
                InlineRenameField(
                    value = feed.custom_title ?: feed.title,
                    onCommit = onRenameCommit,
                    onCancel = onRenameCancel,
                    placeholder = feed.title,
                    allowBlank = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selectionTone, focused) ?: LocalContentColor.current)) {
                Text(
                    feed.displayTitle(),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A 410-Gone feed deliberately keeps error_count at 0 (it is permanent, not a retry
        // candidate), so it is recognized by its last_error marker instead — otherwise a
        // disappeared feed would look completely normal here once its notification is dismissed.
        val gone = feed.last_error == FEED_ERROR_REASON_GONE
        if (feed.error_count > 0 || gone) {
            FeedErrorIndicator(gone)
            Spacer(Modifier.width(4.dp))
        }
        if (count > 0) CountBadge(count, selectionTone == RowSelectionTone.PRIMARY, focused)
    }
}

/**
 * Displays an error indicator for a feed, with an explanatory tooltip when the feed is gone.
 *
 * @param gone Whether the feed responded with HTTP 410 Gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

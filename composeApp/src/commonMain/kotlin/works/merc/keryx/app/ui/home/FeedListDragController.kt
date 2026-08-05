package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import works.merc.keryx.app.ui.common.KeryxIcons

/** Test tag on [FeedDragGhost]'s inner chip `Box`, present only while a drag is in progress —
 * `FeedListDragTest` asserts on its presence/absence to verify the ghost's lifecycle. */
internal const val FEED_DRAG_GHOST_TEST_TAG = "feed-drag-ghost"

/** What a feed-list drag is currently carrying. [title] is only used to label the drag ghost. */
internal sealed interface DraggedItem {
    val title: String

    data class Feed(val feedId: String, override val title: String) : DraggedItem
    data class Folder(val folderId: String, override val title: String) : DraggedItem
}

/**
 * What a press on a feed-list row would drag, plus the geometry the drag ghost needs: how far below
 * the row's own top edge the pointer landed ([grabOffsetY]) and how tall that row is
 * ([rowHeightPx]), so the ghost can float under the pointer exactly where the row sat.
 */
internal data class FeedListDragGrab(
    val item: DraggedItem,
    val grabOffsetY: Float,
    val rowHeightPx: Int,
)

/**
 * The floating drag chip's state, deliberately hoisted out of `FeedListPane` up to `HomeScreen`'s
 * root `Box` (see [FeedDragGhost]): the ghost has to be able to float over the *whole* window —
 * including past the feed pane's right edge, over the article list — and a composable drawn inside
 * `FeedListPane` would be painted before (and therefore under) its sibling panes.
 *
 * Positions are in root/window coordinates, since that is the only space both panes share.
 */
@Stable
internal class FeedDragOverlayState {
    /** The item currently being dragged, or `null` when no drag is in progress. */
    var item: DraggedItem? by mutableStateOf(null)

    /** Top-left of the ghost chip, in root coordinates. */
    var positionInRoot: Offset by mutableStateOf(Offset.Zero)

    /** Size of the ghost chip, matching the dragged row's own size. */
    var size: IntSize by mutableStateOf(IntSize.Zero)

    /** Installed by [FeedListDragController] so the drag can be aborted from outside the pane. */
    var onCancel: () -> Unit = {}

    /** Aborts an in-progress drag without committing a reorder. Returns whether there was one. */
    fun cancel(): Boolean {
        if (item == null) return false
        onCancel()
        return true
    }
}

/**
 * Drives the feed list's hand-rolled (Compose-native, not OS-level) reorder drag: resolves which
 * row a press would drag, tracks the pointer, keeps the insertion-line / drop-highlight state in
 * sync, and applies the resulting move on release.
 *
 * All pointer positions passed in are **local to the pane's single drag-host `Box`** — the same
 * never-virtualized `Box` that hosts the gesture, whose bounds arrive via [hostBoundsState]. That
 * host is the only place the gesture can live: auto-scroll can scroll the dragged row out of the
 * viewport mid-drag, at which point `LazyColumn` disposes the row's composition (and any
 * `pointerInput` coroutine it hosted), killing the gesture. Resolving the drag *source* by
 * hit-testing [listState]'s layout info — exactly how the drop side already resolves its target —
 * keeps the whole gesture on a node that is never recycled.
 *
 * @param vm Performs the move/reorder/tag-attach mutation a drop resolves to.
 * @param listState The feed list's scroll state, used to hit-test the pointer against visible rows.
 * @param hostBoundsState The drag host's bounds in root coordinates, for local↔root translation.
 * @param dropIndexState The current feed/folder layout, used to resolve drop boundaries and apply drops.
 * @param activeBoundaryState Updated with the boundary the dragged row/folder would be inserted at.
 * @param draggedFeedIdState Updated with the id of the feed currently being dragged, or `null`.
 * @param hoveredAttachTagIdState Updated with the id of the tag currently hovered for attachment, or `null`.
 * @param dragPointerYState Updated with the drag pointer's Y in root coordinates, driving auto-scroll.
 * @param titleOfState Resolves a dragged row's display title, for the ghost chip's label.
 * @param overlay The window-wide ghost state this controller writes while a drag is in progress.
 */
internal class FeedListDragController(
    private val vm: HomeViewModel,
    private val listState: LazyListState,
    private val hostBoundsState: State<Rect>,
    private val dropIndexState: State<FeedListDropIndex>,
    private val activeBoundaryState: MutableState<DropBoundary?>,
    private val draggedFeedIdState: MutableState<String?>,
    private val hoveredAttachTagIdState: MutableState<String?>,
    private val dragPointerYState: MutableState<Float?>,
    private val titleOfState: State<(FeedListDragSourceKey) -> String>,
    private val overlay: FeedDragOverlayState,
) {
    init {
        overlay.onCancel = ::cancel
    }

    /** The item currently being dragged, or `null` when no drag is in progress. */
    val dragged: DraggedItem? get() = overlay.item

    /** Top-left of the drag ghost, in root coordinates. */
    val ghostPositionInRoot: Offset get() = overlay.positionInRoot

    /** Size of the drag ghost. */
    val ghostSize: IntSize get() = overlay.size

    /** Where inside the dragged row the pointer grabbed it, local to the drag host. */
    private var grabOffset: Offset = Offset.Zero

    /** Last reported pointer position, local to the drag host — replayed by [refreshHover]. */
    private var lastPosition: Offset = Offset.Zero

    /**
     * Determines which visible feed-list row contains [localY].
     *
     * @param localY The pointer's Y, local to the drag host (equivalently, the list viewport).
     * @return The matched row band, or `null` when the position is outside all visible rows.
     */
    private fun bandAt(localY: Float): FeedListRowBand? {
        val bands = listState.layoutInfo.visibleItemsInfo.map {
            FeedListRowBand(it.key, it.offset, it.size)
        }
        return resolveHitBand(localY, bands)
    }

    /**
     * What a press at [localY] would drag, or `null` when the row there isn't draggable at all
     * (a tag row, a section header, the divider, or empty space below the last row).
     */
    fun sourceAt(localY: Float): FeedListDragGrab? {
        val band = bandAt(localY) ?: return null
        val item = when (val key = parseFeedListDragSourceKey(band.key)) {
            is FeedListDragSourceKey.Feed -> DraggedItem.Feed(key.feedId, titleOfState.value(key))
            is FeedListDragSourceKey.Folder -> DraggedItem.Folder(key.folderId, titleOfState.value(key))
            null -> return null
        }
        return FeedListDragGrab(item, localY - band.offsetPx, band.sizePx)
    }

    /**
     * Begins dragging [item].
     *
     * @param pos The current pointer position, local to the drag host.
     * @param grabOffset The pointer's offset from the dragged row's top-left at press time.
     * @param rowHeightPx The dragged row's height.
     */
    fun start(item: DraggedItem, pos: Offset, grabOffset: Offset, rowHeightPx: Int) {
        this.grabOffset = grabOffset
        overlay.item = item
        overlay.size = IntSize(hostBoundsState.value.width.roundToInt(), rowHeightPx)
        draggedFeedIdState.value = (item as? DraggedItem.Feed)?.feedId
        move(pos)
    }

    /**
     * Reports the pointer moving to [pos] (local to the drag host): repositions the ghost, feeds
     * the auto-scroll loop, and re-resolves the insertion boundary / hovered tag.
     */
    fun move(pos: Offset) {
        if (overlay.item == null) return
        lastPosition = pos
        val bounds = hostBoundsState.value
        overlay.positionInRoot = Offset(bounds.left, bounds.top) + pos - grabOffset
        dragPointerYState.value = bounds.top + pos.y
        updateHover(pos.y)
    }

    /**
     * Re-resolves the insertion boundary / hovered tag at the last reported pointer position.
     * Called after each auto-scroll step: the rows slide under a motionless pointer, so the
     * highlight has to be recomputed even though no pointer event arrived.
     */
    fun refreshHover() {
        if (overlay.item == null) return
        updateHover(lastPosition.y)
    }

    /**
     * Updates the active drop target for a pointer at [localY].
     *
     * Clears the active boundary and tag target when the pointer is outside a valid row.
     */
    private fun updateHover(localY: Float) {
        val item = overlay.item ?: return
        val dropIndex = dropIndexState.value
        val feedId = (item as? DraggedItem.Feed)?.feedId
        val draggedFolderId = (item as? DraggedItem.Folder)?.folderId
        val band = bandAt(localY) ?: run {
            activeBoundaryState.value = null
            hoveredAttachTagIdState.value = null
            return
        }
        val half = resolveRowHalf(localY, band)
        val rowKey = parseFeedListRowKey(band.key)
        hoveredAttachTagIdState.value = null
        activeBoundaryState.value = when {
            feedId != null -> when (rowKey) {
                is FeedListRowKey.Folder -> dropIndex.feedZoneBoundaryFor(rowKey.folderId)
                FeedListRowKey.NoFolderHeader -> dropIndex.feedZoneBoundaryFor(null)
                is FeedListRowKey.Feed -> if (half == RowHalf.TOP) {
                    DropBoundary.BeforeFeed(rowKey.feedId)
                } else {
                    dropIndex.belowBoundaryForFeed(rowKey.feedId)
                }
                is FeedListRowKey.Tag -> {
                    hoveredAttachTagIdState.value = rowKey.tagId
                    null
                }
                FeedListRowKey.Other -> null
            }
            draggedFolderId != null -> when (rowKey) {
                is FeedListRowKey.Folder -> when {
                    rowKey.folderId == draggedFolderId -> null
                    half == RowHalf.TOP -> DropBoundary.BeforeFolder(rowKey.folderId)
                    else -> dropIndex.belowBoundaryForFolder(rowKey.folderId)
                }
                is FeedListRowKey.Feed -> dropIndex.folderIdOfFeed[rowKey.feedId]
                    ?.takeIf { it != draggedFolderId }
                    ?.let(dropIndex::belowBoundaryForFolder)
                else -> null
            }
            else -> null
        }
    }

    /**
     * Applies the drop for a release at [pos] (local to the drag host) and ends the drag.
     *
     * @return `true` if the drop was valid and applied, `false` otherwise.
     */
    fun end(pos: Offset): Boolean {
        val item = overlay.item ?: return false
        val dropIndex = dropIndexState.value
        val band = bandAt(pos.y)
        val half = band?.let { resolveRowHalf(pos.y, it) }
        clear()
        if (band == null || half == null) return false
        val rowKey = parseFeedListRowKey(band.key)
        if (item is DraggedItem.Feed) {
            val feedId = item.feedId
            return when (rowKey) {
                is FeedListRowKey.Folder -> {
                    vm.moveFeed(feedId, rowKey.folderId, dropIndex.firstFeedIdOfGroup[rowKey.folderId])
                    true
                }
                FeedListRowKey.NoFolderHeader -> {
                    vm.moveFeed(feedId, null, dropIndex.firstFeedIdOfGroup[null])
                    true
                }
                is FeedListRowKey.Feed -> {
                    val insertBeforeId = if (half == RowHalf.TOP) {
                        rowKey.feedId
                    } else {
                        dropIndex.nextFeedInGroup[rowKey.feedId]
                    }
                    vm.moveFeed(feedId, dropIndex.folderIdOfFeed[rowKey.feedId], insertBeforeId)
                    true
                }
                is FeedListRowKey.Tag -> {
                    vm.setFeedTag(feedId, rowKey.tagId, true)
                    true
                }
                FeedListRowKey.Other -> false
            }
        }
        val draggedFolderId = (item as DraggedItem.Folder).folderId
        return when (rowKey) {
            is FeedListRowKey.Folder -> {
                if (rowKey.folderId == draggedFolderId) return false
                val insertBeforeId = if (half == RowHalf.TOP) {
                    rowKey.folderId
                } else {
                    dropIndex.nextFolderId[rowKey.folderId]
                }
                vm.reorderFolders(draggedFolderId, insertBeforeId)
                true
            }
            is FeedListRowKey.Feed -> {
                val ownerFolderId = dropIndex.folderIdOfFeed[rowKey.feedId] ?: return false
                if (ownerFolderId == draggedFolderId) return false
                vm.reorderFolders(draggedFolderId, dropIndex.nextFolderId[ownerFolderId])
                true
            }
            else -> false
        }
    }

    /** Aborts the drag without committing anything (Escape, focus loss, composition teardown). */
    fun cancel() {
        clear()
    }

    /** Drops every piece of drag state: the ghost, the insertion line, the tag highlight, auto-scroll. */
    private fun clear() {
        overlay.item = null
        draggedFeedIdState.value = null
        activeBoundaryState.value = null
        hoveredAttachTagIdState.value = null
        dragPointerYState.value = null
    }
}

/**
 * Remembers the pane's [FeedListDragController]. [titleOf] is captured through
 * [rememberUpdatedState] so the permanently-remembered controller always resolves titles against
 * the current feed/folder lists rather than the ones present when it was first created.
 */
@Composable
internal fun rememberFeedListDragController(
    vm: HomeViewModel,
    listState: LazyListState,
    hostBoundsState: State<Rect>,
    dropIndexState: State<FeedListDropIndex>,
    activeBoundaryState: MutableState<DropBoundary?>,
    draggedFeedIdState: MutableState<String?>,
    hoveredAttachTagIdState: MutableState<String?>,
    dragPointerYState: MutableState<Float?>,
    overlay: FeedDragOverlayState,
    titleOf: (FeedListDragSourceKey) -> String,
): FeedListDragController {
    val titleOfState = rememberUpdatedState(titleOf)
    return remember(vm, listState) {
        FeedListDragController(
            vm = vm,
            listState = listState,
            hostBoundsState = hostBoundsState,
            dropIndexState = dropIndexState,
            activeBoundaryState = activeBoundaryState,
            draggedFeedIdState = draggedFeedIdState,
            hoveredAttachTagIdState = hoveredAttachTagIdState,
            dragPointerYState = dragPointerYState,
            titleOfState = titleOfState,
            overlay = overlay,
        )
    }
}

/**
 * Draws a legible drag chip: an opaque rounded-rect (icon + title) floating under the pointer.
 *
 * Everything is painted directly rather than captured from composition, so the chip is legible over
 * arbitrary content — the row's own background is transparent whenever unselected, which is exactly
 * what made the platform drag-shadow snapshot this replaced unreadable. `icon` is a plain [Painter]
 * (not a `@Composable`), so a raw `@Composable` like Coil's `AsyncImage` can't be embedded here.
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
 * Resolves everything [drawDragPreviewChip] needs. Only [icon]/[iconTint] differ between a feed
 * chip (the same fallback icon [FeedAvatar][FeedListRowParts.kt] uses when a favicon isn't
 * available) and a folder chip, so both go through this one setup rather than duplicating it.
 */
@Composable
private fun rememberFeedDragDecoration(
    title: String,
    icon: Painter,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
): DrawScope.() -> Unit {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyLarge
    val textColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    return {
        drawDragPreviewChip(title, textMeasurer, textStyle, textColor, icon, iconTint, backgroundColor, borderColor)
    }
}

/**
 * The floating drag chip. Hosted by `HomeScreen`'s root `Box` (last child, so it paints above every
 * pane) rather than by the feed pane, so the chip can travel across the whole window.
 *
 * The chip's position is read inside [Modifier.offset]'s layout-phase lambda, so a pointer move
 * repositions it without recomposing anything — the only recompositions per gesture are the ghost
 * appearing and disappearing.
 */
@Composable
internal fun FeedDragGhost(state: FeedDragOverlayState) {
    // Resolved up front, outside the `state.item == null` early return: painterResource loads
    // asynchronously, so resolving it only once a drag starts would paint the chip's first frame
    // without its icon.
    val feedIcon = painterResource(KeryxIcons.PublicFilled)
    val folderIcon = painterResource(KeryxIcons.Folder)
    // Deliberately unsized (0x0 while idle, chip-sized while dragging) rather than fillMaxSize: it
    // sits above every pane, and an overlay that filled the window would be a full-window node in
    // every hit test underneath it. Its own top-left is all that's needed — it converts the ghost's
    // root-space position into this container's local space.
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.onGloballyPositioned { originInRoot = it.positionInRoot() }) {
        val item = state.item ?: return@Box
        val decoration = when (item) {
            is DraggedItem.Feed -> rememberFeedDragDecoration(item.title, feedIcon)
            is DraggedItem.Folder -> rememberFeedDragDecoration(
                title = item.title,
                icon = folderIcon,
                iconTint = MaterialTheme.colorScheme.primary,
            )
        }
        val density = LocalDensity.current
        val size = state.size
        Box(
            Modifier
                .testTag(FEED_DRAG_GHOST_TEST_TAG)
                .offset {
                    val local = state.positionInRoot - originInRoot
                    IntOffset(local.x.roundToInt(), local.y.roundToInt())
                }
                .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                .drawBehind(decoration),
        )
    }
}

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.presentation.home.DropBoundary
import works.merc.keryx.app.presentation.home.FeedListDropIndex
import works.merc.keryx.app.presentation.home.FeedListDraggedItem
import works.merc.keryx.app.presentation.home.FeedListDropAction
import works.merc.keryx.app.presentation.home.FeedListDropTarget
import works.merc.keryx.app.presentation.home.FeedListRowHalf
import works.merc.keryx.app.presentation.home.resolveFeedListDropAction
import works.merc.keryx.app.presentation.home.resolveFeedListDropHighlight

/** Test tag on [FeedDragGhost]'s inner chip `Box`, present only while a drag is in progress —
 * `FeedListDragTest` asserts on its presence/absence to verify the ghost's lifecycle. */
internal const val FEED_DRAG_GHOST_TEST_TAG = "feed-drag-ghost"

/** What a feed-list drag is currently carrying. [title] is only used to label the drag ghost. */
internal sealed interface DraggedItem {
    val title: String

    data class Feed(val feedId: String, override val title: String) : DraggedItem
    data class Folder(val folderId: String, override val title: String) : DraggedItem
}

/** Strips the display title Compose's own drag ghost needs, for the shared drop-resolution rules. */
private fun DraggedItem.toShared(): FeedListDraggedItem = when (this) {
    is DraggedItem.Feed -> FeedListDraggedItem.Feed(feedId)
    is DraggedItem.Folder -> FeedListDraggedItem.Folder(folderId)
}

/** Reduces a `LazyColumn` row key's parsed identity to the shared, key-format-free drop target. */
private fun FeedListRowKey.toDropTarget(): FeedListDropTarget = when (this) {
    is FeedListRowKey.Folder -> FeedListDropTarget.FolderHeader(folderId)
    FeedListRowKey.NoFolderHeader -> FeedListDropTarget.NoFolderHeader
    is FeedListRowKey.Feed -> FeedListDropTarget.FeedRow(feedId)
    is FeedListRowKey.Tag -> FeedListDropTarget.TagHeader(tagId)
    FeedListRowKey.Other -> FeedListDropTarget.Other
}

private fun RowHalf.toShared(): FeedListRowHalf = when (this) {
    RowHalf.TOP -> FeedListRowHalf.TOP
    RowHalf.BOTTOM -> FeedListRowHalf.BOTTOM
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

    /**
     * Whether the pointer is currently over a valid drop target for the dragged item. Mirrors
     * whether [FeedListDragController] resolved a non-null insertion boundary or hovered tag for
     * the current position — the same check [FeedListPane]'s row/tag highlighting already relies
     * on — so the ghost can show an "invalid here" cue over blank space, section headers, or a
     * folder hovering itself, instead of implying every position is droppable.
     */
    var hasValidTarget: Boolean by mutableStateOf(false)

    /** Installed by [FeedListDragController] so the drag can be aborted from outside the pane. */
    var onCancel: () -> Unit = {}

    /**
     * Cancels the active drag operation without committing its pending changes.
     *
     * @return `true` if a drag was active and canceled, `false` if no drag was active.
     */
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
 * @param lockHorizontal Whether the ghost stays at the drag host's left edge instead of following
 *   the pointer horizontally — the touch (M3) "lifted row" drag moves along the list's axis only.
 *   Drop resolution is unaffected: it only ever looks at the pointer's Y.
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
    private val lockHorizontal: Boolean = false,
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
     * Identifies the draggable feed or folder at the specified list position.
     *
     * @param localY The vertical position within the feed list.
     * @return The dragged item and its grab geometry, or `null` when the position is not draggable.
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
     * Updates the drag preview position and recalculates the current drop target.
     *
     * @param pos The pointer position in the drag host's local coordinates.
     */
    fun move(pos: Offset) {
        if (overlay.item == null) return
        lastPosition = pos
        val bounds = hostBoundsState.value
        val ghostX = if (lockHorizontal) bounds.left else bounds.left + pos.x - grabOffset.x
        overlay.positionInRoot = Offset(ghostX, bounds.top + pos.y - grabOffset.y)
        dragPointerYState.value = bounds.top + pos.y
        updateHover(pos)
    }

    /**
     * Re-resolves the insertion boundary / hovered tag at the last reported pointer position.
     * Called after each auto-scroll step: the rows slide under a motionless pointer, so the
     * highlight has to be recomputed even though no pointer event arrived.
     */
    fun refreshHover() {
        if (overlay.item == null) return
        updateHover(lastPosition)
    }

    /**
     * Determines whether a drag position lies within the host's horizontal bounds.
     *
     * @param pos The drag position in host-local coordinates.
     * @return `true` if the position is within the host's horizontal bounds, `false` otherwise.
     */
    private fun isWithinHost(pos: Offset): Boolean = pos.x in 0f..hostBoundsState.value.width

    /**
     * Updates the active drop target for a pointer at [pos] (local to the drag host).
     *
     * Clears the active boundary and tag target when the pointer is outside a valid row, or
     * outside the drag host's horizontal extent entirely (see [isWithinHost]), and mirrors the
     * result into [FeedDragOverlayState.hasValidTarget] so the ghost can show whether the current
     * position would actually accept a drop.
     */
    private fun updateHover(pos: Offset) {
        val item = overlay.item ?: return
        if (!isWithinHost(pos)) {
            activeBoundaryState.value = null
            hoveredAttachTagIdState.value = null
            overlay.hasValidTarget = false
            return
        }
        val band = bandAt(pos.y) ?: run {
            activeBoundaryState.value = null
            hoveredAttachTagIdState.value = null
            overlay.hasValidTarget = false
            return
        }
        val half = resolveRowHalf(pos.y, band).toShared()
        val target = parseFeedListRowKey(band.key).toDropTarget()
        val (boundary, tagId) = resolveFeedListDropHighlight(item.toShared(), target, half, dropIndexState.value)
        activeBoundaryState.value = boundary
        hoveredAttachTagIdState.value = tagId
        overlay.hasValidTarget = boundary != null || tagId != null
    }

    /**
     * Applies a valid drop at the release position and ends the drag.
     *
     * @param pos The release position in the drag host's local coordinates.
     * @return `true` if the drop was applied, `false` if the position or target was invalid.
     */
    fun end(pos: Offset): Boolean {
        val item = overlay.item ?: return false
        val band = if (isWithinHost(pos)) bandAt(pos.y) else null
        val half = band?.let { resolveRowHalf(pos.y, it) }
        clear()
        if (band == null || half == null) return false
        val target = parseFeedListRowKey(band.key).toDropTarget()
        val action = resolveFeedListDropAction(item.toShared(), target, half.toShared(), dropIndexState.value)
            ?: return false
        when (action) {
            is FeedListDropAction.MoveFeed -> vm.moveFeed(action.feedId, action.folderId, action.targetFeedId)
            is FeedListDropAction.AttachTag -> vm.setFeedTag(action.feedId, action.tagId, true)
            is FeedListDropAction.ReorderFolder -> vm.reorderFolders(action.draggedFolderId, action.targetFolderId)
        }
        return true
    }

    /** Aborts the drag without committing anything (Escape, focus loss, composition teardown). */
    fun cancel() {
        clear()
    }

    /** Drops every piece of drag state: the ghost, the insertion line, the tag highlight, auto-scroll. */
    private fun clear() {
        overlay.item = null
        overlay.hasValidTarget = false
        draggedFeedIdState.value = null
        activeBoundaryState.value = null
        hoveredAttachTagIdState.value = null
        dragPointerYState.value = null
    }
}

/**
 * Remembers a feed-list drag controller for the specified view model and list state.
 *
 * @param lockHorizontal See [FeedListDragController]'s parameter of the same name.
 * @param titleOf Resolves the current title for a draggable feed or folder.
 * @return The remembered feed-list drag controller.
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
    lockHorizontal: Boolean = false,
    titleOf: (FeedListDragSourceKey) -> String,
): FeedListDragController {
    val titleOfState = rememberUpdatedState(titleOf)
    return remember(vm, listState, lockHorizontal) {
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
            lockHorizontal = lockHorizontal,
        )
    }
}

/**
 * Opacity of the whole drag chip (applied to the ghost's layer, not baked into its paint colors —
 * see [FeedDragGhost]). Below full opacity so the row/highlight the pointer is currently over stays
 * visible underneath the chip instead of being fully hidden by it.
 */
private const val DRAG_GHOST_ALPHA = 0.75f

/** Opacity of the touch "lifted row" ghost while the pointer is over a position that cannot accept
 * the drop — dimmed rather than recoloured, since M3 has no error treatment for a drag. */
private const val LIFTED_ROW_INVALID_ALPHA = 0.6f

/** M3's dragged-state layer opacity, drawn in `onSurface` over the lifted row. */
private const val DRAGGED_STATE_LAYER_ALPHA = 0.16f

/** M3 elevation level 3, the resting elevation of a dragged list item. */
private val LIFTED_ROW_ELEVATION = 6.dp

/**
 * Draws a rounded drag-preview chip containing an icon and an ellipsized title.
 */
private fun DrawScope.drawDragPreviewChip(
    title: String,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    textColor: Color,
    icon: Painter,
    iconTint: Color,
    backgroundColor: Color,
    borderColor: Color?,
    cornerRadius: Dp = 6.dp,
    stateLayerColor: Color? = null,
    contentPadding: Dp = 8.dp,
) {
    val corner = CornerRadius(cornerRadius.toPx())
    drawRoundRect(color = backgroundColor, cornerRadius = corner)
    if (stateLayerColor != null) drawRoundRect(color = stateLayerColor, cornerRadius = corner)
    if (borderColor != null) drawRoundRect(color = borderColor, cornerRadius = corner, style = Stroke(1.dp.toPx()))

    val iconSize = 18.dp.toPx()
    val padding = contentPadding.toPx()
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
 * Creates the visual decoration for a feed or folder drag preview.
 *
 * @param isValidTarget Whether the preview is over a valid drop target.
 * @return A drawing decoration styled for the current target validity.
 */
@Composable
private fun rememberFeedDragDecoration(
    title: String,
    icon: Painter,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isValidTarget: Boolean = true,
): DrawScope.() -> Unit {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyLarge
    val errorColor = MaterialTheme.colorScheme.error
    val textColor = if (isValidTarget) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
    val backgroundColor = if (isValidTarget) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer
    val borderColor = if (isValidTarget) MaterialTheme.colorScheme.outlineVariant else errorColor
    val resolvedIconTint = if (isValidTarget) iconTint else errorColor
    return {
        drawDragPreviewChip(title, textMeasurer, textStyle, textColor, icon, resolvedIconTint, backgroundColor, borderColor)
    }
}

/**
 * The touch counterpart of [rememberFeedDragDecoration]: a Material 3 *dragged* list item — an
 * opaque `surfaceContainerHigh` row with a 16% `onSurface` state layer, no border and square
 * corners (the elevation shadow is applied by [FeedDragGhost]). An invalid position drops the
 * state layer instead of turning red; the ghost's own alpha does the rest.
 */
@Composable
private fun rememberLiftedRowDecoration(
    title: String,
    icon: Painter,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isValidTarget: Boolean = true,
): DrawScope.() -> Unit {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyLarge
    val textColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val stateLayerColor = textColor.copy(alpha = DRAGGED_STATE_LAYER_ALPHA).takeIf { isValidTarget }
    return {
        drawDragPreviewChip(
            title, textMeasurer, textStyle, textColor, icon, iconTint, backgroundColor,
            borderColor = null,
            cornerRadius = 0.dp,
            stateLayerColor = stateLayerColor,
            contentPadding = 16.dp,
        )
    }
}

/**
 * Displays the active feed or folder drag preview across the window.
 *
 * @param state The drag overlay state that provides the item, position, size, and target validity.
 * @param isTouchPrimary Whether to draw the touch "lifted row" (opaque, elevated, dimmed rather
 *   than red on an invalid position) instead of the desktop's translucent outlined chip. Overridable
 *   for tests only, like `feedListReorderDrag`'s parameter of the same name.
 */
@Composable
internal fun FeedDragGhost(
    state: FeedDragOverlayState,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
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
            is DraggedItem.Feed ->
                if (isTouchPrimary) {
                    rememberLiftedRowDecoration(item.title, feedIcon, isValidTarget = state.hasValidTarget)
                } else {
                    rememberFeedDragDecoration(item.title, feedIcon, isValidTarget = state.hasValidTarget)
                }
            is DraggedItem.Folder -> {
                val primary = MaterialTheme.colorScheme.primary
                if (isTouchPrimary) {
                    rememberLiftedRowDecoration(item.title, folderIcon, primary, state.hasValidTarget)
                } else {
                    rememberFeedDragDecoration(item.title, folderIcon, primary, state.hasValidTarget)
                }
            }
        }
        val ghostAlpha = when {
            !isTouchPrimary -> DRAG_GHOST_ALPHA
            state.hasValidTarget -> 1f
            else -> LIFTED_ROW_INVALID_ALPHA
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
                .alpha(ghostAlpha)
                .then(if (isTouchPrimary) Modifier.shadow(LIFTED_ROW_ELEVATION) else Modifier)
                .drawBehind(decoration),
        )
    }
}

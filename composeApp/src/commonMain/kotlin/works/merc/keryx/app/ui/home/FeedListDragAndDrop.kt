package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * How far an indented feed row's content is set in *past the marker column* (folder icon / tag
 * color dot) of the folder/tag row it nests under — the hierarchy step itself, as opposed to the
 * chevron's or marker's own width.
 */
private val FEED_ROW_HIERARCHY_STEP = 12.dp

/**
 * Where an indented feed row's own content starts, inside the row's [listRowSurface] margin — the
 * single value that indent is expressed in. `FeedRow` (nested in a folder) and `TagFeedRow` (nested
 * under a tag) apply it as their leading padding, and an [InsertionMarker] with
 * [InsertionMarker.indented] set takes its left edge *from here* rather than carrying its own
 * number, so the marker and the row title it lines up with can only ever move together.
 *
 * A plain constant — [FEED_LIST_ROW_START_PADDING] + [EXPAND_CHEVRON_SLOT] + [CHEVRON_MARKER_GAP] +
 * [FEED_ROW_HIERARCHY_STEP], all of them touch-density-independent — rather than a function of
 * `isTouchPrimary`. It used to be derived from the chevron slot's own (touch-dependent) width, so
 * that a fixed indent would not read as *outdenting* once a touch platform widened that slot to
 * M3's 48dp touch target; now that [EXPAND_CHEVRON_SLOT] itself no longer grows on a touch-primary
 * platform (the touch target grows via [Modifier.layoutAs] instead, with no layout footprint), the
 * indent has nothing left to track and is simply fixed.
 */
internal val FEED_ROW_INDENT: Dp = FEED_LIST_ROW_START_PADDING + EXPAND_CHEVRON_SLOT + CHEVRON_MARKER_GAP + FEED_ROW_HIERARCHY_STEP

/** @see FEED_ROW_INDENT */
internal fun feedRowIndent(): Dp = FEED_ROW_INDENT

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
 * nothing meaningful. Painting rather than laying out a real sibling `Box` also means the gap
 * between two rows can split exactly at its true midpoint rather than belonging entirely to
 * whichever row's own layout reserved it — see `listRowClickable`'s KDoc.
 *
 * Must sit *before* [listRowSurface] in the modifier chain: the marker is drawn in the margin
 * [listRowSurface]'s leading `padding` reserves, so applying it afterwards would inset it away —
 * and [listRowSurface]'s `decoration` slot is clipped to the inset rounded rect regardless.
 */
@Composable
internal fun Modifier.insertionMarkers(top: InsertionMarker? = null, bottom: InsertionMarker? = null): Modifier {
    val color = MaterialTheme.colorScheme.primary
    val horizontalMargin = listRowHorizontalMargin()
    val indent = feedRowIndent()
    val halfGuideThickness = LIST_ROW_GUIDE_THICKNESS / 2f
    val fullGuideThickness = LIST_ROW_GUIDE_THICKNESS
    return drawWithContent {
        drawContent()
        val halfThicknessPx = halfGuideThickness.toPx()
        val fullThicknessPx = fullGuideThickness.toPx()
        val horizontalMarginPx = horizontalMargin.toPx()
        val indentPx = indent.toPx()
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

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The horizontal inset a list row's highlight keeps from the pane edge — see [listRowSurface]. */
internal val LIST_ROW_HORIZONTAL_MARGIN = 8.dp

/**
 * Vertical margin between a list row's band and its painted highlight — the single value the
 * spec for the space between two rows is expressed in. Two adjacent rows each contribute one,
 * so:
 *
 * - The **visible gap between two rows is twice this** (2dp), the pane color showing between two
 *   highlights.
 * - The **hit boundary is the gap's midpoint**, because each row's `clickable` covers its own
 *   band including its own margin (see [listRowClickable]). Clicking anywhere in the gap therefore
 *   selects the *nearer* row and there is no dead strip that selects nothing.
 * - A drag insertion marker is exactly this thick per side, painted into the margin, so the two
 *   rows touching a boundary together fill the gap with one line centred on the very boundary the
 *   click resolves against — see `insertionMarkers` in `FeedListDragAndDrop.kt`.
 *
 * Note that clicking *precisely* on a highlight's edge still tends to select the neighbour, and
 * shrinking this value does not fix that (it was tried down to zero). That is macOS's own
 * behaviour, not this app's geometry — see `docs/known-issues.md`.
 */
internal val LIST_ROW_VERTICAL_MARGIN = 1.dp

/**
 * The click/drag hit area for a list row (feed/folder/tag/article) is the row's whole layout
 * band — full width, no outer-margin dead strip — while the *painted* selection highlight stays an
 * inset rounded rectangle (see [listRowSurface]). Splitting the two like this (rather than putting
 * `clickable` after the inset padding, which used to be this app's convention) is what makes every
 * point inside a row's bounds resolve to that row, including the outer margin and the four corners
 * a rounded [MaterialTheme.shapes] clip would otherwise carve out of the hit-test region.
 *
 * Apply this on the *same composable* whose chain also carries [listRowSurface] — never on an
 * ancestor of it. `Modifier.padding` (inside [listRowSurface]) creates a real dead zone for *any*
 * pointer input on an ancestor at that same screen position, not just for modifiers nested inside
 * the padding itself, so a `clickable` on a wrapping layout cannot be relied on to cover a child's
 * own padded content area (confirmed empirically). This is also why every list row is a single
 * composable with a single modifier chain — a wrapping `Column` used to exist around `FeedRow`
 * and `FolderGroupHeader` purely to lay out the drag insertion marker as a sibling `Box`; it is
 * gone now that the marker draws into this row's own [LIST_ROW_VERTICAL_MARGIN] instead of
 * claiming layout space (see `insertionMarkers` in `FeedListDragAndDrop.kt`).
 *
 * Pass `indication = null` deliberately here so the press feedback [listRowSurface] paints stays
 * confined to the inset highlight instead of flashing edge-to-edge; pair this with [listRowSurface]
 * on the same [interactionSource].
 */
internal fun Modifier.listRowClickable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(interactionSource = interactionSource, indication = null, onClick = onClick)

/**
 * The inset rounded-rectangle selection surface a list row paints inside its (wider) clickable band
 * — see [listRowClickable]. Applies the row's standard [LIST_ROW_HORIZONTAL_MARGIN] /
 * [LIST_ROW_VERTICAL_MARGIN] outer margin, clips to [MaterialTheme.shapes]' `small` radius, paints
 * [background], then [decoration] (e.g. a drop-target border), then the app's flat press feedback
 * via [interactionSource] — `null` for a row that carries no selection state of its own (e.g.
 * `NoFolderHeader`, which only ever shows a drop-target highlight and is never clicked).
 *
 * A drag insertion marker must be drawn *before* this in the chain (see `insertionMarkers` in
 * `FeedListDragAndDrop.kt`) — `decoration` and everything after it is clipped to the inset rounded
 * rect, so a marker routed through this function could never reach the band's own top/bottom edge.
 */
@Composable
internal fun Modifier.listRowSurface(
    background: Color,
    interactionSource: MutableInteractionSource? = null,
    decoration: Modifier = Modifier,
): Modifier = this
    .padding(horizontal = LIST_ROW_HORIZONTAL_MARGIN, vertical = LIST_ROW_VERTICAL_MARGIN)
    .clip(MaterialTheme.shapes.small)
    .background(background)
    .then(decoration)
    .let { if (interactionSource != null) it.indication(interactionSource, LocalIndication.current) else it }

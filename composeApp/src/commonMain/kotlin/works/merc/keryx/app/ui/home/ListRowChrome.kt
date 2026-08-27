package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The horizontal inset a list row's highlight keeps from the pane edge — see [listRowSurface]. */
internal val LIST_ROW_HORIZONTAL_MARGIN = 8.dp

/**
 * The mobile density floor for an interactive list row (feed / folder / tag / article) —
 * M3's own `NavigationDrawerItem` minimum height on a touch-primary platform, `0.dp` (no floor,
 * the row's intrinsic content height applies) everywhere else.
 *
 * Deliberately independent of [LIST_ROW_VERTICAL_MARGIN]/[LIST_ROW_HORIZONTAL_MARGIN]/
 * [LIST_ROW_GUIDE_THICKNESS] — those govern the *gap* between two rows and the drag insertion
 * marker's geometry, which must stay put regardless of a row's own content height (see
 * [LIST_ROW_VERTICAL_MARGIN]'s own KDoc). Apply via `Modifier.heightIn(min = listRowMinHeight())`
 * *after* a row's inner content padding, the same placement `ArticleRow`'s own `rowHeight` floor
 * already uses — never before it, or the padding would be measured against the still-unfloored
 * height. `NoFolderHeader` is deliberately excluded: it is a plain section label with no click
 * target or touch-sized child of its own, not an interactive row this floor is meant for.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default.
 */
internal fun listRowMinHeight(isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary): Dp =
    if (isTouchPrimary) 56.dp else 0.dp

/**
 * Total thickness of the horizontal guide line drawn at a boundary between two list rows —
 * currently only the drag insertion marker (`insertionMarkers` in `FeedListDragAndDrop.kt`).
 * The two rows touching a boundary paint **half of this each**, on their own side of it, so the
 * line is always centred on the very boundary a click resolves against.
 */
internal val LIST_ROW_GUIDE_THICKNESS = 2.dp

/**
 * Pane-colored clearance between a list row's painted highlight and the guide line, so the two
 * never touch. One sits on each side of the guide.
 *
 * It is part of [LIST_ROW_VERTICAL_MARGIN] and therefore reserved *whether or not* a guide is
 * currently drawn — a row's highlight must not move between resting and being dragged over.
 */
internal val LIST_ROW_GUIDE_CLEARANCE = 1.dp

/**
 * Vertical margin between a list row's band and its painted highlight — a **derived** value, not
 * a chosen one: exactly what one row has to give up to hold its half of the guide line plus its
 * clearance from it. Change [LIST_ROW_GUIDE_THICKNESS] / [LIST_ROW_GUIDE_CLEARANCE] and this
 * follows. Two adjacent rows each contribute one, so:
 *
 * - The **visible gap between two rows is twice this** (4dp), stacked as
 *   [LIST_ROW_GUIDE_CLEARANCE] + [LIST_ROW_GUIDE_THICKNESS] + [LIST_ROW_GUIDE_CLEARANCE]. With no
 *   guide drawn, all of it is pane color showing between the two highlights.
 * - The **hit boundary is the gap's midpoint**, which is also the guide line's centre, because
 *   each row's `clickable` covers its own band including its own margin (see [listRowClickable]).
 *   Clicking anywhere in the gap — clearance or guide — therefore selects the *nearer* row, and
 *   there is no dead strip that selects nothing.
 * - A drag insertion marker is half of [LIST_ROW_GUIDE_THICKNESS] per side, painted into the outer
 *   part of the margin, so the two rows touching a boundary together make one line centred on that
 *   boundary while each keeps its highlight [LIST_ROW_GUIDE_CLEARANCE] clear of it — see
 *   `insertionMarkers` in `FeedListDragAndDrop.kt`.
 *
 * Note that clicking *precisely* on a highlight's edge still tends to select the neighbour, and
 * shrinking this value does not fix that (it was tried down to zero). That is macOS's own
 * behaviour, not this app's geometry — see `docs/known-issues.md`.
 *
 * Declared *after* the two constants above deliberately: Kotlin initializes a file's top-level
 * properties in declaration order, so a forward reference here would silently read 0.dp.
 */
internal val LIST_ROW_VERTICAL_MARGIN = LIST_ROW_GUIDE_CLEARANCE + LIST_ROW_GUIDE_THICKNESS / 2f

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
 * gone now that the marker draws into the outer part of this row's own [LIST_ROW_VERTICAL_MARGIN]
 * instead of claiming layout space (see `insertionMarkers` in `FeedListDragAndDrop.kt`).
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
 * Which native row idiom a list row should follow — see [listRowSurface]'s own KDoc and the
 * `ui-guidelines` skill's "Platform-native list rows" section for the full rationale. Desktop's
 * `actual` ignores this entirely (its one, macOS-leaning row style applies regardless), so this
 * distinction is Android-only in practice.
 */
internal enum class ListRowKind {
    /** A feed/folder/tag row — Android's equivalent of a navigation-drawer item. */
    NavItem,

    /** An article row — Android's equivalent of a plain content list item. */
    ListItem,
}

/**
 * The selection surface a list row paints inside its (wider) clickable band — see
 * [listRowClickable]. Applies the row's standard [LIST_ROW_HORIZONTAL_MARGIN] /
 * [LIST_ROW_VERTICAL_MARGIN] outer margin, paints [background], then [decoration] (e.g. a
 * drop-target border), then the platform's own press feedback via [interactionSource] — `null` for
 * a row that carries no selection state of its own (e.g. `NoFolderHeader`, which only ever shows a
 * drop-target highlight and is never clicked). [kind] only matters on Android — desktop's `actual`
 * always applies the same macOS-leaning inset/rounded style regardless of it (see `ListRowKind`'s
 * own KDoc).
 *
 * A drag insertion marker must be drawn *before* this in the chain (see `insertionMarkers` in
 * `FeedListDragAndDrop.kt`) — `decoration` and everything after it is clipped on the platforms that
 * clip at all, so a marker routed through this function could never reach the band's own top/bottom
 * edge.
 *
 * @param extraBottomMargin Extra bottom margin beyond the standard [LIST_ROW_VERTICAL_MARGIN],
 *   for a row whose bottom insertion marker is unpaired with no possible partner (see
 *   `InsertionMarker.unpaired`) *and* has no later row to borrow margin from either — currently
 *   only `NoFolderHeader` when its group is empty, since it is always the last feed/folder row.
 *   [LIST_ROW_GUIDE_THICKNESS] `/ 2f` is exactly the extra space a paired boundary's *other* row
 *   would otherwise have contributed, so passing that keeps the guide the same 2dp-thick,
 *   1dp-clearance line every paired boundary has, without changing `insertionMarkers`' drawing
 *   code at all — only how much of this row's own margin the guide has to sit inside. Only
 *   `NavItem` rows are ever drag targets, so this only has an effect there.
 */
@Composable
internal expect fun Modifier.listRowSurface(
    background: Color,
    kind: ListRowKind,
    interactionSource: MutableInteractionSource? = null,
    decoration: Modifier = Modifier,
    extraBottomMargin: Dp = 0.dp,
): Modifier

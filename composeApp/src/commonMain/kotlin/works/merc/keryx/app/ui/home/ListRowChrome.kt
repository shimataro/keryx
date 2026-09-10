package works.merc.keryx.app.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The horizontal inset a list row's highlight keeps from the pane edge — see [listRowSurface].
 * `8dp` everywhere except a touch-primary platform, which uses M3's own `NavigationDrawerItem`
 * inset (`NavigationDrawerItemDefaults.ItemPadding`, `12dp`) instead — the same
 * per-platform-density split [listRowMinHeight] already follows.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default.
 */
internal fun listRowHorizontalMargin(isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary): Dp =
    if (isTouchPrimary) 12.dp else 8.dp

/** How long a pulse-triggered ripple holds its press state before releasing, so the indication has
 * time to visibly grow before it starts fading — an immediate press-then-release can render as
 * barely visible. */
private const val RETURN_RIPPLE_PRESS_HOLD_MS = 220L

/**
 * Plays a one-shot press+release into [this], mimicking a real tap so whichever indication is
 * bound to it (Android's M3 ripple via `listRowSurface`'s Android `actual`; desktop's
 * `FlatIndication`) shows its normal press feedback with no actual pointer input. Used to flash
 * the article row a user just navigated away from when they back out to it at `PaneLayout.Single`,
 * where the persistent selection highlight is suppressed by `LocalRowSelectionVisible` — see
 * `HomePaneLayout.kt`'s `shouldFlashReturnedArticle` and `ArticleListPane.kt`'s `ripplePulseFor`.
 */
internal suspend fun MutableInteractionSource.playPulseRipple() {
    val press = PressInteraction.Press(Offset.Zero)
    emit(press)
    try {
        delay(RETURN_RIPPLE_PRESS_HOLD_MS)
        emit(PressInteraction.Release(press))
    } catch (e: CancellationException) {
        withContext(NonCancellable) { emit(PressInteraction.Cancel(press)) }
        throw e
    }
}

/**
 * Plays a one-shot [playPulseRipple] on [interactionSource] whenever [ripplePulse] becomes
 * nonzero — including on the very first composition, which is what a pane remounting on a back
 * navigation at `PaneLayout.Single` relies on. `0` (the default at every call site) never plays
 * one.
 */
@Composable
internal fun PulseRippleEffect(ripplePulse: Int, interactionSource: MutableInteractionSource) {
    LaunchedEffect(ripplePulse) {
        if (ripplePulse != 0) interactionSource.playPulseRipple()
    }
}

/**
 * The mobile density floor for an interactive list row (feed / folder / tag / article) —
 * M3's own `NavigationDrawerItem` minimum height on a touch-primary platform, `0.dp` (no floor,
 * the row's intrinsic content height applies) everywhere else.
 *
 * **M3's 56dp is an outer height — content plus the row's own padding — so this must floor the
 * *padded* row, not its content.** Apply it as `Modifier.heightIn(min = listRowMinHeight())`,
 * placed *before* a row's inner content padding and immediately *after* [listRowSurface]:
 *
 * - Before the content padding, because after it the floor applies to the content alone and the
 *   padding stacks on top of it — making a row as much taller than 56dp as its own padding is
 *   thick, and making rows with differing content padding differing heights. This is the opposite
 *   placement from `ArticleRow`'s own `rowHeight` floor, which genuinely *is* a content height
 *   (derived from typography line heights by `rememberArticleRowMetrics()`) and therefore stays
 *   inside the padding; `ArticleRow` carries both floors, one on each side of it.
 * - After [listRowSurface], because that applies [LIST_ROW_VERTICAL_MARGIN] before it clips and
 *   paints: any further out, the floor would swallow that margin and leave the painted highlight
 *   `2 *` [LIST_ROW_VERTICAL_MARGIN] short of it. What this floors is the *highlight*, not the
 *   row's whole clickable band.
 *
 * Deliberately independent of [LIST_ROW_VERTICAL_MARGIN]/[listRowHorizontalMargin]/
 * [LIST_ROW_GUIDE_THICKNESS] — those govern the *gap* between two rows and the drag insertion
 * marker's geometry, which must stay put regardless of a row's own content height (see
 * [LIST_ROW_VERTICAL_MARGIN]'s own KDoc). It is a *minimum*, not a fixed height: a row whose
 * content genuinely needs more (a large `fontScale`, say) grows past it, exactly as M3 intends.
 * `NoFolderHeader` is deliberately excluded: it is a plain section label with no click target or
 * touch-sized child of its own, not an interactive row this floor is meant for.
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
 *
 * Built on `Modifier.selectable` rather than plain `clickable` so [selected] reaches accessibility
 * services as this row's own semantics (`Role`/checked-analogue state), not just as a painted
 * highlight from [selectionBackground] — the only way a screen-reader user can tell which row is
 * selected once [LocalRowSelectionVisible] hides that highlight (`PaneLayout.Single`, where the
 * highlight would be confusing with only one pane visible at a time). [selected] is therefore this
 * row's actual *logical* selection state, independent of whether the highlight is currently drawn.
 *
 * [Role.Tab] for every call site (feed/folder/tag/sidebar/article rows alike): Material 3's own
 * `NavigationDrawerItem` and `Tab` both report this same role for "pick one of a set, the pick
 * changes what's shown elsewhere" selection, which is exactly what every one of these rows does —
 * `ListRowChrome.android.kt`'s [ListRowKind.NavItem] style is itself modeled on `NavigationDrawerItem`.
 */
internal fun Modifier.listRowClickable(
    interactionSource: MutableInteractionSource,
    selected: Boolean,
    onClick: () -> Unit,
): Modifier = selectable(
    selected = selected,
    interactionSource = interactionSource,
    indication = null,
    role = Role.Tab,
    onClick = onClick,
)

/**
 * Which native row idiom a list row should follow — see [listRowSurface]'s own KDoc and the
 * `ui-guidelines` skill's "Platform-native list rows" section for the full rationale. Desktop's
 * `actual` ignores this entirely (its one, macOS-leaning row style applies regardless), so this
 * distinction is Android-only in practice. On Android, [NavItem] additionally depends on
 * [LocalFeedListInDrawer] for its *shape* specifically (see [listRowShape]) — "Android's
 * equivalent of a navigation-drawer item" below is literally true only while it actually is one.
 */
internal enum class ListRowKind {
    /** A feed/folder/tag row — Android's equivalent of a navigation-drawer item. */
    NavItem,

    /** An article row — Android's equivalent of a plain content list item. */
    ListItem,
}

/**
 * The selection surface a list row paints inside its (wider) clickable band — see
 * [listRowClickable]. Applies the row's standard [listRowHorizontalMargin] /
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

/**
 * The shape a list row's selection surface is clipped to (and that a drop-target border /
 * keyboard-focus outline traces via `listRowOutline` in `HomeCommon.kt`). Desktop's `actual`
 * ignores [kind] entirely — its one macOS-leaning row style applies to every row. Kept out of
 * [listRowSurface] as a value of its own so a decoration drawn *around* a row traces the very
 * shape the row is clipped to, instead of repeating a shape constant that could drift from it.
 *
 * On Android, the shape depends on more than just [kind]: an [ListRowKind.NavItem] row clips to a
 * full pill (M3's own `NavigationDrawerItem` shape) only while it's actually rendered as
 * navigation-drawer content ([LocalFeedListInDrawer]); at `PaneLayout.Triple`, where the same feed
 * list is a permanent pane sitting beside the article list rather than a drawer, it instead shares
 * the article list's own large-rounded-rectangle shape — two simultaneously visible panes read as
 * one design that way, which a drawer overlay (never on screen at the same time as the pane it
 * replaces) has no such need to match. [ListRowKind.ListItem] (article rows, which never render
 * inside the drawer) is unaffected by [LocalFeedListInDrawer] and always gets that same
 * large-rounded-rectangle shape.
 */
@Composable
internal expect fun listRowShape(kind: ListRowKind): Shape

/**
 * Whether the list row currently being laid out is rendered as feed-list navigation-drawer content
 * (`ModalNavigationDrawer`) rather than `PaneLayout.Triple`'s permanent on-screen sidebar pane —
 * see [listRowShape]'s own KDoc for what this changes. `FeedListPane` is the sole provider,
 * derived from its own `onSelectionAdvance` parameter's nullness (the same "is this a drawer"
 * signal its own KDoc and every other narrow-layout branch inside it already use — `null` means
 * `PaneLayout.Triple`); Android's `listRowShape` `actual` is the sole consumer. The default
 * `false` is correct for every row outside `FeedListPane`'s own subtree (in practice just
 * `ArticleRow`, whose [ListRowKind.ListItem] never consults this local anyway) — unlike
 * [listRowSurface]'s `kind` parameter, which deliberately has no default because it's set by many
 * unrelated call sites across both `FeedListPane` and `ArticleListPane` and forgetting it there
 * really could pick a silently wrong style, this local has exactly one provider and one consumer,
 * the same low-risk shape as [LocalRowSelectionVisible]/[LocalKeyboardEngaged] (`HomeCommon.kt`).
 */
internal val LocalFeedListInDrawer = staticCompositionLocalOf { false }

/** Width of a list row's outline decoration — both the drop-target border and the keyboard-focus
 * ring `listRowOutline` (`HomeCommon.kt`) draws share this one value. */
internal val ROW_OUTLINE_WIDTH = 2.dp

/**
 * The palette a selectable list row paints its selection from — resolved per platform, applied by
 * the shared `selectionBackground` / `selectionContentColorOrNull` / `rowOutlineColorOrNull` logic
 * in `HomeCommon.kt` (which keeps the [LocalRowSelectionVisible] gate and the [RowSelectionTone]
 * fan-out common to both).
 *
 * The focused/unfocused split is desktop's "which pane holds logical focus" axis: a selected row in
 * the non-focused pane dims so the user can see where their keyboard input will land. Android has
 * the same axis — a physical keyboard can be attached to an Android tablet — but represents it
 * differently: the selection color itself never changes with focus (M3's `NavigationDrawerItem`
 * keeps the same `secondaryContainer`/`onSecondaryContainer` pair whether or not the item holds
 * focus), and the focused pane's selected row instead gets a `secondary` outline via [focusRing]
 * (`listRowOutline` in `HomeCommon.kt`) — M3's own `FocusIndicatorColor` concept, which ships as a
 * token but has no Compose implementation yet, so Android's `actual` here supplies its own.
 *
 * @property focusedBackground Background of a selected row in the focused pane.
 * @property focusedContent Content color to pair with [focusedBackground]; `null` leaves each
 *   element at its own default color.
 * @property unfocusedBackground Background of a selected row whose pane is not focused.
 * @property unfocusedContent Content color to pair with [unfocusedBackground]; `null` leaves each
 *   element at its own default color.
 * @property echoBackground Background of a [RowSelectionTone.SECONDARY] row — another rendered
 *   instance of the same selected feed, which must read as an echo rather than a second selection.
 * @property focusRing Outline color for a selected row in the focused pane, or `null` on a platform
 *   that represents pane focus entirely through [focusedBackground]/[unfocusedBackground] dimming
 *   instead (desktop) rather than a separate outline (Android).
 */
internal data class RowSelectionColors(
    val focusedBackground: Color,
    val focusedContent: Color?,
    val unfocusedBackground: Color,
    val unfocusedContent: Color?,
    val echoBackground: Color,
    val focusRing: Color?,
)

/** This platform's list-row selection palette — see [RowSelectionColors]. */
@Composable
internal expect fun rowSelectionColors(): RowSelectionColors

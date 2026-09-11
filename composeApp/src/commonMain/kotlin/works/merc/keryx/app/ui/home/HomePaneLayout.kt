package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.DETAIL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.DUAL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.TRIPLE_PANE_MIN_WIDTH

/**
 * One of the three home panes. Also doubles as the navigation-stack depth cursor on a narrow
 * [PaneLayout] — [HomePane.ordinal] `+ 1` is the current depth (1 = feed list, 2 = article list,
 * 3 = article detail) — so drilling in/back is just moving `HomeScreen`'s existing `focusedPane`
 * state, with no separate depth state to keep in sync. At a narrow layout the feed list is a modal
 * navigation drawer rather than a pane (see [feedListIsDrawer]), so `focusedPane` never actually
 * becomes [HomePane.FeedList] there — depth 1 is unreachable outside [PaneLayout.Triple].
 */
enum class HomePane { FeedList, ArticleList, ArticleDetail }

/**
 * How many of the three home panes ([HomePane]) belong side by side at the current width. See
 * [TRIPLE_PANE_MIN_WIDTH] / [DUAL_PANE_MIN_WIDTH] for the thresholds this is derived from — they,
 * not an independent breakpoint, are this app's source of truth. [TRIPLE_PANE_MIN_WIDTH] is
 * summed from the two resizable panes' *default* widths (three panes squeezed onto their floors
 * are not worth showing as three — see its own KDoc); [DUAL_PANE_MIN_WIDTH] from the minimums,
 * since the two panes it governs are not sized from a preference at all — `NarrowPaneRow` gives
 * the article list a fixed width capped at its own default ([dualPaneArticleListWidth]) and hands
 * the reader everything left over, so that threshold only has to guarantee both floors fit.
 */
enum class PaneLayout { Single, Dual, Triple }

/** Resolves the [PaneLayout] that fits within [availableWidth]. */
fun paneLayoutFor(availableWidth: Dp): PaneLayout = when {
    availableWidth >= TRIPLE_PANE_MIN_WIDTH.dp -> PaneLayout.Triple
    availableWidth >= DUAL_PANE_MIN_WIDTH.dp -> PaneLayout.Dual
    else -> PaneLayout.Single
}

/**
 * Whether [layout] hosts the feed list as a modal navigation drawer rather than an on-screen pane.
 *
 * This is the seam a future iOS target branches at, and the reason it is a named function rather
 * than an inlined `layout != Triple`. A drawer is Android's idiom, not a universal narrow-layout
 * one: iOS/iPadOS collapse a NavigationSplitView's sidebar into a pushed navigation stack at a
 * compact width (Mail.app, NetNewsWire, Reeder), which is what this app did before the drawer and
 * what `git log` still holds — `visiblePanes` returning `[FeedList]` at Single depth 1, plus
 * `onEnterArticleList`, `FeedListPane`'s own notification bell, and the return ripple, all removed
 * alongside this. `paneLayoutFor` and `visiblePanes`' Triple/Dual cases carry over to iPadOS
 * unchanged (they map onto NavigationSplitView's three- and two-column modes); only Single's
 * presentation does not.
 */
fun feedListIsDrawer(layout: PaneLayout): Boolean = layout != PaneLayout.Triple

/**
 * The single [HomePane] that keyboard input (arrow-key pane navigation, J/K, F2/Delete) actually
 * targets right now, and therefore the single source of truth for which pane's selected row should
 * show the keyboard-focus ring/dimming (see `ui-guidelines`'s per-platform selection palette).
 *
 * An open feed-list drawer always wins over [focusedPane]: it is the topmost thing on screen while
 * open, regardless of which [HomePane] the navigation stack itself points at (the drawer isn't part
 * of that stack at all — see [HomePane]'s own KDoc). Every other case falls straight through to
 * [focusedPane]. This replaces the old `feedListActionAllowed(pane, drawerOpen)` +
 * `feedDrawerOpen`-guarded-`when(focusedPane)` duplication that used to be repeated at every one of
 * `HomeScreen`'s keyboard-routing and pane-focus call sites — each of those is exactly "is this the
 * pane [keyboardPaneFor] resolves to right now", which used to require re-deriving the drawer
 * precedence by hand at each call site (and was the source of a real bug: two call sites deriving
 * it independently could disagree, painting a focus ring on two panes at once — see
 * "Home's adaptive pane layout" in `docs/app-architecture.md`).
 */
fun keyboardPaneFor(focusedPane: HomePane, feedDrawerOpen: Boolean): HomePane =
    if (feedDrawerOpen) HomePane.FeedList else focusedPane

/** The widths the feed list and article list panes are laid out at, per [triplePaneWidths]. */
internal data class TriplePaneWidths(val feedWidth: Dp, val articleWidth: Dp)

/**
 * Fits the two persisted pane-width preferences into the width left over for them at
 * [PaneLayout.Triple] ([availableForPanes] — the window minus the dividers and the detail pane's
 * own minimum), **without either pane ever going below its own minimum**.
 *
 * Each pane's minimum ([FEED_LIST_PANE_MIN_WIDTH] / [ARTICLE_LIST_PANE_MIN_WIDTH]) is reserved
 * first, and only what each preference asks for *above* its minimum competes for whatever width is
 * left, proportionally. Scaling both preferences by one shared factor instead — as this used to —
 * gives away width in proportion to a pane's total size rather than to its slack, which pushes the
 * narrower pane below its own minimum as the squeeze tightens: back when [TRIPLE_PANE_MIN_WIDTH]
 * was derived from the pane minimums, the default 260dp/360dp preferences landed the feed pane at
 * ~176dp there, under its own 180dp floor.
 *
 * At [TRIPLE_PANE_MIN_WIDTH] exactly there is nothing to scale — that threshold is the sum of the
 * two defaults plus the reader's minimum, so an untouched pair of preferences fits it precisely.
 * The squeeze only arises once a preference has been dragged above its default: both panes at
 * their 480dp/600dp maximums (`FEED_LIST_PANE_MAX_WIDTH`/`ARTICLE_LIST_PANE_MAX_WIDTH`) ask for
 * 1080dp between them, which a window only a little above [TRIPLE_PANE_MIN_WIDTH] cannot give.
 */
internal fun triplePaneWidths(availableForPanes: Dp, feedPreference: Dp, articlePreference: Dp): TriplePaneWidths {
    val minFeed = FEED_LIST_PANE_MIN_WIDTH.dp
    val minArticle = ARTICLE_LIST_PANE_MIN_WIDTH.dp
    val extraAvailable = (availableForPanes - minFeed - minArticle).coerceAtLeast(0.dp)
    val feedExtra = (feedPreference - minFeed).coerceAtLeast(0.dp)
    val articleExtra = (articlePreference - minArticle).coerceAtLeast(0.dp)
    val extraTotal = feedExtra + articleExtra
    val extraScale = if (extraTotal > extraAvailable && extraTotal > 0.dp) extraAvailable / extraTotal else 1f
    return TriplePaneWidths(minFeed + feedExtra * extraScale, minArticle + articleExtra * extraScale)
}

/**
 * The width the article list pane is laid out at within [availableWidth] at [PaneLayout.Dual], the
 * article detail pane taking everything left over via `Modifier.weight(1f)` (see `NarrowPaneRow`).
 *
 * This is the [PaneLayout.Dual] analogue of [triplePaneWidths], and deliberately reuses the same
 * article-list default and minimum ([ARTICLE_LIST_PANE_WIDTH_DEFAULT] /
 * [ARTICLE_LIST_PANE_MIN_WIDTH]) that pane already gets at [PaneLayout.Triple] rather than
 * introducing a width constant of its own: it is the same pane showing the same rows, and the
 * asymmetry it produces — a list at a settled width, a reader that grows into whatever remains —
 * is the shape both layouts want. There is no persisted preference to honor here (a narrow layout
 * has no draggable divider to set one with), so the list's own default width *is* the preference.
 *
 * The reader can never be squeezed below its own floor by this: [paneLayoutFor] only resolves
 * [PaneLayout.Dual] at or above [DUAL_PANE_MIN_WIDTH] ([ARTICLE_LIST_PANE_MIN_WIDTH] +
 * [DETAIL_PANE_MIN_WIDTH] + one divider's width this layout doesn't spend — it draws none between
 * the two panes), so `availableWidth - DETAIL_PANE_MIN_WIDTH` already clears
 * [ARTICLE_LIST_PANE_MIN_WIDTH] before the clamp, and what the detail pane is left with
 * (`availableWidth -` this result) stays at or above [DETAIL_PANE_MIN_WIDTH] throughout the range.
 */
internal fun dualPaneArticleListWidth(availableWidth: Dp): Dp =
    (availableWidth - DETAIL_PANE_MIN_WIDTH.dp)
        .coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.dp, ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp)

/**
 * The panes to render for [layout], given the navigation stack's current [depth] (1..3, see
 * [HomePane]'s KDoc).
 *
 * [PaneLayout.Dual] always shows the same two panes — the article list and the article detail —
 * at every depth: the feed list is a modal navigation drawer there (see [feedListIsDrawer]), not a
 * third on-screen pane, so there is nothing to slide in or out as the stack's depth changes.
 * [PaneLayout.Single] shows exactly one of [HomePane.ArticleList] / [HomePane.ArticleDetail] (depth
 * 1 — the feed list — is unreachable at a narrow layout; a stale depth-1 [HomePane.FeedList] value
 * left over from an older version, or from [PaneLayout.Triple], resolves here to the article list,
 * same as depth 2). [HomePane.FeedList] never appears in this function's result at a narrow layout
 * — every caller that used to branch on its presence there can rely on that invariant instead.
 */
fun visiblePanes(layout: PaneLayout, depth: Int): List<HomePane> = when (layout) {
    PaneLayout.Triple -> listOf(HomePane.FeedList, HomePane.ArticleList, HomePane.ArticleDetail)
    PaneLayout.Dual -> listOf(HomePane.ArticleList, HomePane.ArticleDetail)
    PaneLayout.Single ->
        listOf(if (depth >= HomePane.ArticleDetail.ordinal + 1) HomePane.ArticleDetail else HomePane.ArticleList)
}

/**
 * Whether going back one step from [depth] at [layout] actually changes what's on screen.
 *
 * [PaneLayout.Dual] always shows the same two panes (see [visiblePanes]'s own KDoc) regardless of
 * depth, so a back press there never changes what's on screen — same as [PaneLayout.Triple], which
 * never has anywhere to go back to at all (all three panes are always shown). Both naturally
 * resolve to `false` here since [visiblePanes] returns the same list for every depth at either.
 * [PaneLayout.Single] is the only layout where a depth change is ever visible (depth 2 -> 3, the
 * article list to the article detail).
 *
 * `HomeScreen`'s `BackHandler`/`navigateUpEnabled` no longer read this directly — they go through
 * [homeBackAction], which also accounts for closing the expanded search bar. This stays the pane-only
 * half of that decision.
 */
fun canNavigateBack(layout: PaneLayout, depth: Int): Boolean =
    depth > 1 && visiblePanes(layout, depth - 1) != visiblePanes(layout, depth)

/** What a back action (system back, or a narrow pane's own back arrow) actually does. */
enum class HomeBackAction { None, CloseSearchBar, PopPane }

/**
 * Resolves what a back action should do at [depth]/[layout], given whether the expanded search
 * bar is currently open ([searchBarOpen] — see `HomeViewModel.searchBarVisible`).
 *
 * The search bar lives on `HomePane.ArticleList` with its content swapped out (see
 * `ArticleListPane`'s own KDoc), not a [HomePane] of its own — so closing it is a distinct action
 * from popping the pane stack, and takes priority over [canNavigateBack] whenever it applies. It
 * only applies where [HomePane.ArticleList] is actually among the panes on screen right now
 * ([visiblePanes]): at [PaneLayout.Single] that's depth 2 only (a search *result* opened into
 * [HomePane.ArticleDetail], depth 3, still pops one pane at a time, landing back on the search
 * screen with the bar still open); at [PaneLayout.Dual] it's every depth, since the article list
 * is always on screen there. [PaneLayout.Triple] is excluded entirely via [feedListIsDrawer] — the
 * field there stays in `FeedListPane`'s sidebar (always visible, never a bar to close), and back
 * navigation is disabled at every depth regardless.
 *
 * Falling through to [canNavigateBack] and then [HomeBackAction.None] is what lets a back press on
 * the article list at a narrow layout (depth 2, no search bar open) go unhandled — `HomeScreen`'s
 * `BackHandler` disables itself for `None`, so the platform's own back gesture/button takes over
 * (exiting the app on Android) rather than this codebase swallowing it with nowhere to go.
 */
fun homeBackAction(layout: PaneLayout, depth: Int, searchBarOpen: Boolean): HomeBackAction = when {
    searchBarOpen &&
        feedListIsDrawer(layout) &&
        HomePane.ArticleList in visiblePanes(layout, depth) -> HomeBackAction.CloseSearchBar
    canNavigateBack(layout, depth) -> HomeBackAction.PopPane
    else -> HomeBackAction.None
}

/**
 * Whether landing on [HomePane.ArticleList] via a back action should flash the row for the
 * article that was just being read (see `ListRowChrome.kt`'s ripple-pulse mechanism).
 * `true` only when backing out of [HomePane.ArticleDetail] specifically, and only at
 * [PaneLayout.Single], where `LocalRowSelectionVisible` (`HomeCommon.kt`) suppresses the
 * persistent selection highlight — at [PaneLayout.Dual]/[PaneLayout.Triple] the row's highlight
 * is already visible throughout the transition (the article list pane never leaves the screen),
 * so a one-shot flash would be redundant.
 */
fun shouldFlashReturnedArticle(layout: PaneLayout, fromPane: HomePane): Boolean =
    layout == PaneLayout.Single && fromPane == HomePane.ArticleDetail

/**
 * The [HomePane] a narrow layout should actually open on, given the last-focused pane [saved] from
 * local settings.
 *
 * At [PaneLayout.Triple], [saved] is returned unchanged — all three panes are on screen regardless,
 * so this only matters for a narrow layout. There the result is always [HomePane.ArticleList]:
 * the feed list is a drawer, not a destination [saved] could name any more (see
 * [feedListIsDrawer]'s own KDoc on the narrow layouts' history), and restoring straight into
 * [HomePane.ArticleDetail] would land the user on whatever article they last read with no list
 * around it and no context for how they got there — mirroring how a phone-shaped inbox app opens
 * (the list, not the last-read item).
 */
fun initialPaneFor(layout: PaneLayout, saved: HomePane): HomePane =
    if (layout == PaneLayout.Triple) saved else HomePane.ArticleList

/**
 * The [HomePane] to focus when a notification's `ShowFeedDetail` action selects a feed.
 *
 * At [PaneLayout.Triple], focusing [HomePane.FeedList] puts the selected feed's row on screen next
 * to its articles, which is what "select that feed in the feed list" means there. At a narrow
 * layout ([feedListIsDrawer]) the feed list is a drawer, not a destination this can focus — and
 * opening it unprompted would be a surprising side effect of a background notification — so this
 * advances to [HomePane.ArticleList] instead, without touching the drawer, showing the feed's own
 * articles titled with the feed's name.
 */
fun paneForFeedDetail(layout: PaneLayout): HomePane =
    if (feedListIsDrawer(layout)) HomePane.ArticleList else HomePane.FeedList

/**
 * Whether Home should open the feed-list drawer on entry. True only for the dead end this exists
 * to remove: a narrow layout (where the "+" button lives inside the drawer and is therefore
 * invisible while it's closed) with no feeds to show and no cloud account about to deliver any.
 *
 * A deliberate departure from M3's own "a drawer starts closed" guidance — but there is nothing on
 * the content behind it for the scrim to obscure in this state, so the departure costs nothing,
 * and it gives the drawer's own contents (All/Starred/folders/tags/Settings) a one-time
 * introduction the same way Gmail's drawer is the very first thing a new inbox shows.
 *
 * [cloudConfigured] gates a sync still in flight from being mistaken for "truly no feeds": a fresh
 * cloud connection can take a moment to deliver its first batch, and opening the drawer out from
 * under that would be noise, not help.
 */
fun shouldAutoOpenFeedDrawer(layout: PaneLayout, cloudConfigured: Boolean, hasAnyFeed: Boolean): Boolean =
    feedListIsDrawer(layout) && !cloudConfigured && !hasAnyFeed

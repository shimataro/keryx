package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.DUAL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.TRIPLE_PANE_MIN_WIDTH

/**
 * One of the three home panes. Also doubles as the navigation-stack depth cursor on a narrow
 * [PaneLayout] — [HomePane.ordinal] `+ 1` is the current depth (1 = feed list, 2 = article list,
 * 3 = article detail) — so drilling in/back is just moving `HomeScreen`'s existing `focusedPane`
 * state, with no separate depth state to keep in sync.
 */
enum class HomePane { FeedList, ArticleList, ArticleDetail }

/**
 * How many of the three home panes ([HomePane]) fit side by side at the current width. See
 * [TRIPLE_PANE_MIN_WIDTH] / [DUAL_PANE_MIN_WIDTH] for the thresholds this is derived from —
 * they, not an independent breakpoint, are this app's source of truth for "does it fit".
 */
enum class PaneLayout { Single, Dual, Triple }

/** Resolves the [PaneLayout] that fits within [availableWidth]. */
fun paneLayoutFor(availableWidth: Dp): PaneLayout = when {
    availableWidth >= TRIPLE_PANE_MIN_WIDTH.dp -> PaneLayout.Triple
    availableWidth >= DUAL_PANE_MIN_WIDTH.dp -> PaneLayout.Dual
    else -> PaneLayout.Single
}

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
 * gives away width in proportion to a pane's total size rather than to its slack, so at
 * [TRIPLE_PANE_MIN_WIDTH] exactly (where nothing is left over) the narrower pane was pushed below
 * its minimum: with the default 260dp/360dp preferences the feed pane landed at ~176dp, under its
 * own 180dp floor.
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
 * The panes to render for [layout], given the navigation stack's current [depth] (1..3, see
 * [HomePane]'s KDoc).
 *
 * [PaneLayout.Dual] is a two-pane sliding window over the three-deep stack, not a plain
 * `[depth-1, depth]` pair: the article list pane is always one of the two shown, so drilling from
 * the feed list into an article swaps the feed list pane out for the detail pane rather than
 * sliding the list itself out of view — the list's own on-screen position never moves.
 */
fun visiblePanes(layout: PaneLayout, depth: Int): List<HomePane> = when (layout) {
    PaneLayout.Triple -> listOf(HomePane.FeedList, HomePane.ArticleList, HomePane.ArticleDetail)
    PaneLayout.Dual -> if (depth >= 3) {
        listOf(HomePane.ArticleList, HomePane.ArticleDetail)
    } else {
        listOf(HomePane.FeedList, HomePane.ArticleList)
    }
    PaneLayout.Single -> listOf(HomePane.entries[(depth - 1).coerceIn(0, HomePane.entries.lastIndex)])
}

/**
 * Whether going back one step from [depth] at [layout] actually changes what's on screen.
 *
 * [PaneLayout.Dual]'s sliding window (see [visiblePanes]'s own KDoc) shows the same two panes at
 * depth 1 and depth 2 — the feed list and article list are both already visible, so "going back"
 * from the article list to the feed list is a no-op there, unlike at [PaneLayout.Single] where
 * each depth is its own screen. [PaneLayout.Triple] never has anywhere to go back to (all three
 * panes are always shown), which this naturally resolves to `false` since [visiblePanes] returns
 * the same list for every depth there.
 *
 * `HomeScreen`'s `BackHandler`/`navigateUpEnabled` no longer read this directly — they go through
 * [homeBackAction], which also accounts for exiting the Search scope. This stays the pane-only
 * half of that decision.
 */
fun canNavigateBack(layout: PaneLayout, depth: Int): Boolean =
    depth > 1 && visiblePanes(layout, depth - 1) != visiblePanes(layout, depth)

/** What a back action (system back, or a narrow pane's own back arrow) actually does. */
enum class HomeBackAction { None, ExitSearch, PopPane }

/**
 * Resolves what a back action should do at [depth]/[layout], given whether a Search-scope
 * snapshot is waiting to be restored ([searchScopeReturnPending] — see
 * `HomeViewModel.searchScopeEntry`).
 *
 * Search has no [HomePane] of its own — it's `HomePane.ArticleList` with its content swapped out
 * (see `ArticleListPane`'s own KDoc) — so exiting it is a distinct action from popping the pane
 * stack, and takes priority over [canNavigateBack] whenever it applies. It only applies at
 * [HomePane.ArticleList]'s own depth (2): a search *result* opened into
 * [HomePane.ArticleDetail] (depth 3) still pops one pane at a time, landing back on the search
 * screen with the scope intact. [PaneLayout.Triple] is excluded entirely — the field there stays
 * in `FeedListPane`'s sidebar, and back navigation is disabled at every depth regardless.
 */
fun homeBackAction(layout: PaneLayout, depth: Int, searchScopeReturnPending: Boolean): HomeBackAction = when {
    layout != PaneLayout.Triple &&
        searchScopeReturnPending &&
        depth == HomePane.ArticleList.ordinal + 1 -> HomeBackAction.ExitSearch
    canNavigateBack(layout, depth) -> HomeBackAction.PopPane
    else -> HomeBackAction.None
}

/**
 * Whether landing on [HomePane.ArticleList] via a back action should flash the row for the
 * article that was just being read (see `ArticleRowComponents.kt`'s ripple-pulse mechanism).
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
 * so this only matters for a narrow layout. There, restoring straight into [HomePane.ArticleDetail]
 * would land the user on whatever article they last read with no list around it and no context for
 * how they got there; clamping to [HomePane.ArticleList] instead mirrors how a phone-shaped inbox
 * app opens (the list, not the last-read item).
 */
fun initialPaneFor(layout: PaneLayout, saved: HomePane): HomePane =
    if (layout == PaneLayout.Triple) saved else minOf(saved, HomePane.ArticleList)

/**
 * The [HomePane] to focus when a notification's `ShowFeedDetail` action selects a feed.
 *
 * At [PaneLayout.Triple] — and at [PaneLayout.Dual]'s depth 1, where [visiblePanes] shows the feed
 * list and the article list together — focusing [HomePane.FeedList] puts the selected feed's row
 * on screen next to its articles, which is what "select that feed in the feed list" means there.
 * At [PaneLayout.Single] the feed list is a screen of its own, so focusing it would navigate
 * *back* from wherever the user was and show a list whose selection isn't even painted
 * (`LocalRowSelectionVisible` is `false` there — see `HomeScreen`). Advancing to
 * [HomePane.ArticleList] instead shows the feed's own articles, titled with the feed's name.
 */
fun paneForFeedDetail(layout: PaneLayout): HomePane =
    if (HomePane.ArticleList in visiblePanes(layout, HomePane.FeedList.ordinal + 1)) {
        HomePane.FeedList
    } else {
        HomePane.ArticleList
    }

package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.DUAL_PANE_MIN_WIDTH
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

package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.DETAIL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.DUAL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.PANE_DIVIDER_WIDTH
import works.merc.keryx.app.core.TRIPLE_PANE_MIN_WIDTH
import works.merc.keryx.app.core.WINDOW_DEFAULT_WIDTH
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePaneLayoutTest {

    // --- paneLayoutFor ---

    @Test
    fun paneLayoutForResolvesTripleAtOrAboveTheTripleThreshold() {
        assertEquals(PaneLayout.Triple, paneLayoutFor(TRIPLE_PANE_MIN_WIDTH.dp))
        assertEquals(PaneLayout.Triple, paneLayoutFor((TRIPLE_PANE_MIN_WIDTH + 1).dp))
    }

    @Test
    fun paneLayoutForResolvesTripleAtTheDesktopWindowMinimumWidth() {
        // Desktop's window can never narrow below WINDOW_MIN_WIDTH (see main.kt's
        // window.minimumSize), and TRIPLE_PANE_MIN_WIDTH's KDoc requires WINDOW_MIN_WIDTH to stay
        // `>=` it so the article reader's WebView (which must stay composed for the whole pane
        // lifetime — see ArticleDetailPane's KDoc / known-issues.md) is never unmounted by
        // Single/Dual on desktop. This pins that relationship directly, standing in for a full
        // HomeScreen() render (which needs HomeViewModel's whole dependency graph to compose at
        // all — see FeedListPaneTest.kt for how much Koin scaffolding even one pane needs).
        assertEquals(PaneLayout.Triple, paneLayoutFor(WINDOW_MIN_WIDTH.dp))
    }

    @Test
    fun paneLayoutForResolvesDualJustBelowTheTripleThreshold() {
        assertEquals(PaneLayout.Dual, paneLayoutFor((TRIPLE_PANE_MIN_WIDTH - 1).dp))
        assertEquals(PaneLayout.Dual, paneLayoutFor(DUAL_PANE_MIN_WIDTH.dp))
    }

    @Test
    fun paneLayoutForResolvesSingleBelowTheDualThreshold() {
        assertEquals(PaneLayout.Single, paneLayoutFor((DUAL_PANE_MIN_WIDTH - 1).dp))
        assertEquals(PaneLayout.Single, paneLayoutFor(0.dp))
    }

    // --- visiblePanes ---

    @Test
    fun visiblePanesAtTripleAlwaysShowsAllThreeRegardlessOfDepth() {
        for (depth in 1..3) {
            assertEquals(
                listOf(HomePane.FeedList, HomePane.ArticleList, HomePane.ArticleDetail),
                visiblePanes(PaneLayout.Triple, depth),
            )
        }
    }

    @Test
    fun visiblePanesAtSingleShowsExactlyTheDepthsOwnPane() {
        assertEquals(listOf(HomePane.FeedList), visiblePanes(PaneLayout.Single, 1))
        assertEquals(listOf(HomePane.ArticleList), visiblePanes(PaneLayout.Single, 2))
        assertEquals(listOf(HomePane.ArticleDetail), visiblePanes(PaneLayout.Single, 3))
    }

    @Test
    fun visiblePanesAtDualSlidesFromFeedListToDetailKeepingArticleListOnScreen() {
        // depth 1 and 2 both show feed list + article list — selecting a filter (staying at
        // depth 1 or moving to depth 2) doesn't change which panes are visible.
        assertEquals(listOf(HomePane.FeedList, HomePane.ArticleList), visiblePanes(PaneLayout.Dual, 1))
        assertEquals(listOf(HomePane.FeedList, HomePane.ArticleList), visiblePanes(PaneLayout.Dual, 2))
        // Only drilling into an article (depth 3) swaps the feed list out for the detail pane.
        assertEquals(listOf(HomePane.ArticleList, HomePane.ArticleDetail), visiblePanes(PaneLayout.Dual, 3))
    }

    // --- triplePaneWidths ---

    /** The width `HomeScreen`'s Triple branch has left for the two sized panes at [windowWidth]. */
    private fun availableForPanes(windowWidth: Int): Dp =
        (windowWidth - PANE_DIVIDER_WIDTH * 2 - DETAIL_PANE_MIN_WIDTH).dp

    @Test
    fun triplePaneWidthsNeverDropsBelowPaneMinimumsAtTheTripleThreshold() {
        // The exact width at which paneLayoutFor first resolves Triple: there is nothing left over
        // above the two minimums, so both panes must sit exactly on their own floor. Scaling both
        // preferences by one shared factor used to land the feed pane at ~176dp here, below its
        // own FEED_LIST_PANE_MIN_WIDTH.
        val widths = triplePaneWidths(
            availableForPanes(TRIPLE_PANE_MIN_WIDTH),
            FEED_LIST_PANE_WIDTH_DEFAULT.dp,
            ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp,
        )

        assertEquals(FEED_LIST_PANE_MIN_WIDTH.dp, widths.feedWidth)
        assertEquals(ARTICLE_LIST_PANE_MIN_WIDTH.dp, widths.articleWidth)
    }

    @Test
    fun triplePaneWidthsUsesFullPreferenceWhenThereIsSlack() {
        val widths = triplePaneWidths(
            availableForPanes(WINDOW_DEFAULT_WIDTH),
            FEED_LIST_PANE_WIDTH_DEFAULT.dp,
            ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp,
        )

        assertEquals(FEED_LIST_PANE_WIDTH_DEFAULT.dp, widths.feedWidth)
        assertEquals(ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp, widths.articleWidth)
    }

    @Test
    fun triplePaneWidthsDistributesExcessProportionallyAboveMinimums() {
        // Halfway between "both at their minimum" and "both at their preference": each pane keeps
        // its own minimum plus half of what its preference asked for on top of it.
        val minimumsTotal = (FEED_LIST_PANE_MIN_WIDTH + ARTICLE_LIST_PANE_MIN_WIDTH).dp
        val preferencesTotal = (FEED_LIST_PANE_WIDTH_DEFAULT + ARTICLE_LIST_PANE_WIDTH_DEFAULT).dp
        val available = (minimumsTotal + preferencesTotal) / 2f

        val widths = triplePaneWidths(available, FEED_LIST_PANE_WIDTH_DEFAULT.dp, ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp)

        assertTrue(widths.feedWidth >= FEED_LIST_PANE_MIN_WIDTH.dp, "feed pane below its minimum: ${widths.feedWidth}")
        assertTrue(widths.articleWidth >= ARTICLE_LIST_PANE_MIN_WIDTH.dp, "article pane below its minimum: ${widths.articleWidth}")
        assertEquals(available.value, (widths.feedWidth + widths.articleWidth).value, 0.01f)
        assertEquals(
            (FEED_LIST_PANE_MIN_WIDTH + (FEED_LIST_PANE_WIDTH_DEFAULT - FEED_LIST_PANE_MIN_WIDTH) / 2f),
            widths.feedWidth.value,
            0.01f,
        )
    }

    @Test
    fun triplePaneWidthsClampsToTheMinimumsWhenTheWindowIsNarrowerThanThey() {
        // Never reached on desktop (WINDOW_MIN_WIDTH >= TRIPLE_PANE_MIN_WIDTH), but a transient
        // pre-layout frame reports maxWidth == 0 — the result must still be a usable, non-negative
        // pair rather than shrinking below the minimums or going negative.
        val widths = triplePaneWidths(0.dp, FEED_LIST_PANE_WIDTH_DEFAULT.dp, ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp)

        assertEquals(FEED_LIST_PANE_MIN_WIDTH.dp, widths.feedWidth)
        assertEquals(ARTICLE_LIST_PANE_MIN_WIDTH.dp, widths.articleWidth)
    }

}

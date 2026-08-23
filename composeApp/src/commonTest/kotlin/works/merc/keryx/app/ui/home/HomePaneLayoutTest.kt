package works.merc.keryx.app.ui.home

import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.DUAL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.TRIPLE_PANE_MIN_WIDTH
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

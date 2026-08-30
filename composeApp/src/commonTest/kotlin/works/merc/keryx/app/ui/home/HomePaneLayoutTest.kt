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

    // --- canNavigateBack ---

    @Test
    fun canNavigateBackIsAlwaysFalseAtTriple() {
        // visiblePanes returns the same list for every depth at Triple, so there is never
        // anywhere to go back to — desktop's BackHandler must stay disabled at every depth.
        for (depth in 1..3) {
            assertEquals(false, canNavigateBack(PaneLayout.Triple, depth), "depth $depth")
        }
    }

    @Test
    fun canNavigateBackIsFalseAtDepthOneForEveryLayout() {
        // There is nothing before depth 1 to go back to, regardless of layout.
        for (layout in PaneLayout.entries) {
            assertEquals(false, canNavigateBack(layout, 1), layout.name)
        }
    }

    @Test
    fun canNavigateBackIsTrueAtSingleForEveryDeeperDepth() {
        // Single shows exactly one pane per depth, so stepping back always changes the screen.
        assertEquals(true, canNavigateBack(PaneLayout.Single, 2))
        assertEquals(true, canNavigateBack(PaneLayout.Single, 3))
    }

    @Test
    fun canNavigateBackIsFalseAtDualDepthTwoBecauseTheSlidingWindowDidNotMove() {
        // Dual shows [FeedList, ArticleList] at both depth 1 and depth 2 (see visiblePanes'
        // sliding-window KDoc) — going back from depth 2 to depth 1 changes nothing on screen, so
        // this must resolve to false (the bug this function exists to fix: HomeScreen's old
        // BackHandler intercepted this back press and produced no visible change).
        assertEquals(false, canNavigateBack(PaneLayout.Dual, 2))
    }

    @Test
    fun canNavigateBackIsTrueAtDualDepthThreeBecauseTheFeedListSlidesOut() {
        // Depth 3 swaps the feed list pane out for the detail pane (visiblePanes(Dual, 3) ==
        // [ArticleList, ArticleDetail]) — a real, visible change from depth 2.
        assertEquals(true, canNavigateBack(PaneLayout.Dual, 3))
    }

    // --- homeBackAction ---

    @Test
    fun homeBackActionIsAlwaysNoneAtTripleRegardlessOfAPendingSearchScope() {
        // Triple never has anywhere to go back to (see canNavigateBackIsAlwaysFalseAtTriple), and
        // exiting Search isn't assigned there either — the field stays in FeedListPane's sidebar.
        for (depth in 1..3) {
            assertEquals(HomeBackAction.None, homeBackAction(PaneLayout.Triple, depth, searchScopeReturnPending = true), "depth $depth")
            assertEquals(HomeBackAction.None, homeBackAction(PaneLayout.Triple, depth, searchScopeReturnPending = false), "depth $depth")
        }
    }

    @Test
    fun homeBackActionExitsSearchAtArticleListDepthWhenAScopeIsPending() {
        // Depth 2 is HomePane.ArticleList's own depth — where Search's content-swapped screen
        // lives (see homeBackAction's own KDoc). This is the fix for both bugs a pending scope used
        // to trip over: Single unconditionally popped to the feed list instead of exiting Search,
        // and Dual's back arrow was disabled outright (canNavigateBack(Dual, 2) == false).
        assertEquals(HomeBackAction.ExitSearch, homeBackAction(PaneLayout.Single, 2, searchScopeReturnPending = true))
        assertEquals(HomeBackAction.ExitSearch, homeBackAction(PaneLayout.Dual, 2, searchScopeReturnPending = true))
    }

    @Test
    fun homeBackActionStillPopsThePaneAtDepthThreeEvenWithASearchScopePending() {
        // A result opened from the search screen into ArticleDetail (depth 3) still pops one pane
        // at a time — landing back on the search screen with the scope intact, not exiting it in
        // one step.
        assertEquals(HomeBackAction.PopPane, homeBackAction(PaneLayout.Single, 3, searchScopeReturnPending = true))
        assertEquals(HomeBackAction.PopPane, homeBackAction(PaneLayout.Dual, 3, searchScopeReturnPending = true))
    }

    @Test
    fun homeBackActionMatchesCanNavigateBackWhenNoSearchScopeIsPending() {
        for (layout in PaneLayout.entries) {
            for (depth in 1..3) {
                val expected = if (canNavigateBack(layout, depth)) HomeBackAction.PopPane else HomeBackAction.None
                assertEquals(expected, homeBackAction(layout, depth, searchScopeReturnPending = false), "$layout depth $depth")
            }
        }
    }

    // --- paneForFeedDetail ---

    @Test
    fun paneForFeedDetailFocusesTheFeedListWhereTheArticleListIsVisibleBesideIt() {
        // Triple always shows all three panes; Dual's depth 1 shows [FeedList, ArticleList]. In
        // both, focusing the feed list puts the selected feed's row on screen next to its
        // articles — the original "select that feed in the feed list" behaviour.
        assertEquals(HomePane.FeedList, paneForFeedDetail(PaneLayout.Triple))
        assertEquals(HomePane.FeedList, paneForFeedDetail(PaneLayout.Dual))
    }

    @Test
    fun paneForFeedDetailAdvancesToTheArticleListAtSingle() {
        // Single shows one pane per depth, so focusing the feed list would navigate *backwards*
        // from wherever the user was — and onto a list whose selection highlight isn't even
        // painted (LocalRowSelectionVisible is false there).
        assertEquals(HomePane.ArticleList, paneForFeedDetail(PaneLayout.Single))
    }

    // --- initialPaneFor ---

    @Test
    fun initialPaneForReturnsTheSavedPaneUnchangedAtTriple() {
        // All three panes are always on screen at Triple, so restoring ArticleDetail there is
        // exactly the point (it's what shows the previously read article on desktop).
        for (pane in HomePane.entries) {
            assertEquals(pane, initialPaneFor(PaneLayout.Triple, pane))
        }
    }

    @Test
    fun initialPaneForClampsArticleDetailToArticleListAtANarrowLayout() {
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Single, HomePane.ArticleDetail))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Dual, HomePane.ArticleDetail))
    }

    @Test
    fun initialPaneForLeavesFeedListAndArticleListUnchangedAtANarrowLayout() {
        assertEquals(HomePane.FeedList, initialPaneFor(PaneLayout.Single, HomePane.FeedList))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Single, HomePane.ArticleList))
        assertEquals(HomePane.FeedList, initialPaneFor(PaneLayout.Dual, HomePane.FeedList))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Dual, HomePane.ArticleList))
    }

}

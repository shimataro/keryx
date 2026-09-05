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
    fun visiblePanesAtSingleShowsArticleListOrArticleDetailNeverFeedList() {
        // Depth 1 (a stale saved value from before the drawer existed, or from PaneLayout.Triple)
        // resolves the same as depth 2: the feed list is a drawer at this layout, not a pane, so
        // there is no depth-1 screen to distinguish it from.
        assertEquals(listOf(HomePane.ArticleList), visiblePanes(PaneLayout.Single, 1))
        assertEquals(listOf(HomePane.ArticleList), visiblePanes(PaneLayout.Single, 2))
        assertEquals(listOf(HomePane.ArticleDetail), visiblePanes(PaneLayout.Single, 3))
    }

    @Test
    fun visiblePanesAtDualAlwaysShowsArticleListAndDetailRegardlessOfDepth() {
        // The feed list is a drawer at Dual, not a third on-screen pane, so there is nothing to
        // slide in or out as the stack's depth changes — unlike before this layout had a drawer.
        for (depth in 1..3) {
            assertEquals(listOf(HomePane.ArticleList, HomePane.ArticleDetail), visiblePanes(PaneLayout.Dual, depth), "depth $depth")
        }
    }

    @Test
    fun visiblePanesNeverIncludesTheFeedListAtANarrowLayout() {
        // The invariant NarrowPaneRow's own require() depends on — see its KDoc.
        for (layout in listOf(PaneLayout.Single, PaneLayout.Dual)) {
            for (depth in 1..3) {
                assertTrue(HomePane.FeedList !in visiblePanes(layout, depth), "$layout depth $depth")
            }
        }
    }

    // --- feedListIsDrawer ---

    @Test
    fun feedListIsDrawerIsFalseOnlyAtTriple() {
        assertEquals(false, feedListIsDrawer(PaneLayout.Triple))
        assertEquals(true, feedListIsDrawer(PaneLayout.Dual))
        assertEquals(true, feedListIsDrawer(PaneLayout.Single))
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
    fun canNavigateBackAtSingleIsTrueOnlyFromArticleListToArticleDetail() {
        // depth 1->2 stays on the same pane (ArticleList, see visiblePanesAtSingle...'s own KDoc) —
        // going back would change nothing on screen. Only depth 2->3 (ArticleList -> ArticleDetail)
        // is a real, visible change.
        assertEquals(false, canNavigateBack(PaneLayout.Single, 2))
        assertEquals(true, canNavigateBack(PaneLayout.Single, 3))
    }

    @Test
    fun canNavigateBackIsAlwaysFalseAtDual() {
        // Dual shows the same two panes at every depth (see visiblePanes' own KDoc) — going back
        // never changes what's on screen there (the bug this function exists to fix: HomeScreen's
        // old BackHandler used to intercept such a back press and produce no visible change).
        for (depth in 1..3) {
            assertEquals(false, canNavigateBack(PaneLayout.Dual, depth), "depth $depth")
        }
    }

    // --- shouldFlashReturnedArticle ---

    @Test
    fun shouldFlashReturnedArticleIsTrueAtSingleBackingOutOfArticleDetail() {
        assertEquals(true, shouldFlashReturnedArticle(PaneLayout.Single, HomePane.ArticleDetail))
    }

    @Test
    fun shouldFlashReturnedArticleIsFalseAtSingleFromAnyOtherPane() {
        assertEquals(false, shouldFlashReturnedArticle(PaneLayout.Single, HomePane.ArticleList))
        assertEquals(false, shouldFlashReturnedArticle(PaneLayout.Single, HomePane.FeedList))
    }

    @Test
    fun shouldFlashReturnedArticleIsFalseAtDualAndTripleEvenFromArticleDetail() {
        // The article list pane's row highlight is already visible throughout the transition at
        // both of these layouts (LocalRowSelectionVisible stays true), so a one-shot flash would
        // be redundant.
        assertEquals(false, shouldFlashReturnedArticle(PaneLayout.Dual, HomePane.ArticleDetail))
        assertEquals(false, shouldFlashReturnedArticle(PaneLayout.Triple, HomePane.ArticleDetail))
    }

    // --- shouldFlashReturnedFeedListRow ---

    @Test
    fun shouldFlashReturnedFeedListRowIsTrueAtSingleBackingOutOfArticleList() {
        assertEquals(true, shouldFlashReturnedFeedListRow(PaneLayout.Single, HomePane.ArticleList))
    }

    @Test
    fun shouldFlashReturnedFeedListRowIsFalseAtSingleFromAnyOtherPane() {
        assertEquals(false, shouldFlashReturnedFeedListRow(PaneLayout.Single, HomePane.FeedList))
        assertEquals(false, shouldFlashReturnedFeedListRow(PaneLayout.Single, HomePane.ArticleDetail))
    }

    @Test
    fun shouldFlashReturnedFeedListRowIsFalseAtDualAndTripleEvenFromArticleList() {
        // At Dual, backing out of the article list toward the feed list is already a no-op
        // (canNavigateBack is false there — both panes are already on screen), and Triple never
        // has anywhere to back out to at all.
        assertEquals(false, shouldFlashReturnedFeedListRow(PaneLayout.Dual, HomePane.ArticleList))
        assertEquals(false, shouldFlashReturnedFeedListRow(PaneLayout.Triple, HomePane.ArticleList))
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
    fun homeBackActionStillPopsThePaneAtSingleDepthThreeEvenWithASearchScopePending() {
        // A result opened from the search screen into ArticleDetail (depth 3) still pops one pane
        // at a time at Single — landing back on the search screen with the scope intact, not
        // exiting it in one step. ArticleList isn't visible at this depth (visiblePanes(Single, 3)
        // == [ArticleDetail]), which is what keeps ExitSearch from taking priority here.
        assertEquals(HomeBackAction.PopPane, homeBackAction(PaneLayout.Single, 3, searchScopeReturnPending = true))
    }

    @Test
    fun homeBackActionExitsSearchAtDualDepthThreeBecauseTheArticleListIsAlwaysVisibleThere() {
        // Unlike Single, Dual keeps the article list on screen at every depth (visiblePanes(Dual,
        // 3) == [ArticleList, ArticleDetail]) — so exiting Search takes priority over popping the
        // pane at every depth there, not just depth 2.
        assertEquals(HomeBackAction.ExitSearch, homeBackAction(PaneLayout.Dual, 3, searchScopeReturnPending = true))
    }

    @Test
    fun backOnTheArticleListIsNotSwallowedSoTheOsCanExitTheApp() {
        // Single/Dual's article list (depth 2), with no Search scope pending: homeBackAction must
        // resolve to None so HomeScreen's BackHandler disables itself and the platform's own back
        // gesture/button takes over — on Android, exiting the app — rather than this codebase
        // swallowing the press with nowhere to go.
        assertEquals(HomeBackAction.None, homeBackAction(PaneLayout.Single, 2, searchScopeReturnPending = false))
        assertEquals(HomeBackAction.None, homeBackAction(PaneLayout.Dual, 2, searchScopeReturnPending = false))
        assertEquals(HomeBackAction.None, homeBackAction(PaneLayout.Dual, 3, searchScopeReturnPending = false))
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
    fun paneForFeedDetailFocusesTheFeedListAtTriple() {
        // Triple shows all three panes as permanent on-screen panes — focusing the feed list puts
        // the selected feed's row on screen next to its articles, the original "select that feed
        // in the feed list" behaviour.
        assertEquals(HomePane.FeedList, paneForFeedDetail(PaneLayout.Triple))
    }

    @Test
    fun paneForFeedDetailAdvancesToTheArticleListAtEveryNarrowLayout() {
        // The feed list is a drawer at both narrow layouts (feedListIsDrawer) — focusing it isn't
        // meaningful (there's no on-screen pane to select a row in), and opening the drawer
        // unprompted would be a surprising side effect of a background notification. Advancing to
        // the article list instead shows the feed's own articles, titled with the feed's name.
        assertEquals(HomePane.ArticleList, paneForFeedDetail(PaneLayout.Dual))
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
    fun initialPaneForAlwaysReturnsArticleListAtANarrowLayoutEvenWhenSavedIsFeedList() {
        // A saved HomePane.FeedList (left over from a version before the drawer existed) can no
        // longer be returned unchanged at a narrow layout — it isn't a destination this can name
        // any more (see feedListIsDrawer's own KDoc) — so it clamps to ArticleList exactly like an
        // already-narrow-valid ArticleList does.
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Single, HomePane.FeedList))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Single, HomePane.ArticleList))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Dual, HomePane.FeedList))
        assertEquals(HomePane.ArticleList, initialPaneFor(PaneLayout.Dual, HomePane.ArticleList))
    }

}

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

    @Test
    fun paneLayoutForResolvesTheWidthsRealDevicesReportAsIntended() {
        // The logical widths (physical pixels / density) this app actually meets, pinned so that a
        // change to any pane-width constant which moves one of them across a threshold has to be a
        // deliberate one.
        assertEquals(PaneLayout.Single, paneLayoutFor(411.dp), "phone, portrait")
        assertEquals(PaneLayout.Dual, paneLayoutFor(914.dp), "phone, landscape")
        // A 2560x1600 / 320dpi tablet held in portrait. Three panes do *fit* here — this used to
        // resolve Triple, squeezing all three onto their floors at once (214 / 290 / 280) — which
        // is exactly what summing TRIPLE_PANE_MIN_WIDTH from the defaults rather than the minimums
        // rules out. See that constant's KDoc.
        assertEquals(PaneLayout.Dual, paneLayoutFor(800.dp), "tablet, portrait")
        assertEquals(PaneLayout.Triple, paneLayoutFor(1280.dp), "tablet, landscape")
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
    fun triplePaneWidthsFitsBothDefaultsExactlyAtTheTripleThreshold() {
        // TRIPLE_PANE_MIN_WIDTH is the sum of the two defaults plus the reader's own minimum, so at
        // the exact width where paneLayoutFor first resolves Triple an untouched pair of
        // preferences fits precisely: nothing to scale down, and nothing left over either.
        val widths = triplePaneWidths(
            availableForPanes(TRIPLE_PANE_MIN_WIDTH),
            FEED_LIST_PANE_WIDTH_DEFAULT.dp,
            ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp,
        )

        assertEquals(FEED_LIST_PANE_WIDTH_DEFAULT.dp, widths.feedWidth)
        assertEquals(ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp, widths.articleWidth)
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

    // --- dualPaneArticleListWidth ---

    @Test
    fun dualPaneArticleListWidthLeavesTheReaderExactlyItsMinimumAtTheDualThreshold() {
        // The narrowest width paneLayoutFor ever resolves Dual at. The article list must still
        // clear its own floor, and what's left for the reader (which takes the remainder via
        // Modifier.weight(1f) — see NarrowPaneRow) must be exactly its own minimum, never less.
        val available = DUAL_PANE_MIN_WIDTH.dp
        val listWidth = dualPaneArticleListWidth(available)

        assertTrue(listWidth >= ARTICLE_LIST_PANE_MIN_WIDTH.dp, "article list below its minimum: $listWidth")
        assertEquals(DETAIL_PANE_MIN_WIDTH.dp, available - listWidth)
    }

    @Test
    fun dualPaneArticleListWidthCapsAtTheArticleListDefaultWhenThereIsSlack() {
        // Well above anything paneLayoutFor resolves Dual at (that's Triple's threshold), but a
        // fine "plenty of slack" input to this pure function: the list stops growing at its own
        // default and every further dp goes to the reader, exactly as the Triple branch does.
        assertEquals(ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp, dualPaneArticleListWidth(TRIPLE_PANE_MIN_WIDTH.dp))
    }

    @Test
    fun dualPaneArticleListWidthStaysWithinTheArticleListsOwnBoundsInBetween() {
        // Halfway between the Dual threshold and the width at which the list first reaches its
        // default — the interior of the range, where neither end of the clamp is in play.
        val listAtItsDefault = ARTICLE_LIST_PANE_WIDTH_DEFAULT + DETAIL_PANE_MIN_WIDTH
        val available = ((DUAL_PANE_MIN_WIDTH + listAtItsDefault) / 2).dp
        val listWidth = dualPaneArticleListWidth(available)

        assertTrue(listWidth >= ARTICLE_LIST_PANE_MIN_WIDTH.dp, "article list below its minimum: $listWidth")
        assertTrue(listWidth <= ARTICLE_LIST_PANE_WIDTH_DEFAULT.dp, "article list above its default: $listWidth")
        assertTrue(available - listWidth >= DETAIL_PANE_MIN_WIDTH.dp, "reader below its minimum: ${available - listWidth}")
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

    // --- shouldAutoOpenFeedDrawer ---

    @Test
    fun shouldAutoOpenFeedDrawerIsFalseAtTripleRegardlessOfFeedsOrCloud() {
        // feedListIsDrawer(Triple) == false: there is no drawer to open at all.
        for (cloudConfigured in listOf(true, false)) {
            for (hasAnyFeed in listOf(true, false)) {
                assertEquals(
                    false,
                    shouldAutoOpenFeedDrawer(PaneLayout.Triple, cloudConfigured, hasAnyFeed),
                    "cloudConfigured=$cloudConfigured hasAnyFeed=$hasAnyFeed",
                )
            }
        }
    }

    @Test
    fun shouldAutoOpenFeedDrawerIsFalseWhenCloudIsConfigured() {
        // A fresh cloud connection can take a moment to deliver its first batch — opening the
        // drawer out from under that sync would be noise, not help.
        assertEquals(false, shouldAutoOpenFeedDrawer(PaneLayout.Single, cloudConfigured = true, hasAnyFeed = false))
        assertEquals(false, shouldAutoOpenFeedDrawer(PaneLayout.Dual, cloudConfigured = true, hasAnyFeed = false))
    }

    @Test
    fun shouldAutoOpenFeedDrawerIsFalseWhenFeedsAlreadyExist() {
        assertEquals(false, shouldAutoOpenFeedDrawer(PaneLayout.Single, cloudConfigured = false, hasAnyFeed = true))
        assertEquals(false, shouldAutoOpenFeedDrawer(PaneLayout.Dual, cloudConfigured = false, hasAnyFeed = true))
    }

    @Test
    fun shouldAutoOpenFeedDrawerIsTrueOnlyAtANarrowLocalOnlyLayoutWithNoFeeds() {
        assertEquals(true, shouldAutoOpenFeedDrawer(PaneLayout.Single, cloudConfigured = false, hasAnyFeed = false))
        assertEquals(true, shouldAutoOpenFeedDrawer(PaneLayout.Dual, cloudConfigured = false, hasAnyFeed = false))
    }

    // --- keyboardPaneFor ---

    @Test
    fun keyboardPaneForReturnsFocusedPaneWhenTheDrawerIsClosed() {
        for (pane in HomePane.entries) {
            assertEquals(pane, keyboardPaneFor(pane, feedDrawerOpen = false), "pane=$pane")
        }
    }

    @Test
    fun keyboardPaneForReturnsFeedListWheneverTheDrawerIsOpenRegardlessOfFocusedPane() {
        // An open drawer is always the topmost thing on screen — it wins over whatever focusedPane
        // itself says, which at a narrow layout can still be ArticleList or ArticleDetail (the
        // drawer isn't part of the navigation stack focusedPane points into — see HomePane's KDoc).
        for (pane in HomePane.entries) {
            assertEquals(HomePane.FeedList, keyboardPaneFor(pane, feedDrawerOpen = true), "pane=$pane")
        }
    }

}

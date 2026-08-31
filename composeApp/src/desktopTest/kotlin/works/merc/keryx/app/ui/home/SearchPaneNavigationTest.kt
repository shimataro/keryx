package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end coverage for the bug this app's Android narrow layout used to have: typing into the
 * feed list's search field never advanced the navigation stack (so the results were never on
 * screen — see `FeedListPane.kt`'s old `KeryxTextField` block), and drilling into the article list
 * made the field disappear along with `FeedListPane` itself.
 *
 * This drives `FeedListPane` + `ArticleListPane` together exactly as `HomeScreen` wires them at
 * `PaneLayout.Single`, using a plain depth cursor in place of `HomeScreen`'s own
 * `focusedPane`/menu-bar machinery — `FeedListPaneTest.kt`'s own host is why only `FeedListPane`
 * needs Koin at all (`ArticleListPane` injects nothing).
 */
@OptIn(ExperimentalTestApi::class)
class SearchPaneNavigationTest {

    @Composable
    private fun NarrowHomeTestHost(vm: HomeViewModel, depth: Int, onDepthChange: (Int) -> Unit) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val layout = PaneLayout.Single
            val visible = visiblePanes(layout, depth)
            val searchScopeEntry by vm.searchScopeEntry.collectAsStateSafe(null)
            fun goBack() {
                when (homeBackAction(layout, depth, searchScopeEntry != null)) {
                    HomeBackAction.ExitSearch -> vm.exitSearchScope()?.let { onDepthChange(it.ordinal + 1) }
                    HomeBackAction.PopPane -> onDepthChange(depth - 1)
                    HomeBackAction.None -> {}
                }
            }
            NarrowPaneRow(visible, Modifier.size(320.dp, 600.dp)) { pane, paneModifier ->
                when (pane) {
                    HomePane.FeedList -> FeedListPane(
                        vm = vm,
                        focused = true,
                        dragOverlay = remember { FeedDragOverlayState() },
                        onActivated = {},
                        modifier = paneModifier,
                        onSelectionAdvance = { onDepthChange(2) },
                    )
                    HomePane.ArticleList -> ArticleListPane(
                        vm = vm,
                        focused = true,
                        onActivated = {},
                        modifier = paneModifier,
                        onSelectionAdvance = { onDepthChange(3) },
                        onNavigateUp = ::goBack,
                        navigateUpEnabled = homeBackAction(layout, depth, searchScopeEntry != null) != HomeBackAction.None,
                        onSearchClick = { vm.enterSearchScope(HomePane.ArticleList) },
                    )
                    // A plain stand-in for ArticleDetailPane: its own reader is a genuine
                    // native WebView this test harness cannot host (see
                    // ArticleDetailPaneTest.kt's own KDoc) — this test only cares about depth
                    // transitions, not the article body.
                    HomePane.ArticleDetail -> Box(
                        paneModifier.fillMaxSize().testTag(ARTICLE_DETAIL_STUB_TAG),
                    ) {
                        TooltipIconButton(tooltip = "Back", onClick = { goBack() }) {
                            KeryxIcon(KeryxIcons.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            }
        }
    }

    /**
     * Mirrors `HomeScreen`'s real [PaneLayout.Dual] wiring (its `focusedPane`/`goBack` state, plus
     * the same [NarrowPaneRow] host it lays the panes out with), using a genuine `focusedPane: HomePane` state instead of
     * [NarrowHomeTestHost]'s plain depth cursor — `homeBackAction` is driven off
     * `focusedPane.ordinal + 1`, exactly as `HomeScreen`'s own `goBack()`/`navigateUpEnabled` are,
     * which is what the regression below actually depends on: at `Dual`, both
     * [FeedListPane] and [ArticleListPane] are on screen together, so which one is "focused" is
     * independent of which panes are visible.
     */
    @Composable
    private fun DualHomeTestHost(vm: HomeViewModel, focusedPane: HomePane, onFocusedPaneChange: (HomePane) -> Unit) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val layout = PaneLayout.Dual
            val visible = visiblePanes(layout, focusedPane.ordinal + 1)
            val searchScopeEntry by vm.searchScopeEntry.collectAsStateSafe(null)
            fun setFocusedPane(pane: HomePane) {
                if (pane != focusedPane) onFocusedPaneChange(pane)
            }
            fun goBack() {
                when (homeBackAction(layout, focusedPane.ordinal + 1, searchScopeEntry != null)) {
                    HomeBackAction.ExitSearch -> vm.exitSearchScope()?.let { setFocusedPane(it) }
                    HomeBackAction.PopPane -> {
                        val previous = focusedPane.ordinal - 1
                        if (previous >= 0) setFocusedPane(HomePane.entries[previous])
                    }
                    HomeBackAction.None -> {}
                }
            }
            NarrowPaneRow(visible, Modifier.size(640.dp, 600.dp)) { pane, paneModifier ->
                when (pane) {
                    HomePane.FeedList -> FeedListPane(
                        vm = vm,
                        focused = focusedPane == HomePane.FeedList,
                        dragOverlay = remember { FeedDragOverlayState() },
                        onActivated = { setFocusedPane(HomePane.FeedList) },
                        modifier = paneModifier,
                        onSelectionAdvance = { setFocusedPane(HomePane.ArticleList) },
                    )
                    HomePane.ArticleList -> ArticleListPane(
                        vm = vm,
                        focused = focusedPane == HomePane.ArticleList,
                        onActivated = { setFocusedPane(HomePane.ArticleList) },
                        modifier = paneModifier,
                        onNavigateUp = ::goBack,
                        navigateUpEnabled = homeBackAction(layout, focusedPane.ordinal + 1, searchScopeEntry != null) != HomeBackAction.None,
                        onSearchClick = {
                            setFocusedPane(HomePane.ArticleList)
                            vm.enterSearchScope(HomePane.ArticleList)
                        },
                    )
                    HomePane.ArticleDetail -> Box(paneModifier.testTag(ARTICLE_DETAIL_STUB_TAG)) {}
                }
            }
        }
    }

    @Test
    fun theArticleListsOwnSearchIconAtDualLayoutFocusesItSoBackCanExitSearch() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            runDesktopComposeUiTest {
                var focusedPane by mutableStateOf(HomePane.FeedList)
                setContent { DualHomeTestHost(vm, focusedPane, { focusedPane = it }) }
                waitForIdle()

                assertEquals(ArticleFilter.All, vm.filter.value)

                // Bug precondition: the feed list, not the article list, is focused when the
                // article list's own search icon is tapped.
                onNodeWithContentDescription("記事を検索").performClick()
                waitForIdle()

                assertEquals(ArticleFilter.Search, vm.filter.value)
                // The fix: entering Search from this icon also focuses the article list, so
                // homeBackAction resolves to ExitSearch instead of None.
                assertEquals(HomePane.ArticleList, focusedPane)
                onNodeWithContentDescription("戻る").assertIsEnabled()

                onNodeWithContentDescription("戻る").performClick()
                waitForIdle()

                // Back actually exits Search: the filter is restored and focus lands back on the
                // article list (enterSearchScope's own returnPane), not the feed list.
                assertEquals(ArticleFilter.All, vm.filter.value)
                assertEquals(HomePane.ArticleList, focusedPane)
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun tappingTheCollapsedSearchBarNavigatesToTheResultsPaneWhoseFieldCarriesTheQuery() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            runDesktopComposeUiTest {
                var depth by mutableStateOf(1)
                setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
                waitForIdle()

                // Depth 1: only the collapsed bar is on screen — no editable field anywhere yet.
                onNode(hasSetTextAction()).assertDoesNotExist()

                onNodeWithText("記事を検索…").performClick()
                waitForIdle()

                // Depth 2: the navigation stack advanced, and the query field is now on the same
                // pane as the results — the exact bug this design fixes.
                assertEquals(2, depth)
                assertEquals(ArticleFilter.Search, vm.filter.value)
                onNode(hasSetTextAction()).assertIsDisplayed()
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theQueryAndTheFieldSurviveOpeningAResultAndComingBack() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            vm.setSearchQuery("kotlin")
            runDesktopComposeUiTest {
                var depth by mutableStateOf(2)
                setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
                waitForIdle()

                onNode(hasSetTextAction()).assertIsDisplayed()
                assertEquals("kotlin", vm.searchQuery.value)

                depth = 3
                waitForIdle()
                onNode(hasSetTextAction()).assertDoesNotExist()
                onNodeWithTag(ARTICLE_DETAIL_STUB_TAG).assertIsDisplayed()

                onNodeWithContentDescription("Back").performClick()
                waitForIdle()

                assertEquals(2, depth)
                assertEquals("kotlin", vm.searchQuery.value)
                onNode(hasSetTextAction()).assertIsDisplayed()
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun exactlyOnePaneConsumesASearchFocusRequest() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            runDesktopComposeUiTest {
                var depth by mutableStateOf(1)
                setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
                waitForIdle()

                onNodeWithText("記事を検索…").performClick()
                waitForIdle()

                // The latch (raised by the collapsed bar's own onClick, via requestSearchFocus())
                // must have been consumed by exactly the field that appeared at depth 2 — not left
                // dangling to steal focus at some later, unrelated field.
                assertEquals(2, depth)
                assertEquals(false, vm.pendingSearchFocus.value)
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun switchingAwayFromSearchAtDepthOneDropsThePendingFocusRequest() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            vm.selectFilter(ArticleFilter.Search)
            vm.requestSearchFocus()
            assertEquals(true, vm.pendingSearchFocus.value)

            // Navigating away before any field consumed the request (e.g. the user picked a
            // different quick filter before the search screen ever composed) must drop it.
            vm.selectFilter(ArticleFilter.All)

            assertEquals(false, vm.pendingSearchFocus.value)
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theArticleListsOwnSearchIconDoesNotAdvanceAndBackReturnsToTheSameArticleList() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            runDesktopComposeUiTest {
                var depth by mutableStateOf(2)
                setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
                waitForIdle()

                assertEquals(ArticleFilter.All, vm.filter.value)
                onNodeWithContentDescription("記事を検索").performClick()
                waitForIdle()

                // The regression this whole feature fixes: entering Search from the article list's
                // own search icon must not push a new depth (the field lives on this same pane), so
                // going back afterwards doesn't overshoot past the list the user was actually on.
                assertEquals(2, depth)
                assertEquals(ArticleFilter.Search, vm.filter.value)

                onNodeWithContentDescription("戻る").performClick()
                waitForIdle()

                assertEquals(2, depth)
                assertEquals(ArticleFilter.All, vm.filter.value)
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theCollapsedSearchBarsBackArrowRestoresTheFeedListAndKeepsTheQuery() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            runDesktopComposeUiTest {
                var depth by mutableStateOf(1)
                setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
                waitForIdle()

                onNodeWithText("記事を検索…").performClick()
                waitForIdle()
                assertEquals(2, depth)
                onNode(hasSetTextAction()).performTextInput("kotlin")
                waitForIdle()

                onNodeWithContentDescription("戻る").performClick()
                waitForIdle()

                // Back from the search screen returns to the feed list (where it was entered from,
                // not depth 1 as an incidental side effect of popping), with the filter restored —
                // not left on Search — and the query kept for the collapsed bar to show.
                assertEquals(1, depth)
                assertEquals(ArticleFilter.All, vm.filter.value)
                assertEquals("kotlin", vm.searchQuery.value)
            }
        } finally {
            fixture.close()
            driver.close()
        }
    }
}

private const val ARTICLE_DETAIL_STUB_TAG = "article-detail-stub"

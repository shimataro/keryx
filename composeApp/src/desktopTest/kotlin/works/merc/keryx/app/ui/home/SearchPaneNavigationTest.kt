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
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end coverage for the article list's own search entry point at a narrow `PaneLayout`
 * (`ArticleListTopBar`'s search icon, `onSearchClick`) — the feed list is a modal navigation
 * drawer at every narrow layout (see `HomePaneLayout.kt`'s `feedListIsDrawer`) and has no search
 * entry point of its own there, so this pane's own field is the only one that exists.
 *
 * This drives `ArticleListPane` alone with a plain depth cursor (`NarrowHomeTestHost`) or a
 * `focusedPane: HomePane` cursor (`DualHomeTestHost`), in place of `HomeScreen`'s own
 * `focusedPane`/menu-bar machinery — `FeedListPaneTest.kt`'s own host is where `FeedListPane`
 * (the drawer's content) is exercised on its own.
 *
 * [DualHomeTestHost] is also `PaneLayout.Dual`'s reference `focusedPane` harness for any test that
 * needs it, not just the search-scope tests below — see the article-selection tests near the
 * bottom of this file for a case that has nothing to do with Search at all.
 */
@OptIn(ExperimentalTestApi::class)
class SearchPaneNavigationTest {

    @Composable
    private fun NarrowHomeTestHost(vm: HomeViewModel, depth: Int, onDepthChange: (Int) -> Unit) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val layout = PaneLayout.Single
            val visible = visiblePanes(layout, depth)
            val searchBarVisible by vm.searchBarVisible.collectAsStateSafe(false)
            fun goBack() {
                when (homeBackAction(layout, depth, searchBarVisible)) {
                    HomeBackAction.ExitSearch -> { vm.setSearchBarVisible(false); onDepthChange(HomePane.ArticleList.ordinal + 1) }
                    HomeBackAction.PopPane -> onDepthChange(depth - 1)
                    HomeBackAction.None -> {}
                }
            }
            NarrowPaneRow(visible, 320.dp, Modifier.size(320.dp, 600.dp)) { pane, paneModifier ->
                when (pane) {
                    HomePane.FeedList -> error("The feed list is a drawer at PaneLayout.Single, never a NarrowPaneRow pane.")
                    HomePane.ArticleList -> ArticleListPane(
                        vm = vm,
                        focused = true,
                        onActivated = {},
                        modifier = paneModifier,
                        onSelectionAdvance = { onDepthChange(3) },
                        onOpenDrawer = {},
                        onExitSearch = ::goBack,
                        onSearchClick = { vm.setSearchBarVisible(true); vm.requestSearchFocus() },
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
     * `focusedPane.ordinal + 1`, exactly as `HomeScreen`'s own `goBack()` is, which is what the
     * regression below actually depends on: `focusedPane` can still land on `HomePane.FeedList`
     * (e.g. left-arrow keyboard nav — see `HomeScreen`'s own KDoc), even though the feed list
     * itself is a drawer rather than a pane at this layout, and which one is "focused" is
     * independent of which panes [visiblePanes] actually shows.
     */
    @Composable
    private fun DualHomeTestHost(vm: HomeViewModel, focusedPane: HomePane, onFocusedPaneChange: (HomePane) -> Unit) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val layout = PaneLayout.Dual
            val visible = visiblePanes(layout, focusedPane.ordinal + 1)
            val searchBarVisible by vm.searchBarVisible.collectAsStateSafe(false)
            fun setFocusedPane(pane: HomePane) {
                if (pane != focusedPane) onFocusedPaneChange(pane)
            }
            fun goBack() {
                when (homeBackAction(layout, focusedPane.ordinal + 1, searchBarVisible)) {
                    HomeBackAction.ExitSearch -> { vm.setSearchBarVisible(false); setFocusedPane(HomePane.ArticleList) }
                    HomeBackAction.PopPane -> {
                        val previous = focusedPane.ordinal - 1
                        if (previous >= 0) setFocusedPane(HomePane.entries[previous])
                    }
                    HomeBackAction.None -> {}
                }
            }
            NarrowPaneRow(visible, 640.dp, Modifier.size(640.dp, 600.dp)) { pane, paneModifier ->
                when (pane) {
                    HomePane.FeedList -> error("The feed list is a drawer at PaneLayout.Dual, never a NarrowPaneRow pane.")
                    HomePane.ArticleList -> ArticleListPane(
                        vm = vm,
                        focused = focusedPane == HomePane.ArticleList,
                        onActivated = { setFocusedPane(HomePane.ArticleList) },
                        modifier = paneModifier,
                        // Mirrors HomeScreen's own gate: a no-op at PaneLayout.Dual, since
                        // visiblePanes never changes with depth there (both panes are already on
                        // screen) — see ArticleListPane's own KDoc on this parameter.
                        onSelectionAdvance = { if (layout == PaneLayout.Single) setFocusedPane(HomePane.ArticleDetail) },
                        onOpenDrawer = {},
                        onExitSearch = ::goBack,
                        onSearchClick = {
                            setFocusedPane(HomePane.ArticleList)
                            vm.setSearchBarVisible(true)
                            vm.requestSearchFocus()
                        },
                    )
                    HomePane.ArticleDetail -> Box(paneModifier.testTag(ARTICLE_DETAIL_STUB_TAG)) {}
                }
            }
        }
    }

    @Test
    fun theArticleListsOwnSearchIconAtDualLayoutFocusesItSoBackCanExitSearch() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var focusedPane by mutableStateOf(HomePane.FeedList)
            setContent { DualHomeTestHost(vm, focusedPane, { focusedPane = it }) }
            waitForIdle()

            assertEquals(false, vm.searchBarVisible.value)

            // Bug precondition: focusedPane is HomePane.FeedList (e.g. left-arrow keyboard nav)
            // when the article list's own search icon is tapped — the feed list itself has no
            // on-screen row to focus at this layout (it's a drawer), but the cursor value alone
            // must not stop homeBackAction from resolving correctly afterward.
            onNodeWithContentDescription("記事を検索").performClick()
            waitForIdle()

            assertEquals(true, vm.searchBarVisible.value)
            // The fix: opening the bar from this icon also focuses the article list, so
            // homeBackAction resolves to ExitSearch instead of None.
            assertEquals(HomePane.ArticleList, focusedPane)
            onNodeWithContentDescription("戻る").assertIsEnabled()

            onNodeWithContentDescription("戻る").performClick()
            waitForIdle()

            // Back actually closes the bar, and focus lands back on the article list, not the
            // feed list.
            assertEquals(false, vm.searchBarVisible.value)
            assertEquals(HomePane.ArticleList, focusedPane)
        }
    }

    @Test
    fun theQueryAndTheFieldSurviveOpeningAResultAndComingBack() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            vm.setSearchBarVisible(true)
            vm.setSearchQuery("kotlin")
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
    }

    @Test
    fun closingTheSearchBarBeforeAnyFieldMountedDropsThePendingFocusRequest() {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            vm.setSearchBarVisible(true)
            vm.requestSearchFocus()
            assertEquals(true, vm.pendingSearchFocus.value)

            // Closing the bar before any field consumed the request (e.g. the user picked a
            // different quick filter before the search field ever composed) must drop it.
            vm.setSearchBarVisible(false)

            assertEquals(false, vm.pendingSearchFocus.value)
        } finally {
            runBlocking { fixture.close() }
        }
    }

    @Test
    fun theArticleListsOwnSearchIconDoesNotAdvanceAndBackReturnsToTheSameArticleList() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var depth by mutableStateOf(2)
            setContent { NarrowHomeTestHost(vm, depth, { depth = it }) }
            waitForIdle()

            assertEquals(false, vm.searchBarVisible.value)
            onNodeWithContentDescription("記事を検索").performClick()
            waitForIdle()

            // Opening the bar from the article list's own search icon must not push a new depth
            // (the field lives on this same pane), so going back afterwards doesn't overshoot
            // past the list the user was actually on.
            assertEquals(2, depth)
            assertEquals(true, vm.searchBarVisible.value)

            onNodeWithContentDescription("戻る").performClick()
            waitForIdle()

            assertEquals(2, depth)
            assertEquals(false, vm.searchBarVisible.value)
        }
    }

    /**
     * Regression test for a bug where selecting an article at `PaneLayout.Dual` advanced
     * `focusedPane` to `HomePane.ArticleDetail` (mirroring `PaneLayout.Single`'s own drill-in),
     * even though `visiblePanes` shows the same two panes at every depth there — the article list
     * pane then rendered `focused = false` on the very next frame, and ↑/↓ (routed through
     * `keyboardPaneFor`) stopped moving the just-tapped selection at all.
     */
    @Test
    fun articleRowSelectionAtDualLayoutDoesNotAdvanceFocusedPaneToArticleDetail() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("f1")
        db.insertArticleRow("a1", "f1", createdAt = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var focusedPane by mutableStateOf(HomePane.ArticleList)
            setContent { DualHomeTestHost(vm, focusedPane, { focusedPane = it }) }
            waitForIdle()

            onNodeWithText("Title a1").performClick()
            waitForIdle()

            assertEquals("a1", vm.selectedArticle.value?.id)
            assertEquals(HomePane.ArticleList, focusedPane)
        }
    }
}

private const val ARTICLE_DETAIL_STUB_TAG = "article-detail-stub"

/** Inserts an article row for the DB-backed test above (mirrors ArticleListPaneTest.kt's own). */
private fun KeryxDatabase.insertArticleRow(id: String, feedId: String, createdAt: Long) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
        summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
        is_read = 0L, read_at = null, is_starred = 0L, starred_at = null, cached_at = 0L,
        search_text = "", updated_at = 0L, created_at = createdAt,
    )
}

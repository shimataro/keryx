package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.ui.common.KeryxIcons
import kotlinx.coroutines.Dispatchers
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArticleListPaneTest {

    @Test
    fun scrollsOffscreenSelectionIntoFullView() = runDesktopComposeUiTest {
        val items = articles(30)
        lateinit var state: LazyListState
        var selected by mutableStateOf<ArticleListRow?>(null)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = selected?.id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        selected = items[25]
        waitForIdle()

        onNodeWithTag("article-a25").assertIsDisplayed()
        val info = state.layoutInfo.visibleItemsInfo.first { it.index == 25 }
        assertTrue(info.offset >= state.layoutInfo.viewportStartOffset)
        assertTrue(info.offset + info.size <= state.layoutInfo.viewportEndOffset)
    }

    /**
     * The pane can be composed with its list already positioned partway down — `NarrowPaneRow`
     * restores a saved scroll position as `rememberLazyListState`'s *initial* index/offset when the
     * pane comes back at a narrow layout. On that first frame `layoutInfo` is still empty, which
     * `scrollToIndexIfNeeded` would otherwise read as "the selection isn't rendered anywhere" and
     * answer with an animated scroll, yanking the restored position to put the selected row at the
     * top of a viewport it was already sitting inside.
     */
    @Test
    fun doesNotScrollAwayFromARestoredPositionThatAlreadyShowsTheSelection() = runDesktopComposeUiTest {
        val items = articles(60)
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = items[22].id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        onNodeWithTag("article-a22").assertIsDisplayed()
        assertEquals(20, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    /**
     * The `ArticleListPane`-level counterpart of the test above: a round trip through Search
     * restores `listState` to wherever it was left (see `ArticleListPane`'s own `listState`/
     * `wasSearch` handling), but the selected article — cleared and possibly re-restored to
     * something unrelated to where the list happens to be scrolled — is not guaranteed to land
     * inside that viewport the way a `NarrowPaneRow` remount's selection always does.
     * `preserveScrollPositionOnMount` exists for exactly this gap: it must suppress the "keep the
     * selection in view" scroll for this composable's first evaluation, even when the selected
     * article is nowhere near the restored viewport.
     */
    @Test
    fun preserveScrollPositionOnMountSuppressesTheInitialSelectionScroll() = runDesktopComposeUiTest {
        val items = articles(60)
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                // Far outside the restored viewport (rows ~20-25 at this size) — a plain mount would
                // animate-scroll all the way down to it.
                selectedId = items[55].id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
                preserveScrollPositionOnMount = true,
            )
        }
        waitForIdle()

        onNodeWithTag("article-a20").assertIsDisplayed()
        assertEquals(20, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    /**
     * The suppression above must only ever cover the mount's own first evaluation — a genuine
     * selection change afterward (keyboard navigation, picking a different article) still has to
     * scroll normally, or a search round trip would leave the list stuck refusing to follow the
     * selection for the rest of the pane's lifetime.
     */
    @Test
    fun preserveScrollPositionOnMountDoesNotSuppressALaterGenuineSelectionChange() = runDesktopComposeUiTest {
        val items = articles(60)
        lateinit var state: LazyListState
        var selected by mutableStateOf(items[55])

        setContent {
            state = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = selected.id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
                preserveScrollPositionOnMount = true,
            )
        }
        waitForIdle()
        // The mount's own evaluation was suppressed, exactly as above.
        assertEquals(20, state.firstVisibleItemIndex)

        // A later, genuine selection change must scroll normally.
        selected = items[59]
        waitForIdle()

        onNodeWithTag("article-a59").assertIsDisplayed()
    }

    @Test
    fun doesNotScrollWhenSelectionAlreadyFullyVisible() = runDesktopComposeUiTest {
        val items = articles(30)
        lateinit var state: LazyListState
        var selected by mutableStateOf<ArticleListRow?>(null)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = selected?.id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        val indexBefore = state.firstVisibleItemIndex
        val offsetBefore = state.firstVisibleItemScrollOffset

        selected = items[1]
        waitForIdle()

        assertEquals(indexBefore, state.firstVisibleItemIndex)
        assertEquals(offsetBefore, state.firstVisibleItemScrollOffset)
        onNodeWithTag("article-a1").assertIsDisplayed()
    }

    @Test
    fun allRowsHaveIdenticalHeightRegardlessOfTitleLengthOrStarredState() = runDesktopComposeUiTest {
        val items = listOf(
            article("short").copy(title = "Hi", is_starred = 0L),
            article("long").copy(title = "A very long article title ".repeat(6), is_starred = 1L),
            article("medium").copy(title = "A normal length title here", is_starred = 0L),
        )
        lateinit var state: LazyListState

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, 400.dp),
                listState = state,
            )
        }
        waitForIdle()

        val heights = state.layoutInfo.visibleItemsInfo.map { it.size }.toSet()
        assertEquals(1, heights.size, "article rows must have identical height regardless of title length/starred state: $heights")
    }

    @Test
    fun scrollsJustEnoughToRevealPartiallyClippedSelection() = runDesktopComposeUiTest(height = 2500) {
        val items = articles(30)
        lateinit var state: LazyListState
        var selected by mutableStateOf<ArticleListRow?>(null)
        // First frame: tall enough that a single row is never clipped, so we can measure it.
        var containerHeight by mutableStateOf(2000.dp)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = selected?.id,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = {},
                modifier = Modifier.size(360.dp, containerHeight),
                listState = state,
            )
        }
        waitForIdle()

        // Calibrate the total container height so the LazyColumn's own viewport (excluding the
        // filter-chip toolbar row + divider above it) is exactly 3.5 rows tall, so the 4th row
        // (index 3) straddles the bottom edge. Use the LazyListItemInfo size (as measured by the
        // list itself), not a semantics node bounds, since the testTag only covers the row content.
        val initialViewportPx = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
        val totalPxAt2000 = with(density) { 2000.dp.toPx() }
        val headerHeightPx = totalPxAt2000 - initialViewportPx
        val rowHeightPx = state.layoutInfo.visibleItemsInfo.first { it.index == 0 }.size
        val desiredViewportPx = rowHeightPx * 3.5f
        containerHeight = with(density) { (headerHeightPx + desiredViewportPx).toDp() }
        waitForIdle()

        val targetIndex = 3
        val straddling = state.layoutInfo.visibleItemsInfo.first { it.index == targetIndex }
        assertTrue(straddling.offset + straddling.size > state.layoutInfo.viewportEndOffset)

        val indexBefore = state.firstVisibleItemIndex
        selected = items[targetIndex]
        waitForIdle()

        val info = state.layoutInfo.visibleItemsInfo.first { it.index == targetIndex }
        assertTrue(info.offset >= state.layoutInfo.viewportStartOffset)
        assertTrue(info.offset + info.size <= state.layoutInfo.viewportEndOffset)
        assertTrue(state.firstVisibleItemIndex - indexBefore in 0..2)
    }

    @Test
    fun rightClickOnEmptyBackgroundActivatesPaneWithoutSelectingArticle() = runDesktopComposeUiTest {
        var activateCount = 0
        var selectCount = 0

        setContent {
            ArticleListPaneContent(
                articles = emptyList(),
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = { selectCount++ },
                onActivated = { activateCount++ },
                modifier = Modifier.size(360.dp, 400.dp),
            )
        }
        waitForIdle()

        onRoot().performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, activateCount)
        assertEquals(0, selectCount)
    }

    @Test
    fun rightClickOnArticleRowSelectsWithoutActivatingPaneFocus() = runDesktopComposeUiTest {
        val items = articles(3)
        var activateCount = 0
        var selectCount = 0

        setContent {
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selectedId = null,
                unreadOnly = false,
                onToggleUnreadOnly = {},
                onToggleSort = {},
                onMarkAllRead = {},
                onSelectArticle = { selectCount++ },
                onActivated = { activateCount++ },
                modifier = Modifier.size(360.dp, 400.dp),
            )
        }
        waitForIdle()

        onNodeWithTag("article-a0").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, selectCount)
        assertEquals(0, activateCount)
    }

    @Test
    fun articleListTopBarSortButtonDisabledInSearchModeDoesNotInvokeCallback() = runDesktopComposeUiTest {
        var toggleSortCount = 0

        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = { toggleSortCount++ },
                onMarkAllRead = {},
                sortEnabled = false,
            )
        }
        waitForIdle()

        // sortEnabled=false uses the "disabled while searching" tooltip text as its contentDescription.
        onNodeWithContentDescription("検索結果は関連度順で表示されます").assertIsNotEnabled()
        onNodeWithContentDescription("検索結果は関連度順で表示されます").performClick()
        waitForIdle()

        assertEquals(0, toggleSortCount)
    }

    @Test
    fun articleListTopBarSortButtonEnabledInNormalModeInvokesCallback() = runDesktopComposeUiTest {
        var toggleSortCount = 0

        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = { toggleSortCount++ },
                onMarkAllRead = {},
                sortEnabled = true,
            )
        }
        waitForIdle()

        // sortEnabled=true + newestFirst=true shows the "switch to oldest first" tooltip.
        onNodeWithContentDescription("古い順").assertIsEnabled()
        onNodeWithContentDescription("古い順").performClick()
        waitForIdle()

        assertEquals(1, toggleSortCount)
    }

    @Test
    fun sortDirectionIconDistinguishesTheTwoDirections() {
        // The regression this guards: both directions used to share one asset, flipped vertically at
        // the call site, which reads as a direction only on an icon set whose sort glyph has an arrow.
        assertNotEquals(
            sortDirectionIcon(newestFirst = true),
            sortDirectionIcon(newestFirst = false),
        )
    }

    @Test
    fun sortDirectionIconStaysDirectionalWhileDisabled() {
        // The disabled search scope must show the same directional glyph as the enabled state — only
        // TooltipIconButton's own dimmed styling should convey "disabled", not a different glyph.
        assertEquals(KeryxIcons.SortDescending, sortDirectionIcon(newestFirst = true))
        assertEquals(KeryxIcons.SortAscending, sortDirectionIcon(newestFirst = false))
    }

    @Test
    fun articleListTopBarUnreadOnlyEnabledInvokesCallback() = runDesktopComposeUiTest {
        var toggleUnreadCount = 0

        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = { toggleUnreadCount++ },
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
            )
        }
        waitForIdle()

        onNodeWithText("未読のみ").assertIsEnabled()
        onNodeWithText("未読のみ").performClick()
        waitForIdle()

        assertEquals(1, toggleUnreadCount)
    }

    /**
     * At [PaneLayout.Dual] the feed list slides in and out beside this pane as the user drills into
     * an article and back, which flips whether there is anywhere to navigate up to. The
     * back-button-and-title row must stay laid out across that flip — only the button's `enabled`
     * state may follow it — or the controls row below (and the whole article list under that) jumps
     * by the row's height every time. See the `ui-guidelines` skill, "Layout stability under state
     * changes": prefer disabled over hidden.
     */
    @Test
    fun articleListTopBarKeepsTheControlsRowInPlaceWhenNavigateUpBecomesUnavailable() = runDesktopComposeUiTest {
        var navigateUpEnabled by mutableStateOf(true)
        var backCount = 0

        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
                onNavigateUp = { backCount++ },
                navigateUpEnabled = navigateUpEnabled,
                title = "Feed a",
            )
        }
        waitForIdle()

        val boundsWhenEnabled = onNodeWithText("未読のみ").fetchSemanticsNode().boundsInRoot
        onNodeWithContentDescription("戻る").assertIsEnabled()
        onNodeWithContentDescription("戻る").performClick()
        waitForIdle()
        assertEquals(1, backCount)

        navigateUpEnabled = false
        waitForIdle()

        assertEquals(
            boundsWhenEnabled,
            onNodeWithText("未読のみ").fetchSemanticsNode().boundsInRoot,
            "the controls row must not move when navigating up becomes unavailable",
        )
        onNodeWithContentDescription("戻る").assertIsNotEnabled()
        onNodeWithContentDescription("戻る").performClick()
        waitForIdle()
        assertEquals(1, backCount, "a disabled back button must not invoke onNavigateUp")
    }

    /**
     * The row is omitted entirely only where it is conceptually never relevant — a
     * [PaneLayout.Triple] pane, which passes no `onNavigateUp` at all — not merely where navigating
     * up is temporarily unavailable (covered above).
     */
    @Test
    fun articleListTopBarOmitsTheNavigationRowWhenNoNavigateUpIsGivenAtAll() = runDesktopComposeUiTest {
        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
            )
        }
        waitForIdle()

        onNodeWithContentDescription("戻る").assertDoesNotExist()
        onNodeWithText("未読のみ").assertIsDisplayed()
    }

    @Test
    fun articleListPaneUnreadOnlyEnabledForStarredFilter() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {})
            }
            waitForIdle()

            vm.selectFilter(ArticleFilter.Starred)
            waitForIdle()
            onNodeWithText("未読のみ").assertIsEnabled()

            vm.selectFilter(ArticleFilter.All)
            waitForIdle()
            onNodeWithText("未読のみ").assertIsEnabled()
        }
    }

    /**
     * At a narrow layout, the query field itself moves into this pane's own top bar
     * (`KeryxExpandedSearchBar`) once the Search scope is active — see `SearchListPane`'s KDoc for
     * why it can't stay in `FeedListPane` there.
     */
    @Test
    fun articleListPaneShowsAnEditableSearchFieldAtANarrowLayoutInSearchScope() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {}, onNavigateUp = {}, navigateUpEnabled = true)
            }
            waitForIdle()

            vm.selectFilter(ArticleFilter.Search)
            waitForIdle()

            onNode(hasSetTextAction()).assertIsDisplayed()
            onNodeWithContentDescription("戻る").assertIsDisplayed()
        }
    }

    /**
     * Desktop regression guard: at [PaneLayout.Triple] (no `onNavigateUp`), `FeedListPane`'s own
     * field already covers search input, so `SearchListPane` must not render its own editable
     * field even while the Search scope is active.
     */
    @Test
    fun articleListPaneOmitsTheEditableSearchFieldAtTripleEvenInSearchScope() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {})
            }
            waitForIdle()

            vm.selectFilter(ArticleFilter.Search)
            waitForIdle()

            onNode(hasSetTextAction()).assertDoesNotExist()
        }
    }

    @Test
    fun articleListPaneNarrowSearchFieldReportsEditsUpstreamAndClears() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {}, onNavigateUp = {}, navigateUpEnabled = true)
            }
            waitForIdle()

            vm.selectFilter(ArticleFilter.Search)
            waitForIdle()

            onNode(hasSetTextAction()).performTextInput("kotlin")
            waitForIdle()
            assertEquals("kotlin", vm.searchQuery.value)

            onNodeWithContentDescription("クリア").performClick()
            waitForIdle()
            assertEquals("", vm.searchQuery.value)
        }
    }

    /**
     * The search icon this test targets is [ArticleListTopBar]'s own entry point into search at a
     * narrow layout ("Native-feel restyle"/"Pane structure" in the `ui-guidelines` skill) — distinct
     * from the query field inside [SearchListPane]'s `KeryxExpandedSearchBar`, which is never
     * present at the same time (the icon only shows outside the Search scope).
     */
    @Test
    fun articleListTopBarSearchIconInvokesOnSearchClickWhenProvided() = runDesktopComposeUiTest {
        var clicked = false
        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
                onSearchClick = { clicked = true },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("記事を検索").performClick()
        waitForIdle()
        assertEquals(true, clicked)
    }

    @Test
    fun articleListTopBarOmitsTheSearchIconWhenNotProvided() = runDesktopComposeUiTest {
        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = {},
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
            )
        }
        waitForIdle()

        onNodeWithContentDescription("記事を検索").assertDoesNotExist()
    }

    /**
     * At `PaneLayout.Single` this pane is unmounted while the article detail is on screen, and the
     * filter can change underneath it while it is gone — a notification's `ShowFeedDetail`, or
     * deleting the feed being viewed. `NarrowPaneRow` restores the scroll position it saved on the
     * way out, so the reset-to-top has to notice a filter change that happened across that gap;
     * `lastFilter` being a plain `remember` would re-initialize to the *new* filter on remount and
     * silently leave the new feed's list scrolled to the old one's offset.
     */
    @Test
    fun resetsToTheTopWhenTheFilterChangedWhileThePaneWasUnmounted() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("fa")
        db.insertFeed("fb", url = "https://feed/fb")
        repeat(40) { db.insertArticleRow("a$it", "fa", createdAt = it.toLong()) }
        repeat(40) { db.insertArticleRow("b$it", "fb", createdAt = it.toLong()) }
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var depth by mutableStateOf(2)
            setContent {
                NarrowPaneRow(visiblePanes(PaneLayout.Single, depth), Modifier.size(360.dp, 400.dp)) { pane, paneModifier ->
                    when (pane) {
                        HomePane.ArticleList -> ArticleListPane(
                            vm = vm,
                            focused = true,
                            onActivated = {},
                            modifier = paneModifier,
                        )
                        else -> Box(paneModifier.fillMaxSize())
                    }
                }
            }
            vm.selectFilter(ArticleFilter.Feed("fa"))
            waitForIdle()

            // Newest first, so a39 is feed A's top row — scroll until it is gone.
            onRoot().performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
            waitForIdle()
            onNodeWithTag("article-a39").assertDoesNotExist()

            // Drill into the article detail (this pane unmounts), switch feeds while it is
            // gone — as PendingNotificationActionHost's ShowFeedDetail does — then come back.
            depth = 3
            waitForIdle()
            vm.selectFilter(ArticleFilter.Feed("fb"))
            waitForIdle()
            depth = 2
            waitForIdle()

            // Feed B's own top row, not whatever sat at feed A's restored offset.
            onNodeWithTag("article-b39").assertIsDisplayed()
        }
    }

    /**
     * Search has no `HomePane`/`SaveableStateHolder` of its own the way `NarrowPaneRow` gives
     * `PaneLayout.Single` — `ArticleListPane` renders `SearchListPane` from an early `return` inside
     * the same composable instead, which used to leave `listState`/`lastFilter` out of composition
     * (and therefore reset to a fresh, unscrolled state) for as long as Search was active.
     */
    @Test
    fun returningFromSearchPreservesTheArticleListsScrollPosition() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("fa")
        repeat(40) { db.insertArticleRow("a$it", "fa", createdAt = it.toLong()) }
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {})
            }
            vm.selectFilter(ArticleFilter.Feed("fa"))
            waitForIdle()

            // Newest first, so a39 is the top row — scroll until it is gone.
            onRoot().performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
            waitForIdle()
            onNodeWithTag("article-a39").assertDoesNotExist()

            vm.enterSearchScope(HomePane.ArticleList)
            waitForIdle()
            vm.exitSearchScope()
            waitForIdle()

            // Still scrolled past the top row, not reset by the round trip through Search.
            onNodeWithTag("article-a39").assertDoesNotExist()
        }
    }

    /**
     * `KeryxExpandedSearchBar` and `ArticleListTopBar` are two separate composables stacked in the
     * same `Column` (see `SearchListPane`) — the clear button appearing/disappearing inside the
     * former must not shift the latter's controls row, the same "Layout stability under state
     * changes" concern `articleListTopBarKeepsTheControlsRowInPlaceWhenNavigateUpBecomesUnavailable`
     * already covers for the back-button row.
     */
    @Test
    fun theSearchFieldsClearButtonAppearingDoesNotMoveTheControlsRowBelowIt() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent {
                ArticleListPane(vm = vm, focused = true, onActivated = {}, onNavigateUp = {}, navigateUpEnabled = true)
            }
            waitForIdle()

            vm.selectFilter(ArticleFilter.Search)
            waitForIdle()
            val boundsWhenEmpty = onNodeWithText("未読のみ").fetchSemanticsNode().boundsInRoot

            vm.setSearchQuery("kotlin")
            waitForIdle()

            assertEquals(
                boundsWhenEmpty,
                onNodeWithText("未読のみ").fetchSemanticsNode().boundsInRoot,
                "the controls row must not move when the search field's clear button appears",
            )
        }
    }
}

/** Inserts an article row for the DB-backed tests above (`article`/`articles` build UI rows). */
private fun KeryxDatabase.insertArticleRow(id: String, feedId: String, createdAt: Long) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
        summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
        is_read = 0L, read_at = null, is_starred = 0L, starred_at = null, cached_at = 0L,
        search_text = "", updated_at = 0L, created_at = createdAt,
    )
}

private fun article(id: String, publishedAt: Long = 0L): ArticleListRow = ArticleListRow(
    id = id,
    feed_id = "f1",
    title = "Article $id",
    url = "u$id",
    published_at = publishedAt,
    created_at = 0L,
    is_read = 1L,
    is_starred = 0L,
)

private fun articles(count: Int): List<ArticleListRow> = List(count) { article("a$it", publishedAt = it.toLong()) }

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Articles
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArticleListPaneTest {

    @Test
    fun scrollsOffscreenSelectionIntoFullView() = runDesktopComposeUiTest {
        val items = articles(30)
        lateinit var state: LazyListState
        var selected by mutableStateOf<Articles?>(null)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selected = selected,
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

    @Test
    fun doesNotScrollWhenSelectionAlreadyFullyVisible() = runDesktopComposeUiTest {
        val items = articles(30)
        lateinit var state: LazyListState
        var selected by mutableStateOf<Articles?>(null)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selected = selected,
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
                selected = null,
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
        var selected by mutableStateOf<Articles?>(null)
        // First frame: tall enough that a single row is never clipped, so we can measure it.
        var containerHeight by mutableStateOf(2000.dp)

        setContent {
            state = rememberLazyListState()
            ArticleListPaneContent(
                articles = items,
                feedTitles = emptyMap(),
                selected = selected,
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
                selected = null,
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
                selected = null,
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
    fun articleListTopBarUnreadOnlyDisabledDoesNotInvokeCallback() = runDesktopComposeUiTest {
        var toggleUnreadCount = 0

        setContent {
            ArticleListTopBar(
                unreadOnly = false,
                onToggleUnreadOnly = { toggleUnreadCount++ },
                newestFirst = true,
                onToggleSort = {},
                onMarkAllRead = {},
                unreadOnlyEnabled = false,
            )
        }
        waitForIdle()

        onNodeWithText("未読のみ").assertIsNotEnabled()
        onNodeWithText("未読のみ").performClick()
        waitForIdle()

        assertEquals(0, toggleUnreadCount)
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
                unreadOnlyEnabled = true,
            )
        }
        waitForIdle()

        onNodeWithText("未読のみ").assertIsEnabled()
        onNodeWithText("未読のみ").performClick()
        waitForIdle()

        assertEquals(1, toggleUnreadCount)
    }

    @Test
    fun unreadOnlyIsEnabledForAllFilter() {
        assertTrue(isUnreadOnlyEnabled(ArticleFilter.All))
    }

    @Test
    fun unreadOnlyIsDisabledForStarredFilter() {
        assertFalse(isUnreadOnlyEnabled(ArticleFilter.Starred))
    }

    @Test
    fun unreadOnlyIsEnabledForFeedFilter() {
        assertTrue(isUnreadOnlyEnabled(ArticleFilter.Feed("f1")))
    }

    @Test
    fun unreadOnlyIsEnabledForTagFilter() {
        assertTrue(isUnreadOnlyEnabled(ArticleFilter.Tag("t1")))
    }

    @Test
    fun unreadOnlyIsEnabledForFolderFilter() {
        assertTrue(isUnreadOnlyEnabled(ArticleFilter.Folder("folder1")))
    }

    @Test
    fun unreadOnlyIsEnabledForSearchFilter() {
        assertTrue(isUnreadOnlyEnabled(ArticleFilter.Search))
    }

    @Test
    fun articleListPaneUnreadOnlyDisabledForStarredFilter() {
        val (driver, db) = inMemoryDb()
        val vm = newMinimalViewModel(driver, db)
        try {
            runDesktopComposeUiTest {
                setContent {
                    ArticleListPane(vm = vm, focused = true, onActivated = {})
                }
                waitForIdle()

                vm.selectFilter(ArticleFilter.Starred)
                waitForIdle()
                onNodeWithText("未読のみ").assertIsNotEnabled()

                vm.selectFilter(ArticleFilter.All)
                waitForIdle()
                onNodeWithText("未読のみ").assertIsEnabled()
            }
        } finally {
            vm.viewModelScope.cancel()
            driver.close()
        }
    }
}

private class ArticleListPaneTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
}

private class ArticleListPaneTestTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null
    override fun save(tokens: OAuthTokens) { stored = tokens }
    override fun load(): OAuthTokens? = stored
    override fun clear() { stored = null }
}

private fun failingFetcher(): FeedFetcher {
    val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }
    return FeedFetcher(client)
}

private fun missingFaviconResolver(): FaviconResolver {
    val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }
    return FaviconResolver(client)
}

private fun newMinimalViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
    appKey: String = "",
    activityCenter: ActivityCenter = ActivityCenter(),
): HomeViewModel {
    val dir = FileIO.join(AppDirs.tempDir(), "article-list-pane-test")
    val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
    val feedRepository = FeedRepository(
        db, failingFetcher(), missingFaviconResolver(), articleRepository, FtsManager(driver).also { it.ensureIndexed() }, syncScheduler,
        NotificationCenter(), ArticleListPaneTestNotificationMessages(), clock, Dispatchers.Unconfined,
    )
    val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
    val folderRepository = FolderRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
    val settingsRepository = SettingsRepository(
        db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined
    )
    val syncRepository = SyncRepository(
        driver = driver,
        db = db,
        ftsManager = FtsManager(driver),
        cloudProvider = { null },
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        activityCenter = activityCenter,
        notificationCenter = NotificationCenter(),
        notificationMessages = ArticleListPaneTestNotificationMessages(),
        localDbPath = "unused",
        tempDir = "unused",
    )
    val tokenStorage = ArticleListPaneTestTokenStorage()
    val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
    val authManager = DropboxAuthManager(authClient, clock = clock)
    val cloudSession = singleProviderCloudSession(
        client = authClient,
        tokenStorage = tokenStorage,
        authManager = authManager,
        clientId = appKey,
        clock = clock,
    )
    return HomeViewModel(
        feedRepository, articleRepository, tagRepository, folderRepository, settingsRepository,
        syncRepository, cloudSession, activityCenter, clock, NewArticleNotifier(), ArticleListPaneTestNotificationMessages(),
        Dispatchers.Unconfined, Dispatchers.Unconfined,
    )
}

private fun article(id: String, publishedAt: Long = 0L): Articles = Articles(
    id = id,
    feed_id = "f1",
    guid = "g$id",
    url = "u$id",
    title = "Article $id",
    summary = null,
    content = null,
    author = null,
    published_at = publishedAt,
    thumbnail_url = null,
    is_read = 1L,
    read_at = null,
    is_starred = 0L,
    starred_at = null,
    cached_at = 0L,
    search_text = "",
    updated_at = 0L,
    created_at = 0L,
    deleted_at = null,
    deleted_updated_at = null,
)

private fun articles(count: Int): List<Articles> = List(count) { article("a$it", publishedAt = it.toLong()) }

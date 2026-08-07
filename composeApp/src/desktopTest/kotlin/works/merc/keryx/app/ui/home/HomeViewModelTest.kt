package works.merc.keryx.app.ui.home

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.MAX_REMEMBERED_SCROLL_POSITIONS
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.singleProviderCloudSession
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.addFeedAlreadySubscribed
import works.merc.keryx.app.domain.addFeedCanSubscribe
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.CountingSqlDriver
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.stampArticleDeleted
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [NotificationMessages] fake returning canned strings (unused by most HomeViewModel tests). */
private class HomeViewModelTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

/** In-memory [TokenStorage] fake (never actually used since appKey is empty in these tests). */
private class HomeViewModelTestTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null

    /** How often the secret store was read — see `cloudConnectedDoesNotReadTokenStorageWhenObserved`. */
    var loadCount = 0
        private set

    override fun save(tokens: OAuthTokens) {
        stored = tokens
    }

    override fun load(): OAuthTokens? {
        loadCount++
        return stored
    }

    override fun clear() {
        stored = null
    }
}

/** Inserts an article directly (bypassing the repository) for viewmodel tests. */
private fun KeryxDatabase.insertArticle(
    id: String,
    feedId: String,
    title: String = "Title $id",
    content: String? = null,
    isRead: Long = 0L,
    isStarred: Long = 0L,
    publishedAt: Long? = null,
    createdAt: Long = 0L,
) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = publishedAt,
        thumbnail_url = null, is_read = isRead, read_at = null, is_starred = isStarred, starred_at = null,
        cached_at = 0L, search_text = content ?: "", updated_at = 0L, created_at = createdAt,
    )
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var driver: CountingSqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "home-vm-test-${Random.nextInt()}")

    // ViewModels created via newViewModel(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise the eager DB-backed
    // collectors (SharingStarted.Eagerly) outlive the test and can throw against the closed driver,
    // surfacing (flakily, on another test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
    private val createdViewModels = mutableListOf<HomeViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val (d, _) = inMemoryDb()
        // Wrapped so a test can assert which actions re-execute the article-list query.
        driver = CountingSqlDriver(d)
        db = KeryxDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    /** A [FeedFetcher] whose HTTP calls always fail fast, for tests that trigger refresh/sync but don't need real fetches. */
    private fun failingFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FeedFetcher] backed by [handler] (mirrors production DI: followRedirects off, HttpTimeout on). */
    private fun fetcherWith(handler: MockRequestHandler): FeedFetcher {
        val client = HttpClient(MockEngine(handler)) {
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

    private fun newViewModel(
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 0L },
        appKey: String = "",
        feedFetcher: FeedFetcher = failingFetcher(),
        activityCenter: ActivityCenter = ActivityCenter(),
        newArticleNotifier: NewArticleNotifier = NewArticleNotifier(),
        tokenStorage: HomeViewModelTestTokenStorage = HomeViewModelTestTokenStorage(),
    ): HomeViewModel {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so the subscribe/refresh path's indexMissing() works.
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManagerIndexed(driver), syncScheduler,
            NotificationCenter(), HomeViewModelTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        // Unconfined write dispatcher so saveLocalSettings persists inline (store.load() assertions
        // in these tests are synchronous).
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { null },
            clock = clock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            activityCenter = activityCenter,
            notificationCenter = NotificationCenter(),
            notificationMessages = HomeViewModelTestNotificationMessages(),
            localDbPath = "unused",
            tempDir = "unused",
        )
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
            syncRepository, cloudSession, activityCenter, clock,
            newArticleNotifier, HomeViewModelTestNotificationMessages(),
            Dispatchers.Unconfined,
            // dbWriteDispatcher: Unconfined so read/star writes run inline for deterministic assertions.
            Dispatchers.Unconfined,
        ).also { createdViewModels += it }
    }

    /**
     * The viewmodel's exposed StateFlows use `SharingStarted.Eagerly` (see [HomeViewModel]), so they
     * begin collecting their DB-backed upstreams as soon as the ViewModel is created — but under the
     * virtual test scheduler those collectors only advance once the scheduler is pumped. This helper
     * keeps an explicit live subscriber per flow so that, together with `testScheduler.advanceUntilIdle()`,
     * every `.value` reflects the current DB state when asserted.
     */
    private fun TestScope.subscribeAll(vm: HomeViewModel) {
        backgroundScope.launch { vm.feeds.collect {} }
        backgroundScope.launch { vm.tags.collect {} }
        backgroundScope.launch { vm.feedTagMap.collect {} }
        backgroundScope.launch { vm.unreadByFeed.collect {} }
        backgroundScope.launch { vm.unreadByTag.collect {} }
        backgroundScope.launch { vm.folders.collect {} }
        backgroundScope.launch { vm.unreadByFolder.collect {} }
        backgroundScope.launch { vm.totalUnread.collect {} }
        backgroundScope.launch { vm.starredUnreadCount.collect {} }
        backgroundScope.launch { vm.articles.collect {} }
        backgroundScope.launch { vm.searchResults.collect {} }
        backgroundScope.launch { vm.searchUnreadCount.collect {} }
    }

    @Test
    fun exposedStateFlowsReflectDbState() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("f1", folderId = "d1")
        db.insertTag("t1", "Kotlin")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f1", isRead = 1L, isStarred = 1L)

        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("f1"), vm.feeds.value.map { it.id })
        assertEquals(listOf("t1"), vm.tags.value.map { it.id })
        assertEquals(mapOf("f1" to 1L), vm.unreadByFeed.value)
        assertEquals(listOf("d1"), vm.folders.value.map { it.id })
        assertEquals(mapOf("d1" to 1L), vm.unreadByFolder.value)
        assertEquals(1L, vm.totalUnread.value)
        assertEquals(0L, vm.starredUnreadCount.value)
    }

    @Test
    fun feedRefreshingReflectsActivityCenter() = runTest {
        // Unconfined scope so the ActivityCenter's stateIn reflects counter changes inline.
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter(activityScope)
        try {
            val vm = newViewModel(activityCenter = activityCenter)
            assertFalse(vm.feedRefreshing.value)

            // Hold a refresh open on the injected ActivityCenter; the VM must expose the same state.
            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackFeedRefresh { gate.await() } }
            assertTrue(vm.feedRefreshing.value)

            gate.complete(Unit)
            job.join()
            assertFalse(vm.feedRefreshing.value)
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun refreshAllIsGuardedWhileAlreadyRefreshing() = runTest {
        db.insertFeed("f1")
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter(activityScope)
        try {
            val vm = newViewModel(activityCenter = activityCenter)
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            // Simulate an in-flight refresh (e.g. the background loop) holding the indicator on.
            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackFeedRefresh { gate.await() } }
            assertTrue(vm.feedRefreshing.value)

            // A manual refresh while busy must be a no-op (guard) and must not throw or clear state early.
            vm.refreshAll()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.feedRefreshing.value)

            gate.complete(Unit)
            job.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.feedRefreshing.value)
        } finally {
            activityScope.cancel()
        }
    }

    /**
     * The list flow carries only the columns the list renders, so selecting has to load the body
     * for the detail pane. Guards that hydration: without it the reader would show an empty article.
     */
    @Test
    fun selectArticleLoadsTheArticleBodyForTheDetailPane() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>the whole article body</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.selectArticle(vm.articles.value.single())

        assertEquals("<p>the whole article body</p>", vm.selectedArticle.value?.content)
    }

    /**
     * A sync merge can tombstone the row between the list emission the user clicked and the click
     * itself. Blanking the reader on that race would be worse than leaving the previous article up,
     * so the selection is kept; the read write still goes out (it is a no-op on a tombstone).
     */
    @Test
    fun selectingAnArticleTombstonedSinceTheEmissionKeepsThePreviousSelection() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>first</p>")
        db.insertArticle("a2", "f1", isRead = 0L, content = "<p>second</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val stale = vm.articles.value.first { it.id == "a2" }
        vm.selectArticle(vm.articles.value.first { it.id == "a1" })
        testScheduler.advanceUntilIdle()

        driver.stampArticleDeleted("a2", deletedAt = 10L)
        vm.selectArticle(stale)
        testScheduler.advanceUntilIdle()

        assertEquals("a1", vm.selectedArticle.value?.id)
        assertEquals("<p>first</p>", vm.selectedArticle.value?.content)
    }

    /**
     * The pinned-read set is revalidated on every `articles` write, and "mark all read" sizes it to
     * the whole visible list. Doing that with one `getById` per pin was an N+1 of full-row reads —
     * each on its own connection, inside a `MutableStateFlow.update` CAS lambda that can re-run it.
     * One `id IN (...)` existence query replaces the lot.
     */
    @Test
    fun revalidatingPinnedArticlesDoesNotIssueOneQueryPerPin() = runTest {
        db.insertFeed("f1")
        repeat(60) { db.insertArticle("a$it", "f1", isRead = 0L) }
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.markAllRead()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.articles.value.size >= 60)

        val before = driver.articleGetByIdExecutions
        // Any articles write re-triggers the revalidation.
        vm.toggleRead(vm.articles.value.first())
        testScheduler.advanceUntilIdle()

        assertTrue(
            driver.articleGetByIdExecutions - before <= 1,
            "expected at most the selection's own row fetch, got " +
                "${driver.articleGetByIdExecutions - before} getById calls for 60 pins",
        )
    }

    /**
     * A tombstone that lands while an article is pinned must still drop it, or the `articles` merge
     * step would re-add deleted content to the visible list.
     */
    @Test
    fun revalidatingPinnedArticlesStillDropsATombstonedPin() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectArticle(vm.articles.value.first { it.id == "a1" })
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.articles.value.any { it.id == "a1" }, "the read article stays pinned")

        driver.stampArticleDeleted("a1", deletedAt = 10L)
        vm.toggleRead(vm.articles.value.first { it.id == "a2" })
        testScheduler.advanceUntilIdle()

        assertTrue(vm.articles.value.none { it.id == "a1" }, "a tombstoned pin must be dropped")
    }

    @Test
    fun selectArticleMarksReadAndUpdatesSelectedState() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val article = db.articlesQueries.getById("a1").executeAsOne()

        vm.selectArticle(article.toListRow())

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, vm.selectedArticle.value?.is_read)
        assertEquals("a1", vm.selectedArticle.value?.id)
    }

    @Test
    fun selectNextAndSelectPreviousMoveThroughListAndClampAtEnds() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 1L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // Newest first ordering: a1, a2, a3.
        assertEquals(listOf("a1", "a2", "a3"), vm.articles.value.map { it.id })

        // No selection yet: selectNext should select the first item.
        vm.selectNext()
        assertEquals("a1", vm.selectedArticle.value?.id)

        vm.selectNext()
        assertEquals("a2", vm.selectedArticle.value?.id)

        vm.selectNext()
        assertEquals("a3", vm.selectedArticle.value?.id)

        // Clamp at the last item.
        vm.selectNext()
        assertEquals("a3", vm.selectedArticle.value?.id)

        vm.selectPrevious()
        assertEquals("a2", vm.selectedArticle.value?.id)

        vm.selectPrevious()
        assertEquals("a1", vm.selectedArticle.value?.id)

        // Clamp at the first item.
        vm.selectPrevious()
        assertEquals("a1", vm.selectedArticle.value?.id)
    }

    @Test
    fun selectingUnreadArticleWhileUnreadOnlyKeepsItPinnedInList() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        // a1 is now read but stays visible in the unread-only list because it's pinned.
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun enablingUnreadOnlyHidesReadArticlesExceptTheCurrentSelection() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // Read through all three articles while unread-only is off.
        vm.selectArticle(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        vm.selectArticle(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        vm.selectArticle(db.articlesQueries.getById("a3").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()

        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        // Only the currently-selected article (a3) stays visible; a1/a2 are read and not selected.
        assertEquals(listOf("a3"), vm.articles.value.map { it.id })
    }

    @Test
    fun selectFilterClearsPinnedReadArticlesAndSelection() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })

        // A genuine filter change (Feed("f1") -> All) clears pin/selection.
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        assertNull(vm.selectedArticle.value)
        // a1 is now read and no longer pinned, so it drops out of the unread-only list.
        assertTrue(vm.articles.value.isEmpty())
    }

    @Test
    fun selectFilterOnSameFilterKeepsPinnedReadArticlesAndSelection() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })

        // Re-clicking the already-selected feed must not hide the just-read selected article.
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()

        assertEquals("a1", vm.selectedArticle.value?.id)
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun markSelectedUnreadClearsPinAndUpdatesSelectedState() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        vm.markSelectedUnread()
        testScheduler.advanceUntilIdle()

        assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(0L, vm.selectedArticle.value?.is_read)
        // Pin cleared: a1 is unread again and no longer needs pinning, so it stays visible naturally.
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun toggleStarUpdatesDbAndRefreshesSelectedStateOnlyWhenSelected() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val a1 = db.articlesQueries.getById("a1").executeAsOne()
        val a2 = db.articlesQueries.getById("a2").executeAsOne()

        // Not selected: DB updates, but selectedArticle stays null.
        vm.toggleStar(a2.toListRow())
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertNull(vm.selectedArticle.value)

        // Select a1, then toggle its star: selectedArticle should refresh.
        vm.selectArticle(a1.toListRow())
        vm.toggleStar(db.articlesQueries.getById("a1").executeAsOne().toListRow())

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_starred)
        assertEquals(1L, vm.selectedArticle.value?.is_starred)
    }

    @Test
    fun toggleReadUpdatesDbAndRefreshesSelectedStateOnlyWhenSelected() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val a2 = db.articlesQueries.getById("a2").executeAsOne()

        // Not selected: unread -> read updates DB, but selectedArticle stays null.
        vm.toggleRead(a2.toListRow())
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        assertNull(vm.selectedArticle.value)

        // Not selected: read -> unread updates DB, still no selection.
        vm.toggleRead(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        assertNull(vm.selectedArticle.value)

        // Select a1 (marks it read), then toggle it back to unread: selectedArticle should refresh.
        val a1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(a1.toListRow())
        assertEquals(1L, vm.selectedArticle.value?.is_read)

        vm.toggleRead(db.articlesQueries.getById("a1").executeAsOne().toListRow())

        assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(0L, vm.selectedArticle.value?.is_read)
    }

    @Test
    fun markAllReadDelegatesToRepositoryAndPinsVisibleUnread() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        // Both visible unread articles are pinned in their read state and remain visible
        // under unread-only until the filter is switched.
        assertEquals(listOf("a2", "a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun setUnreadOnlyIsANoOpWhenTheValueAlreadyMatches() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f1", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.markAllRead()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a2", "a1"), vm.articles.value.map { it.id })

        // A redundant call with the already-current value must not re-derive the pin map from
        // scratch (which would keep only the selected article and drop markAllRead()'s pins).
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a2", "a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun markAllReadKeepsSelectedArticlePinnedAndVisibleUnderUnreadOnly() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        // Both a1 and a2 were visible unread articles, so both are pinned in their read state
        // and remain visible under unread-only until the filter is switched.
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
        assertEquals(1L, vm.selectedArticle.value?.is_read)
    }

    @Test
    fun refreshAllKeepsSelectedArticlePinnedAndVisibleUnderUnreadOnly() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.refreshAll()
        testScheduler.advanceUntilIdle()

        // a1 was selected (now read) and must stay pinned/visible; a2 is still unread on its own.
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun syncKeepsSelectedArticlePinnedAndVisibleUnderUnreadOnly() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.sync()
        testScheduler.advanceUntilIdle()

        // a1 was selected (now read) and must stay pinned/visible; a2 is still unread on its own.
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun refreshAllDropsStaleSelectionPinnedBeforeCompletionWhenSelectionChangesMidFlight() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        // Gate the feed fetch open with a CompletableDeferred so refreshAll() genuinely suspends
        // mid-flight, giving us a window to change the selection before it completes.
        val gate = CompletableDeferred<Unit>()
        // Unconfined-on-testScheduler ActivityCenter (mirrors feedRefreshingReflectsActivityCenter):
        // the default ActivityCenter() runs its stateIn on a real Dispatchers.Default scope, which
        // would mix real and virtual time here and make the poll below unreliable.
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter(activityScope)
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { gate.await(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            vm.selectFilter(ArticleFilter.All)
            testScheduler.advanceUntilIdle()
            val article1 = db.articlesQueries.getById("a1").executeAsOne()
            vm.selectArticle(article1.toListRow())
            vm.setUnreadOnly(true)
            testScheduler.advanceUntilIdle()

            vm.refreshAll()
            // viewModelScope.launch{} bodies are only queued, not run inline; runCurrent() starts the
            // coroutine so it reaches (and suspends on) the gate before we proceed.
            testScheduler.runCurrent()
            assertTrue(activityCenter.feedRefreshing.value)
            // The refresh is genuinely in flight here — simulate the user moving on to a2 before it completes.
            val article2 = db.articlesQueries.getById("a2").executeAsOne()
            vm.selectArticle(article2.toListRow())
            gate.complete(Unit)

            // The fetch resumes off the virtual scheduler (real MockEngine dispatch, see docs/testing.md),
            // so poll with short real sleeps until refreshAll settles (mirrors
            // refreshAllRaisesTrayNotificationWhenNewArticlesArrive's established pattern).
            var waited = 0
            while (activityCenter.feedRefreshing.value && waited < 5_000) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(50)
                waited += 50
            }
            testScheduler.advanceUntilIdle()

            // Only a2 (the current selection) should remain pinned; a1 must not survive the refresh.
            assertEquals(listOf("a2"), vm.articles.value.map { it.id })
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun syncDropsStaleSelectionPinnedBeforeCompletionWhenSelectionChangesMidFlight() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        vm.sync()
        // viewModelScope.launch{} for sync() is only queued at this point (cloudProvider() == null
        // makes the actual sync a fast no-op once it runs, but it hasn't run yet) — selecting a2
        // now reproduces a selection change made while sync is still in flight.
        val article2 = db.articlesQueries.getById("a2").executeAsOne()
        vm.selectArticle(article2.toListRow())
        testScheduler.advanceUntilIdle()

        // Only a2 (the current selection) should remain pinned; a1 must not survive the sync.
        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun pinnedArticleSoftDeletedByAnotherDeviceIsDroppedFromPinsReactively() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        // Simulate another device's sync propagating a soft-delete tombstone for the pinned article.
        driver.stampArticleDeleted("a1", deletedAt = 100L)
        val article2 = db.articlesQueries.getById("a2").executeAsOne()
        vm.toggleStar(article2.toListRow()) // ordinary write to `articles` -> ticks articleChangeSignal
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
    }

    /**
     * A sync that tombstones the *selected* article must not leave it in the visible list. The
     * trailing re-pin that `sync()` / `refreshAll()` perform (to keep the selection visible across
     * a refresh) must not resurrect a row that no longer exists, which is the same invariant
     * [pinnedArticleSoftDeletedByAnotherDeviceIsDroppedFromPinsReactively] covers for the
     * reactive reconcile path.
     */
    @Test
    fun syncDoesNotResurrectTheSelectedArticleAfterItIsTombstoned() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        vm.selectArticle(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        // Another device's sync propagates a soft-delete tombstone for the selected article.
        driver.stampArticleDeleted("a1", deletedAt = 100L)
        vm.sync()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun markAllReadOnStarredFilterDoesNotForceSelectedArticleReadState() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, isStarred = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        // Flip the selected article back to unread while keeping it selected, so it enters
        // markAllRead() as unread (selecting an article always marks it read immediately).
        vm.markSelectedUnread()
        testScheduler.advanceUntilIdle()
        assertEquals(0L, vm.selectedArticle.value?.is_read)

        // Starred filter: markAllAsRead is a no-op, so the DB row (and selected state) must stay unread.
        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(0L, vm.selectedArticle.value?.is_read)
    }

    @Test
    fun setUnreadOnlyAndToggleSortFlipExposedState() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertFalse(vm.unreadOnly.value)
        vm.setUnreadOnly(true)
        assertTrue(vm.unreadOnly.value)

        assertTrue(vm.newestFirst.value)
        vm.toggleSort()
        assertFalse(vm.newestFirst.value)
        vm.toggleSort()
        assertTrue(vm.newestFirst.value)
    }

    /**
     * The unread-only, sort and pinned-read inputs are pure display transforms over whatever the
     * article-list query returned, so only a filter change may re-execute that query. Guards against
     * putting them back into the `flatMapLatest` key, which made every selection re-run the whole
     * unbounded list query (invisible to behavioral assertions, but O(all articles) per click).
     */
    @Test
    fun displayOnlyChangesDoNotReExecuteTheArticleListQuery() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        val afterFilter = driver.listQueryExecutions
        vm.setUnreadOnly(true)
        vm.toggleSort()
        testScheduler.advanceUntilIdle()
        assertEquals(
            afterFilter,
            driver.listQueryExecutions,
            "unread-only and sort are display transforms and must not re-query",
        )

        // Selecting marks the article read, which does legitimately notify the articles table —
        // but exactly once, not once for the write plus once for the resulting pin.
        vm.selectArticle(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(
            afterFilter + 1,
            driver.listQueryExecutions,
            "a selection should re-query only for its own mark-as-read write",
        )

        // A filter change must still switch queries.
        val beforeSwitch = driver.listQueryExecutions
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertTrue(
            driver.listQueryExecutions > beforeSwitch,
            "changing the filter must re-execute the list query",
        )
    }

    @Test
    fun toggleSortReversesTheArticleListOrder() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2", "a3"), vm.articles.value.map { it.id })

        vm.toggleSort()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a3", "a2", "a1"), vm.articles.value.map { it.id })

        vm.toggleSort()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2", "a3"), vm.articles.value.map { it.id })
    }

    @Test
    fun oldestFirstOrderHoldsWithAPinnedArticleMergedIn() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setUnreadOnly(true)
        vm.toggleSort()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a3", "a2", "a1"), vm.articles.value.map { it.id })

        // Selecting a2 marks it read; it stays pinned and keeps its oldest-first position.
        vm.selectArticle(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a3", "a2", "a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun searchFilterEncodeDecodeRoundTrips() {
        assertEquals("search", ArticleFilter.Search.encode())
        assertEquals(ArticleFilter.Search, decodeArticleFilter("search"))
    }

    @Test
    fun setSearchQuerySwitchesToSearchScopeAndRetainsQuery() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertEquals(ArticleFilter.All, vm.filter.value)

        // Typing moves into the Search scope on the first keystroke. (A <3-char query keeps the FTS
        // query from running — the switch happens on isNotEmpty, not on the result set.)
        vm.setSearchQuery("ko")
        assertEquals(ArticleFilter.Search, vm.filter.value)
        assertEquals("ko", vm.searchQuery.value)

        // Leaving Search keeps the query so returning re-shows the same results.
        vm.selectFilter(ArticleFilter.All)
        assertEquals(ArticleFilter.All, vm.filter.value)
        assertEquals("ko", vm.searchQuery.value)

        vm.selectFilter(ArticleFilter.Search)
        assertEquals(ArticleFilter.Search, vm.filter.value)
        assertEquals("ko", vm.searchQuery.value)
    }

    @Test
    fun currentArticlesSwitchesSourceToSearchResultsInSearchScope() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")

        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        // Normal scope: currentArticles (used by J/K/arrow navigation) mirrors the feed-backed list.
        assertEquals(setOf("a1", "a2"), vm.currentArticles().map { it.id }.toSet())

        // Search scope draws from searchResults instead — empty here (no query), which proves the
        // source switched away from the feed list. (Real FTS hit ranking is covered by FtsSearchTest.)
        vm.selectFilter(ArticleFilter.Search)
        assertEquals(emptyList(), vm.currentArticles())
    }

    @Test
    fun restoredSearchFilterFallsBackToAll() = runTest {
        val vm1 = newViewModel()
        vm1.setSearchQuery("ko") // persists lastFilter = "search"
        assertEquals(ArticleFilter.Search, vm1.filter.value)

        // A fresh viewmodel (same settings dir) must not restore into an empty Search view,
        // since the query text isn't persisted.
        val vm2 = newViewModel()
        assertEquals(ArticleFilter.All, vm2.filter.value)
    }

    /**
     * [HomeViewModel.setSearchQuery]'s 250ms debounce runs under the search flow's
     * `flowOn(dispatcher)` (`Dispatchers.Unconfined` in these tests), which drops the shared
     * virtual test scheduler for that segment of the pipeline — `delay()` there falls back to a
     * real-time wait instead of being advanced by [kotlinx.coroutines.test.TestScope.testScheduler].
     * A short real sleep lets that debounce actually elapse; the surrounding `advanceUntilIdle()`
     * calls then pump everything else (the FTS query + StateFlow update) on the shared scheduler.
     * Sleeps well past the 250ms debounce window (rather than just past it) so this doesn't flake
     * on a loaded CI machine.
     */
    private fun TestScope.advanceForSearchDebounce() {
        testScheduler.advanceUntilIdle()
        Thread.sleep(500)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun searchingIsTrueWhileDebouncedResultsAreStillPendingThenFalse() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        backgroundScope.launch { vm.searching.collect {} }

        // A valid query is typed but the debounced FTS results haven't arrived yet.
        vm.setSearchQuery("Kotlin")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.searching.value)

        // Once the debounce elapses and the search runs, searching clears and results appear.
        advanceForSearchDebounce()
        assertFalse(vm.searching.value)
        assertEquals(listOf("a1"), vm.searchResults.value.map { it.article.id })

        // Too-short query has no usable terms, so it's not "searching" (shows the too-short hint).
        vm.setSearchQuery("ab")
        testScheduler.advanceUntilIdle()
        assertFalse(vm.searching.value)
    }

    @Test
    fun searchResultsFilterToUnreadOnlyWhenToggled() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 1L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a1"), vm.searchResults.value.map { it.article.id })
    }

    @Test
    fun searchUnreadCountCountsUnreadMatchesIgnoresUnreadOnlyAndDecrementsOnRead() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 1L)
        db.insertArticle("a3", "f1", title = "Kotlin Three", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        // Only the two unread matches (a1, a3) are counted; the read one (a2) is excluded.
        assertEquals(2L, vm.searchUnreadCount.value)

        // The unread-only display toggle must not change the count (it counts raw matches).
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertEquals(2L, vm.searchUnreadCount.value)

        // Selecting a result marks it read immediately (pinned); the count drops by one.
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(1L, vm.searchUnreadCount.value)
    }

    @Test
    fun selectingSearchResultKeepsItPinnedAndVisibleUnderUnreadOnlyAfterBeingMarkedRead() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        vm.setUnreadOnly(true)
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        // a1 is now read (selecting always marks read immediately) but stays visible because it's pinned.
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())
    }

    @Test
    fun changingSearchQueryClearsPinnedReadArticles() = runTest {
        db.insertFeed("f1")
        // a1 matches both queries, a2 only "Kotlin", a3 only "Java".
        db.insertArticle("a1", "f1", title = "Kotlin and Java", content = "kotlin java", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Only", content = "kotlin", isRead = 0L)
        db.insertArticle("a3", "f1", title = "Java Only", content = "java", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        vm.setUnreadOnly(true)
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        // a1 is read but pinned, so it stays visible under unread-only.
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        // Change query: pins are cleared immediately. After the new search, a1 is read and unpinned,
        // so even though it also matches "Java" it must not appear under unread-only.
        vm.setSearchQuery("Java")
        advanceForSearchDebounce()
        assertEquals(listOf("a3"), vm.searchResults.value.map { it.article.id })
    }

    @Test
    fun markAllReadInSearchScopeMarksOnlyUnreadMatchesAndKeepsSelectedOnePinned() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 0L)
        db.insertArticle("a3", "f1", title = "Kotlin Three", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        vm.setUnreadOnly(true)
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a2", "a3"), vm.searchResults.value.map { it.article.id }.toSet())

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a3").executeAsOne().is_read)
        // All three were visible unread matches, so all are pinned in their read state and remain
        // visible under unread-only until the filter or query changes.
        assertEquals(listOf("a1", "a2", "a3"), vm.searchResults.value.map { it.article.id })
    }

    @Test
    fun markAllReadInSearchScopeDoesNotAffectUnreadArticlesOutsideTheMatch() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("other", "f1", title = "Something else", content = "unrelated content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertEquals(listOf("a1"), vm.searchResults.value.map { it.article.id })

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        // "other" never matched the search, so a search-scoped mark-all-read must not touch it —
        // proving this isn't a blanket mark-everything-read fallback.
        assertEquals(0L, db.articlesQueries.getById("other").executeAsOne().is_read)
    }

    @Test
    fun toggleSortHasNoEffectOnSearchResultsOrdering() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Zzz Kotlin", content = "kotlin kotlin kotlin filler padding words")
        db.insertArticle("a2", "f1", title = "Kotlin", content = "kotlin")
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        val before = vm.searchResults.value.map { it.article.id }
        assertEquals(2, before.size)

        vm.toggleSort()
        testScheduler.advanceUntilIdle()

        assertFalse(vm.newestFirst.value)
        // The relevance-rank order is unaffected by the (search-scope-irrelevant) sort toggle.
        assertEquals(before, vm.searchResults.value.map { it.article.id })
    }

    @Test
    fun toggleReadInSearchUpdatesSearchResults() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        assertEquals(0L, vm.searchResults.value.single().article.is_read)

        vm.toggleRead(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()

        assertEquals(1L, vm.searchResults.value.single().article.is_read)
    }

    @Test
    fun toggleStarInSearchUpdatesSearchResults() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content")
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        assertEquals(0L, vm.searchResults.value.single().article.is_starred)

        vm.toggleStar(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()

        assertEquals(1L, vm.searchResults.value.single().article.is_starred)
    }

    @Test
    fun starAfterReadInSearchKeepsStarVisible() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        // Read the result first: this pins it (is_read=1) with its is_starred snapshot (0).
        vm.selectArticle(vm.searchResults.value.single().article)
        testScheduler.advanceUntilIdle()
        assertEquals(1L, vm.searchResults.value.single().article.is_read)

        // Star the already-read result: the stale pinned snapshot must not hide the fresh star.
        vm.toggleStar(vm.searchResults.value.single().article)
        testScheduler.advanceUntilIdle()

        val result = vm.searchResults.value.single().article
        assertEquals(1L, result.is_starred)
        assertEquals(1L, result.is_read)
    }

    @Test
    fun syncAndRefreshAllDelegateWithoutThrowingWhenCloudDisconnected() = runTest {
        db.insertFeed("f1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.sync()
        testScheduler.advanceUntilIdle()

        vm.refreshAll()
        testScheduler.advanceUntilIdle()
        // No exception thrown; reaching here is the assertion.
    }

    @Test
    fun refreshAllRaisesTrayNotificationWhenNewArticlesArrive() = runTest {
        db.insertFeed("f1")
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        // Plain launch (not backgroundScope): a SharedFlow needs an actively-collecting subscriber
        // to observe an emission at all (unlike a StateFlow's cached .value, which subscribeAll's
        // helpers rely on elsewhere in this file), and this scope's advanceUntilIdle() below reliably
        // pumps it, matching NewArticleNotifierTest's established pattern.
        val trayJob = launch { notifier.trayEvents.collect { tray.add(it) } }
        // Explicit Unconfined-on-testScheduler ActivityCenter (mirrors feedRefreshingReflectsActivityCenter):
        // the default ActivityCenter() runs its stateIn on a real Dispatchers.Default scope, which
        // would mix real and virtual time here and make the poll below unreliable.
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter(activityScope)
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) },
                newArticleNotifier = notifier,
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            vm.refreshAll()
            // The feed fetch/parse work hops onto Ktor's real MockEngine dispatcher (not the virtual
            // test scheduler), so a single advanceUntilIdle() can race it (see docs/testing.md on
            // mixing runTest with Ktor MockEngine). Poll with short real sleeps until it lands.
            var waited = 0
            while (tray.isEmpty() && waited < 5_000) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(50)
                waited += 50
            }

            // The single fetched article (guid g1) is reported via the fake NotificationMessages
            // as "new:1"; this proves manual refresh now reaches the tray, not just the background loop.
            assertEquals(listOf("new:1"), tray)
            trayJob.cancel()
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun cloudConnectedReflectsCloudSessionState() = runTest {
        val vm = newViewModel(appKey = "")
        subscribeAll(vm)
        assertFalse(vm.cloudConnected.value)
    }

    /**
     * `cloudConnected` is read straight from composition, and answering it reaches the OS secret
     * store (an uncached D-Bus / Credential Manager round trip on Linux and Windows). It must
     * therefore be re-evaluated only when the selected provider changes — not per observation, and
     * not on unrelated local-settings writes, which happen as often as every drag frame.
     */
    @Test
    fun cloudConnectedDoesNotReReadTokenStorageWhenObservedOrOnUnrelatedSettingsWrites() = runTest {
        val tokenStorage = HomeViewModelTestTokenStorage()
        val vm = newViewModel(appKey = "app-key", tokenStorage = tokenStorage)
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val afterStartup = tokenStorage.loadCount
        // Guard against a false pass: with a configured client id, answering the question at all
        // must reach the secret store, so the counter has to be moving in the first place.
        assertTrue(afterStartup > 0, "cloudConnected should consult token storage at least once")

        // What recomposition does: read the value over and over.
        repeat(50) { assertFalse(vm.cloudConnected.value) }
        // Unrelated local-settings writes (sort, filter, pane geometry) must not re-read either.
        vm.toggleSort()
        vm.selectFilter(ArticleFilter.Starred)
        vm.toggleSort()
        testScheduler.advanceUntilIdle()

        assertEquals(
            afterStartup,
            tokenStorage.loadCount,
            "observing cloudConnected must not reach the secret store again",
        )
    }

    @Test
    fun unsubscribeFeedResetsFilterWhenViewingThatFeed() = runTest {
        db.insertFeed("f1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val feed = db.feedsQueries.getById("f1").executeAsOne()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        vm.unsubscribeFeed(feed.id)

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertNotNull(db.feedsQueries.getById("f1").executeAsOne().deleted_at)
    }

    @Test
    fun unsubscribeFeedLeavesFilterAloneWhenViewingSomethingElse() = runTest {
        db.insertFeed("f1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.All)

        vm.unsubscribeFeed("f1")

        assertEquals(ArticleFilter.All, vm.filter.value)
    }

    @Test
    fun deleteTagResetsFilterWhenViewingThatTag() = runTest {
        db.insertTag("t1", "Kotlin")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Tag("t1"))

        vm.deleteTag("t1")

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertNotNull(db.tagsQueries.getById("t1").executeAsOne().deleted_at)
    }

    @Test
    fun createTagWithBlankNameIsNoOp() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        val id = vm.createTag("   ")

        assertNull(id)
        assertTrue(db.tagsQueries.watchAll().executeAsList().isEmpty())
    }

    @Test
    fun createTagWithValidNameCreatesTag() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        val id = vm.createTag("  Kotlin  ")

        assertNotNull(id)
        assertEquals("Kotlin", db.tagsQueries.getById(id).executeAsOne().name)
    }

    @Test
    fun updateTagWithBlankNameIsNoOp() = runTest {
        db.insertTag("t1", "Old")
        val vm = newViewModel()
        subscribeAll(vm)

        vm.updateTag("t1", "   ", null)

        assertEquals("Old", db.tagsQueries.getById("t1").executeAsOne().name)
    }

    @Test
    fun updateTagWithValidNameUpdatesTag() = runTest {
        db.insertTag("t1", "Old")
        val vm = newViewModel()
        subscribeAll(vm)

        vm.updateTag("t1", "  New  ", null)

        assertEquals("New", db.tagsQueries.getById("t1").executeAsOne().name)
    }

    @Test
    fun createTagPropagatesColorToRepository() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        val id = vm.createTag("Kotlin", "#FF0000")

        assertNotNull(id)
        assertEquals("#FF0000", db.tagsQueries.getById(id).executeAsOne().color)
    }

    @Test
    fun updateTagPropagatesColorToRepositoryWithoutLosingIt() = runTest {
        db.insertTag("t1", "Old")
        val vm = newViewModel()
        subscribeAll(vm)

        vm.updateTag("t1", "New", "#00FF00")

        // A non-null color passed to updateTag must actually be persisted, not silently dropped
        // to null (the old hardcoded behavior before per-tag color selection existed).
        assertEquals("#00FF00", db.tagsQueries.getById("t1").executeAsOne().color)
    }

    @Test
    fun deleteFolderResetsFilterWhenViewingThatFolder() = runTest {
        db.insertFolder("d1", "Kotlin")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Folder("d1"))

        vm.deleteFolder("d1")

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertNotNull(db.foldersQueries.getById("d1").executeAsOne().deleted_at)
    }

    @Test
    fun createFolderWithBlankNameIsNoOp() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        val id = vm.createFolder("   ")

        assertNull(id)
        assertTrue(db.foldersQueries.watchAll().executeAsList().isEmpty())
    }

    @Test
    fun toggleFolderCollapsedPersistsToLocalSettings() = runTest {
        db.insertFolder("d1", "Kotlin")
        // A fresh reader (not the viewmodel's own cached SettingsRepository) verifies the write
        // actually reached disk (LocalSettingsStore.load() re-reads the file each call).
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        assertFalse("d1" in store.load().collapsedFolderIds)

        vm.toggleFolderCollapsed("d1")

        assertTrue("d1" in vm.collapsedFolderIds.value)
        assertTrue("d1" in store.load().collapsedFolderIds)

        vm.toggleFolderCollapsed("d1")

        assertFalse("d1" in vm.collapsedFolderIds.value)
        assertFalse("d1" in store.load().collapsedFolderIds)
    }

    @Test
    fun deleteFolderRemovesItFromCollapsedFolderIds() = runTest {
        db.insertFolder("d1", "Kotlin")
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.toggleFolderCollapsed("d1")
        assertTrue("d1" in vm.collapsedFolderIds.value)

        vm.deleteFolder("d1")

        assertFalse("d1" in vm.collapsedFolderIds.value)
        assertFalse("d1" in store.load().collapsedFolderIds)
    }

    @Test
    fun toggleTagExpandedPersistsToLocalSettings() = runTest {
        db.insertTag("t1", "Kotlin")
        // A fresh reader (not the viewmodel's own cached SettingsRepository) verifies the write
        // actually reached disk (LocalSettingsStore.load() re-reads the file each call).
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        assertFalse("t1" in store.load().expandedTagIds)

        vm.toggleTagExpanded("t1")

        assertTrue("t1" in vm.expandedTagIds.value)
        assertTrue("t1" in store.load().expandedTagIds)

        vm.toggleTagExpanded("t1")

        assertFalse("t1" in vm.expandedTagIds.value)
        assertFalse("t1" in store.load().expandedTagIds)
    }

    @Test
    fun deleteTagRemovesItFromExpandedTagIds() = runTest {
        db.insertTag("t1", "Kotlin")
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.toggleTagExpanded("t1")
        assertTrue("t1" in vm.expandedTagIds.value)

        vm.deleteTag("t1")

        assertFalse("t1" in vm.expandedTagIds.value)
        assertFalse("t1" in store.load().expandedTagIds)
    }

    // --- Article scroll position memory ---

    @Test
    fun saveScrollPositionCapsAtMaxAndEvictsLeastRecentlyUsed() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        for (i in 1..MAX_REMEMBERED_SCROLL_POSITIONS) {
            vm.saveScrollPosition("a$i", i * 100)
        }
        for (i in 1..MAX_REMEMBERED_SCROLL_POSITIONS) {
            assertEquals(i * 100, vm.getScrollPosition("a$i"))
        }

        // One more entry evicts the least-recently-used one (a1, saved first and never touched again).
        vm.saveScrollPosition("aNew", 999)

        assertEquals(0, vm.getScrollPosition("a1")) // evicted -> falls back to default 0
        assertEquals(999, vm.getScrollPosition("aNew"))
        for (i in 2..MAX_REMEMBERED_SCROLL_POSITIONS) {
            assertEquals(i * 100, vm.getScrollPosition("a$i"))
        }
    }

    @Test
    fun saveScrollPositionOnExistingArticleMovesItToMruFrontAndUpdatesValue() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)
        for (i in 1..MAX_REMEMBERED_SCROLL_POSITIONS) {
            vm.saveScrollPosition("a$i", i * 100)
        }

        // Re-save the oldest entry (a1): it should move to the MRU front, so a2 (now the
        // least-recently-used) is the one evicted by the next new entry, not a1.
        vm.saveScrollPosition("a1", 12345)
        vm.saveScrollPosition("aNew", 1)

        assertEquals(12345, vm.getScrollPosition("a1"))
        assertEquals(0, vm.getScrollPosition("a2")) // evicted
        assertEquals(1, vm.getScrollPosition("aNew"))
    }

    @Test
    fun saveScrollPositionPersistsToLocalSettings() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)

        vm.saveScrollPosition("a1", 42)

        val persisted = store.load().recentArticleScrollPositions
        assertEquals(1, persisted.size)
        assertEquals("a1", persisted[0].articleId)
        assertEquals(42, persisted[0].scrollOffset)
    }

    // --- Restart persistence / restoration ---

    @Test
    fun selectFilterPersistsLastFilterAndClearsLastArticleId() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        assertEquals("a1", store.load().lastArticleId)

        vm.selectFilter(ArticleFilter.Feed("f1"))

        assertEquals("feed:f1", store.load().lastFilter)
        assertNull(store.load().lastArticleId)
    }

    @Test
    fun selectArticlePersistsLastArticleId() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val article = db.articlesQueries.getById("a1").executeAsOne()

        vm.selectArticle(article.toListRow())

        assertEquals("a1", store.load().lastArticleId)
    }

    @Test
    fun restartRestoresFilterArticleAndScrollPosition() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm1.selectArticle(article1.toListRow())
        vm1.saveScrollPosition("a1", 321)
        testScheduler.advanceUntilIdle()

        // Simulate an app restart: a fresh HomeViewModel over the same db/dir.
        val vm2 = newViewModel()
        subscribeAll(vm2)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Feed("f1"), vm2.filter.value)
        assertEquals("a1", vm2.selectedArticle.value?.id)
        assertEquals(321, vm2.getScrollPosition("a1"))
    }

    @Test
    fun restartFallsBackToAllWhenFilterTargetWasDeletedMeanwhile() = runTest {
        db.insertFolder("d1", "Kotlin")
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.Folder("d1"))
        testScheduler.advanceUntilIdle()

        // Simulate the folder being soft-deleted independently (e.g. by another device's sync)
        // without going through vm1, so the persisted lastFilter still points at "folder:d1".
        db.foldersQueries.softDelete(1L, 1L, "d1")

        val vm2 = newViewModel()
        subscribeAll(vm2)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.All, vm2.filter.value)
    }

    @Test
    fun restartPinsRestoredReadArticleSoItStaysVisibleInUnreadOnlyList() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 1L, publishedAt = 1L, createdAt = 1L)
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm1.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        val vm2 = newViewModel()
        vm2.setUnreadOnly(true)
        subscribeAll(vm2)
        testScheduler.advanceUntilIdle()

        assertEquals("a1", vm2.selectedArticle.value?.id)
        // Without pinning the restored (already-read) article, it would drop out of this list.
        assertEquals(listOf("a1"), vm2.articles.value.map { it.id })
    }

    @Test
    fun restartDoesNotReMarkRestoredArticleAsRead() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm1 = newViewModel(clock = Clock { 1000L })
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm1.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        val readAtAfterFirstSelection = db.articlesQueries.getById("a1").executeAsOne().read_at
        assertNotNull(readAtAfterFirstSelection)

        val vm2 = newViewModel(clock = Clock { 999999L })
        subscribeAll(vm2)
        testScheduler.advanceUntilIdle()

        assertEquals("a1", vm2.selectedArticle.value?.id)
        assertEquals(readAtAfterFirstSelection, db.articlesQueries.getById("a1").executeAsOne().read_at)
    }

    // --- Focused pane / unread-only / newest-first restoration ---

    @Test
    fun setFocusedPanePersistsToLocalSettings() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        assertNull(store.load().lastFocusedPane)

        vm.setFocusedPane(HomePane.FeedList)

        assertEquals("FeedList", store.load().lastFocusedPane)
    }

    @Test
    fun getInitialFocusedPaneDefaultsToArticleListWhenNothingSaved() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertEquals(HomePane.ArticleList, vm.getInitialFocusedPane())
    }

    @Test
    fun restartRestoresFocusedPane() = runTest {
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.setFocusedPane(HomePane.FeedList)

        val vm2 = newViewModel()
        subscribeAll(vm2)

        assertEquals(HomePane.FeedList, vm2.getInitialFocusedPane())
    }

    @Test
    fun getInitialFocusedPaneFallsBackToArticleListWhenPersistedValueIsInvalid() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        store.save(store.load().copy(lastFocusedPane = "bogus"))
        val vm = newViewModel()
        subscribeAll(vm)

        assertEquals(HomePane.ArticleList, vm.getInitialFocusedPane())
    }

    @Test
    fun setUnreadOnlyAndToggleSortPersistToLocalSettings() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        assertNull(store.load().lastUnreadOnly)
        assertNull(store.load().lastNewestFirst)

        vm.setUnreadOnly(true)
        assertEquals(true, store.load().lastUnreadOnly)

        vm.toggleSort()
        assertEquals(false, store.load().lastNewestFirst)
    }

    @Test
    fun restartRestoresUnreadOnlyAndNewestFirst() = runTest {
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.setUnreadOnly(true)
        vm1.toggleSort()

        val vm2 = newViewModel()
        subscribeAll(vm2)

        assertTrue(vm2.unreadOnly.value)
        assertFalse(vm2.newestFirst.value)
    }

    @Test
    fun unreadOnlyAndNewestFirstFallBackToDefaultsWhenNothingSaved() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertFalse(vm.unreadOnly.value)
        assertTrue(vm.newestFirst.value)
    }

    @Test
    fun legacyUnreadFilterMigratesToAllFilterWithUnreadOnlyForced() = runTest {
        // Simulates a user who had the removed `ArticleFilter.Unread` selected before upgrading:
        // the persisted "unread" lastFilter should now map to All + unreadOnly=true.
        val store = LocalSettingsStore(dirOverride = dir)
        store.save(store.load().copy(lastFilter = "unread"))

        val vm = newViewModel()
        subscribeAll(vm)

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertTrue(vm.unreadOnly.value)
    }

    // --- Add-feed preview / subscribe logic ---
    // runBlocking (not runTest) so real MockEngine socket I/O + HttpTimeout don't get a false
    // timeout under virtual time (see docs/testing.md).

    @Test
    fun resolvePreviewReturnsSingleForDirectFeed() = runBlocking {
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        val result = vm.resolvePreview("https://ex.com/feed")
        assertIs<AddFeedPreview.Single>(result)
        assertEquals("Feed", result.title)
        assertEquals(1, result.articleCount)
        assertEquals("https://ex.com/feed", result.resolvedUrl)
    }

    @Test
    fun resolvePreviewReturnsMultipleForHtmlWithFeedLinks() = runBlocking {
        val vm = newViewModel(feedFetcher = fetcherWith { respond(DISCOVERY_HTML, HttpStatusCode.OK) })
        val result = vm.resolvePreview("https://ex.com/")
        assertIs<AddFeedPreview.Multiple>(result)
        assertEquals(
            listOf("https://ex.com/feed.xml", "https://ex.com/atom.xml"),
            result.candidates.map { it.url },
        )
    }

    @Test
    fun resolvePreviewFallsBackToHttpWhenSchemeOmitted() = runBlocking {
        // No scheme typed: https attempt 404s (non-discovery), so it retries over http, which serves
        // the feed. The resolved URL must reflect the http scheme that actually worked.
        val vm = newViewModel(
            feedFetcher = fetcherWith { request ->
                if (request.url.protocol.name == "https") respond("", HttpStatusCode.NotFound)
                else respond(RSS, HttpStatusCode.OK)
            },
        )
        val result = vm.resolvePreview("ex.com/feed")
        assertIs<AddFeedPreview.Single>(result)
        assertEquals("http://ex.com/feed", result.resolvedUrl)
    }

    @Test
    fun resolvePreviewReturnsFailedForNonDiscoveryError() {
        runBlocking {
            val vm = newViewModel(feedFetcher = fetcherWith { respond("", HttpStatusCode.NotFound) })
            val result = vm.resolvePreview("https://ex.com/feed")
            assertIs<AddFeedPreview.Failed>(result)
            assertIs<FeedNotFoundException>(result.exception)
        }
    }

    @Test
    fun subscribeFeedsTalliesSuccessesAndFailures() {
        runBlocking {
            val vm = newViewModel(
                feedFetcher = fetcherWith { request ->
                    if (request.url.host == "good.com") respond(RSS, HttpStatusCode.OK)
                    else respond("", HttpStatusCode.NotFound)
                },
            )
            val outcome = vm.subscribeFeeds(listOf("https://good.com/feed", "https://bad.com/feed"))
            assertEquals(1, outcome.successCount)
            assertEquals(1, outcome.failCount)
            assertIs<FeedNotFoundException>(outcome.firstError)
        }
    }

    @Test
    fun addFeedCanSubscribeReflectsPreviewAndSelection() {
        val candidates = listOf(DiscoveredFeedLink("https://ex.com/a"), DiscoveredFeedLink("https://ex.com/b"))
        val single = AddFeedPreview.Single("https://ex.com/feed", "Feed", 1)
        val multiple = AddFeedPreview.Multiple(candidates)

        assertFalse(addFeedCanSubscribe(null, emptySet()))
        assertTrue(addFeedCanSubscribe(single, emptySet()))
        assertFalse(addFeedCanSubscribe(multiple, emptySet()))
        assertTrue(addFeedCanSubscribe(multiple, setOf("https://ex.com/a")))
    }

    @Test
    fun addFeedAlreadySubscribedMatchesExistingUrlAfterSchemeNormalization() {
        db.insertFeed("f1", url = "https://feed/f1")
        val feeds = db.feedsQueries.watchAll().executeAsList()

        assertFalse(addFeedAlreadySubscribed("", feeds))
        assertFalse(addFeedAlreadySubscribed("https://feed/other", feeds))
        assertTrue(addFeedAlreadySubscribed("https://feed/f1", feeds))
        // No scheme typed: withDefaultScheme prepends https:// before comparing.
        assertTrue(addFeedAlreadySubscribed("feed/f1", feeds))
    }
}

private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

private const val DISCOVERY_HTML = """<html><head>
<link rel="alternate" type="application/rss+xml" href="/feed.xml" title="RSS"/>
<link rel="alternate" type="application/atom+xml" href="/atom.xml" title="Atom"/>
</head><body>site</body></html>"""

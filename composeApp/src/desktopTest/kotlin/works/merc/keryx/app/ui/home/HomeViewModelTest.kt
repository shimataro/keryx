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
import kotlinx.coroutines.CoroutineDispatcher
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
import works.merc.keryx.app.core.ARTICLE_CONTENT_CACHE_LIMIT
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.singleProviderCloudSession
import works.merc.keryx.app.data.cloud.TokenClearOutcome
import works.merc.keryx.app.data.cloud.TokenSaveOutcome
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ActivitySnapshot
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.addFeedAlreadySubscribed
import works.merc.keryx.app.domain.addFeedCanSubscribe
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.RefreshCycleRunner
import works.merc.keryx.app.domain.FakeNotificationMessages
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.CountingSqlDriver
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
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

/** In-memory [TokenStorage] fake (never actually used since appKey is empty in these tests). */
private class HomeViewModelTestTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null

    /** How often the secret store was read — see `cloudConnectedDoesNotReadTokenStorageWhenObserved`. */
    var loadCount = 0
        private set

    override fun save(tokens: OAuthTokens): TokenSaveOutcome {
        stored = tokens
        return TokenSaveOutcome.SECURE
    }

    override fun load(): OAuthTokens? {
        loadCount++
        return stored
    }

    override fun clear(): TokenClearOutcome {
        stored = null
        return TokenClearOutcome.CLEARED
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
        // Overridable so a test can pause a read/star write mid-flight (e.g. with a virtual-time
        // StandardTestDispatcher) instead of the default Unconfined, which always runs it inline.
        dbWriteDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        // Consulted once per sync() — a test can count invocations to tell whether a sync ran.
        // Passing one also connects the CloudSession (a client ID plus stored tokens), since a
        // refresh-then-sync cycle only syncs while a provider is connected.
        cloudProvider: (() -> works.merc.keryx.app.data.cloud.CloudStorage?)? = null,
    ): HomeViewModel {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so the subscribe/refresh path's indexMissing() works.
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManagerIndexed(driver), syncScheduler,
            NotificationCenter(), FakeNotificationMessages(), clock, Dispatchers.Unconfined,
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
            cloudProvider = cloudProvider ?: { null },
            clock = clock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            activityCenter = activityCenter,
            notificationCenter = NotificationCenter(),
            notificationMessages = FakeNotificationMessages(),
            localDbPath = "unused",
            tempDir = "unused",
        )
        val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(authClient, clock = clock)
        if (cloudProvider != null) tokenStorage.save(OAuthTokens(accessToken = "token"))
        val cloudSession = singleProviderCloudSession(
            client = authClient,
            tokenStorage = tokenStorage,
            authManager = authManager,
            clientId = if (cloudProvider != null && appKey.isEmpty()) "APPKEY" else appKey,
            clock = clock,
        )
        val refreshCycleRunner = RefreshCycleRunner(
            activityCenter, feedRepository, syncRepository, cloudSession, newArticleNotifier,
            settingsRepository, FakeNotificationMessages(),
        )
        return HomeViewModel(
            feedRepository, articleRepository, tagRepository, folderRepository, settingsRepository,
            syncRepository, cloudSession, activityCenter, clock, refreshCycleRunner,
            Dispatchers.Unconfined,
            // dbWriteDispatcher: Unconfined by default so read/star writes run inline for
            // deterministic assertions; overridable via the dbWriteDispatcher parameter above.
            dbWriteDispatcher,
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
        backgroundScope.launch { vm.pagerArticles.collect {} }
        backgroundScope.launch { vm.newArticleCount.collect {} }
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
    fun hasAnyFeedIsFalseWithNoFeedsAndTrueOnceOneExists() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.hasAnyFeed())

        db.insertFeed("f1")

        assertEquals(true, vm.hasAnyFeed())
    }

    @Test
    fun feedRefreshingReflectsActivityCenter() = runTest {
        // Unconfined scope so the gated track call below starts (and registers) inline.
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(activityCenter = activityCenter)
            assertFalse(vm.activity.value.feedRefreshing)

            // Hold a refresh open on the injected ActivityCenter; the VM must expose the same state.
            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackFeedRefresh { gate.await() } }
            assertTrue(vm.activity.value.feedRefreshing)

            gate.complete(Unit)
            job.join()
            assertFalse(vm.activity.value.feedRefreshing)
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun refreshAllIsGuardedWhileAlreadyRefreshing() = runTest {
        db.insertFeed("f1")
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(activityCenter = activityCenter)
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            // Simulate an in-flight refresh (e.g. the background loop) holding the indicator on.
            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackFeedRefresh { gate.await() } }
            assertTrue(vm.activity.value.feedRefreshing)

            // A manual refresh while busy must be a no-op (guard) and must not throw or clear state early.
            vm.refreshAll()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.activity.value.feedRefreshing)

            gate.complete(Unit)
            job.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.activity.value.feedRefreshing)
        } finally {
            activityScope.cancel()
        }
    }

    /**
     * Pumps the virtual scheduler with short real sleeps until [done] holds: feed fetches resume on
     * Ktor's real MockEngine dispatcher, off the test scheduler (see docs/testing.md), so a single
     * advanceUntilIdle() can race them.
     */
    private fun TestScope.pumpUntil(done: () -> Boolean) {
        var waited = 0
        while (!done() && waited < 5_000) {
            testScheduler.advanceUntilIdle()
            Thread.sleep(50)
            waited += 50
        }
        testScheduler.advanceUntilIdle()
    }

    /** Whether any pull-to-refresh is still pending, whichever selection it was started on. */
    private val HomeViewModel.pulling: Boolean get() = pullRefreshingFilters.value.isNotEmpty()

    /** A no-cloud provider for [newViewModel] that counts how many syncs consulted it. */
    private class CountingNoCloud : () -> works.merc.keryx.app.data.cloud.CloudStorage? {
        val syncs = java.util.concurrent.atomic.AtomicInteger(0)
        override fun invoke(): works.merc.keryx.app.data.cloud.CloudStorage? {
            syncs.incrementAndGet()
            return null
        }
    }

    @Test
    fun pullToRefreshKeepsTheIndicatorUpThroughRefreshAndSync() = runTest {
        db.insertFeed("f1")
        val gate = CompletableDeferred<Unit>()
        val activityCenter = ActivityCenter()
        val cloud = CountingNoCloud()
        val vm = newViewModel(
            feedFetcher = fetcherWith { gate.await(); respond(RSS, HttpStatusCode.OK) },
            activityCenter = activityCenter,
            cloudProvider = cloud,
        )
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        // Raised synchronously, before the refresh coroutine has even started running.
        assertTrue(vm.pulling)
        testScheduler.runCurrent()
        assertTrue(activityCenter.activity.value.feedRefreshing)
        assertTrue(vm.pulling)

        gate.complete(Unit)
        pumpUntil { !vm.pulling }

        assertFalse(vm.pulling)
        assertFalse(activityCenter.activity.value.feedRefreshing)
        assertEquals(1, cloud.syncs.get(), "the pull must sync after refreshing, like refreshAll()")
        assertEquals(1, db.articlesQueries.watchAll().executeAsList().size)
    }

    @Test
    fun pullToRefreshInAFolderFetchesOnlyThatFoldersFeeds() = runTest {
        db.insertFolder("d1", "Folder")
        db.insertFeed("f1", folderId = "d1")
        db.insertFeed("f2", folderId = "d1")
        db.insertFeed("f3")
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val activityCenter = ActivityCenter()
        val vm = newViewModel(
            feedFetcher = fetcherWith { request ->
                requested += request.url.toString()
                respond("", HttpStatusCode.NotFound)
            },
            activityCenter = activityCenter,
        )
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Folder("d1"))
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        pumpUntil { !vm.pulling }

        assertFalse(vm.pulling)
        assertEquals(setOf("https://feed/f1", "https://feed/f2"), requested.toSet())
    }

    @Test
    fun pullToRefreshDuringAnInFlightRefreshJoinsItWithoutFetching() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            // Simulate an in-flight refresh (e.g. the background loop).
            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackFeedRefresh { gate.await() } }
            assertTrue(vm.activity.value.feedRefreshing)

            vm.pullToRefresh()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.pulling, "the pull waits for the in-flight refresh")
            assertEquals(0, fetches.get())

            gate.complete(Unit)
            job.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.pulling)
            assertEquals(0, fetches.get(), "joining must not start a refresh of its own")
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun pullToRefreshDuringASyncWaitsForItWithoutRefreshing() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            val job = activityScope.launch { activityCenter.trackSync { gate.await() } }
            assertTrue(vm.activity.value.syncing)

            vm.pullToRefresh()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.pulling)
            assertFalse(vm.activity.value.feedRefreshing)

            gate.complete(Unit)
            job.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.pulling)
            assertFalse(vm.activity.value.feedRefreshing)
            assertEquals(0, fetches.get())
        } finally {
            activityScope.cancel()
        }
    }

    /**
     * Regression: a refresh-then-sync cycle (e.g. the background loop) has a gap between its
     * refresh and its sync where neither [ActivitySnapshot.feedRefreshing] nor
     * [ActivitySnapshot.syncing] is up. A pull that joined the cycle used to treat that gap as
     * "done" and drop its indicator before the sync had even started.
     */
    @Test
    fun pullToRefreshJoiningARefreshCycleStaysUpAcrossTheGapBeforeItsSync() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            val refreshGate = CompletableDeferred<Unit>()
            val gap = CompletableDeferred<Unit>()
            val syncGate = CompletableDeferred<Unit>()
            val cycle = activityScope.launch {
                activityCenter.trackRefreshCycle {
                    activityCenter.trackFeedRefresh { refreshGate.await() }
                    gap.await()
                    activityCenter.trackSync { syncGate.await() }
                }
            }

            vm.pullToRefresh()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.pulling)

            // Into the gap: the refresh is over and the sync hasn't started yet.
            refreshGate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertFalse(activityCenter.activity.value.feedRefreshing)
            assertFalse(activityCenter.activity.value.syncing)
            assertTrue(vm.pulling, "the pull must wait for the cycle's sync, not just its refresh")

            gap.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(activityCenter.activity.value.syncing)
            assertTrue(vm.pulling)

            syncGate.complete(Unit)
            cycle.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.pulling)
            assertEquals(0, fetches.get(), "joining must not start a refresh of its own")
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun refreshAllStartsNothingInTheGapOfARefreshCycle() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val cloud = CountingNoCloud()
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
                cloudProvider = cloud,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            // A cycle sitting in the gap between its refresh and its sync: only the cycle flag is up.
            val gap = CompletableDeferred<Unit>()
            val cycle = activityScope.launch { activityCenter.trackRefreshCycle { gap.await() } }
            assertTrue(vm.activity.value.refreshCycleRunning)
            assertFalse(vm.activity.value.feedRefreshing)

            vm.refreshAll()
            // Give a wrongly started refresh time to reach the (real-dispatcher) MockEngine.
            repeat(5) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(50)
            }
            assertEquals(0, fetches.get(), "refreshAll must not refresh while a cycle is running")
            assertEquals(0, cloud.syncs.get(), "refreshAll must not sync while a cycle is running")

            gap.complete(Unit)
            cycle.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.activity.value.refreshCycleRunning)
        } finally {
            activityScope.cancel()
        }
    }

    /**
     * A feed fetcher that counts its requests and holds each one at [gate], so a refresh stays in
     * flight until the test releases it.
     */
    private fun gatedCountingFetcher(gate: CompletableDeferred<Unit>, fetches: java.util.concurrent.atomic.AtomicInteger) =
        fetcherWith {
            fetches.incrementAndGet()
            gate.await()
            respond("", HttpStatusCode.NotFound)
        }

    /**
     * Regression: [HomeViewModel.refreshAll]'s double-start guard used to read ActivityCenter's
     * per-flag `stateIn` copies, which only catch up once their sharing coroutine is dispatched
     * (on `Dispatchers.Default` for the default [ActivityCenter]), so a second call right after the
     * first could still see "not refreshing" and start a second refresh.
     *
     * Main is unconfined here, like production's `Main.immediate` on the UI thread: each
     * `viewModelScope.launch` runs inline up to its first suspension, so the first call's refresh
     * cycle is already registered by the time it returns, and only a stale read could let the
     * second call through.
     */
    @Test
    fun refreshAllTwiceInARowStartsASingleRefresh() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        db.insertFeed("f1")
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val cloud = CountingNoCloud()
        val vm = newViewModel(feedFetcher = gatedCountingFetcher(gate, fetches), cloudProvider = cloud)
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.refreshAll()
        vm.refreshAll()

        gate.complete(Unit)
        pumpUntil { vm.activity.value.idle }

        assertTrue(vm.activity.value.idle)
        assertEquals(1, fetches.get(), "the second refreshAll() must be a no-op while the first runs")
        assertEquals(1, cloud.syncs.get())
    }

    /**
     * Regression: same stale read as [refreshAllTwiceInARowStartsASingleRefresh], via
     * [HomeViewModel.pullToRefresh]'s own "anything in flight?" check — a pull right after
     * [HomeViewModel.refreshAll] started a refresh of its own instead of waiting for that one.
     */
    @Test
    fun pullToRefreshRightAfterRefreshAllWaitsForItWithoutStartingAnother() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        db.insertFeed("f1")
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val cloud = CountingNoCloud()
        val vm = newViewModel(feedFetcher = gatedCountingFetcher(gate, fetches), cloudProvider = cloud)
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.refreshAll()
        vm.pullToRefresh()
        pumpUntil { fetches.get() >= 1 }
        assertTrue(vm.pulling, "the pull waits for the in-flight refresh")

        gate.complete(Unit)
        pumpUntil { !vm.pulling }

        assertFalse(vm.pulling)
        assertTrue(vm.activity.value.idle, "the pull only finishes once the refresh and its sync have")
        assertEquals(1, fetches.get(), "the pull must join the running refresh, not start its own")
        assertEquals(1, cloud.syncs.get())
    }

    @Test
    fun repeatedPullsStartASingleRefresh() = runTest {
        db.insertFeed("f1")
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityCenter = ActivityCenter()
        val vm = newViewModel(
            feedFetcher = fetcherWith {
                fetches.incrementAndGet()
                gate.await()
                respond("", HttpStatusCode.NotFound)
            },
            activityCenter = activityCenter,
        )
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        vm.pullToRefresh()
        testScheduler.runCurrent()
        vm.pullToRefresh()
        testScheduler.runCurrent()

        gate.complete(Unit)
        pumpUntil { !vm.pulling }

        assertFalse(vm.pulling)
        assertEquals(1, fetches.get())
    }

    @Test
    fun pullToRefreshOnASelectionWithNoFeedsNeitherFetchesNorSyncs() = runTest {
        db.insertFolder("d1", "Empty folder")
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityCenter = ActivityCenter()
        val cloud = CountingNoCloud()
        val vm = newViewModel(
            feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
            activityCenter = activityCenter,
            cloudProvider = cloud,
        )
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Folder("d1"))
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        testScheduler.advanceUntilIdle()

        assertFalse(vm.pulling)
        assertEquals(0, fetches.get())
        assertEquals(0, cloud.syncs.get(), "an empty target must not sync")
    }

    @Test
    fun thePullIndicatorBelongsToTheSelectionThePullStartedOn() = runTest {
        db.insertFeed("f1")
        db.insertFeed("f2")
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val vm = newViewModel(feedFetcher = gatedCountingFetcher(gate, fetches))
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        pumpUntil { fetches.get() >= 1 }
        assertEquals(setOf<ArticleFilter>(ArticleFilter.Feed("f1")), vm.pullRefreshingFilters.value)

        // Another selection is not the list being refreshed...
        vm.selectFilter(ArticleFilter.Feed("f2"))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.filter.value in vm.pullRefreshingFilters.value)

        // ...and switching back shows the still-running pull again.
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.filter.value in vm.pullRefreshingFilters.value)

        gate.complete(Unit)
        pumpUntil { !vm.pulling }
        assertEquals(emptySet(), vm.pullRefreshingFilters.value)
        assertEquals(1, fetches.get(), "only the first selection's feed is fetched")
    }

    @Test
    fun aPullOnAnotherSelectionWaitsAndStaysUpUntilEverythingFinishes() = runTest {
        db.insertFeed("f1")
        db.insertFeed("f2")
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val cloud = CountingNoCloud()
        val vm = newViewModel(feedFetcher = gatedCountingFetcher(gate, fetches), cloudProvider = cloud)
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()

        vm.pullToRefresh()
        pumpUntil { fetches.get() >= 1 }
        vm.selectFilter(ArticleFilter.Feed("f2"))
        testScheduler.advanceUntilIdle()
        vm.pullToRefresh()
        testScheduler.advanceUntilIdle()

        assertEquals(
            setOf<ArticleFilter>(ArticleFilter.Feed("f1"), ArticleFilter.Feed("f2")),
            vm.pullRefreshingFilters.value,
        )

        gate.complete(Unit)
        pumpUntil { !vm.pulling }

        assertEquals(emptySet(), vm.pullRefreshingFilters.value)
        assertTrue(vm.activity.value.idle)
        assertEquals(1, fetches.get(), "the second pull must wait for the first, not start its own")
        assertEquals(1, cloud.syncs.get())
    }

    @Test
    fun aPullWaitsForACycleSomeoneElseClaimedFirst() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            val other = activityScope.launch { activityCenter.tryTrackRefreshCycle { gate.await() } }
            assertTrue(activityCenter.activity.value.refreshCycleRunning)

            vm.pullToRefresh()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.pulling, "the pull waits for the cycle that got there first")

            gate.complete(Unit)
            other.join()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.pulling)
            assertEquals(0, fetches.get(), "waiting must not start a refresh of its own")
        } finally {
            activityScope.cancel()
        }
    }

    @Test
    fun refreshAllDuringASyncStartsNothing() = runTest {
        db.insertFeed("f1")
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter()
        try {
            val cloud = CountingNoCloud()
            val vm = newViewModel(
                feedFetcher = fetcherWith { fetches.incrementAndGet(); respond("", HttpStatusCode.NotFound) },
                activityCenter = activityCenter,
                cloudProvider = cloud,
            )
            subscribeAll(vm)
            testScheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            val sync = activityScope.launch { activityCenter.trackSync { gate.await() } }

            vm.refreshAll()
            repeat(5) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(50)
            }
            gate.complete(Unit)
            sync.join()
            testScheduler.advanceUntilIdle()

            assertEquals(0, fetches.get(), "refreshAll must not refresh while a sync is running")
            assertEquals(0, cloud.syncs.get(), "refreshAll must not queue a sync of its own either")
            assertTrue(vm.activity.value.idle)
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
        testScheduler.advanceUntilIdle()

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
        // Nothing about the dead article may be applied: pinning it would let the `articles` merge
        // step re-add it to the visible list, and persisting it would restore it on the next launch.
        assertTrue(vm.articles.value.none { it.id == "a2" && it.is_read == 1L })
        assertEquals("a1", LocalSettingsStore(dirOverride = dir).load().lastArticleId)
    }

    /**
     * The body load runs off the UI thread, so several selections can be in flight at once. Only
     * the newest may reach the reader — an earlier lookup that happened to finish later must not
     * put its article back on screen. Every article passed over is still marked read, because
     * selecting an article marks it read (external-spec §7) whether or not it stays selected.
     */
    @Test
    fun rapidSelectionsApplyOnlyTheNewestButMarkEveryTraversedArticleRead() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // No pump in between: all three hydrations are in flight together.
        vm.selectArticle(vm.articles.value.first { it.id == "a1" })
        vm.selectArticle(vm.articles.value.first { it.id == "a2" })
        vm.selectArticle(vm.articles.value.first { it.id == "a3" })
        testScheduler.advanceUntilIdle()

        assertEquals("a3", vm.selectedArticle.value?.id)
        assertEquals("a3", LocalSettingsStore(dirOverride = dir).load().lastArticleId)
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a3").executeAsOne().is_read)
    }

    /**
     * Keyboard navigation steps from a synchronous cursor, not from [HomeViewModel.selectedArticle]
     * (which only catches up once the body has loaded). Stepping from the latter would make a held
     * arrow key re-read the same stale index on every repeat and never advance past the first row.
     */
    @Test
    fun selectNextStepsOneRowPerCallWithoutWaitingForTheBodyToLoad() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 1L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // Three repeats with no pump, as a held arrow key produces.
        vm.selectNext()
        vm.selectNext()
        vm.selectNext()
        testScheduler.advanceUntilIdle()

        assertEquals("a3", vm.selectedArticle.value?.id)
    }

    /**
     * A filter switch clears the selection and every pin. A body load still in flight across that
     * switch must not restore either — re-adding the pin would put an article from the previous
     * scope back into the new one, since the `articles` merge re-adds any pinned id.
     */
    @Test
    fun switchingFilterCancelsASelectionWhoseBodyIsStillLoading() = runTest {
        db.insertFeed("f1")
        db.insertFeed("f2")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f2", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        vm.selectArticle(vm.articles.value.first { it.id == "a1" })
        vm.selectFilter(ArticleFilter.Feed("f2"))
        testScheduler.advanceUntilIdle()

        assertNull(vm.selectedArticle.value)
        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
        assertNull(LocalSettingsStore(dirOverride = dir).load().lastArticleId)
    }

    /**
     * The same switch, but with a selection made under the *new* filter before the old body load
     * lands. That refills the selection cursor, so a null check alone reads the stale hydration as
     * still current and pins its article — which the `articles` merge then re-adds to a list it
     * does not belong to. Only the browsing epoch can tell the two apart.
     */
    @Test
    fun aSelectionUnderTheNewFilterDoesNotLetAStaleLoadPinThePreviousFiltersArticle() = runTest {
        db.insertFeed("f1")
        db.insertFeed("f2")
        db.insertArticle("a1", "f1", isRead = 0L)
        db.insertArticle("a2", "f2", isRead = 0L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // Read from the DB rather than the list: after the switch below, vm.articles hasn't
        // recomputed yet, and pumping it would complete the very hydration this test holds open.
        val a1 = db.articlesQueries.getById("a1").executeAsOne().toListRow()
        val a2 = db.articlesQueries.getById("a2").executeAsOne().toListRow()

        // No pump in between: a1's body load is still in flight across both the switch and the
        // selection that follows it.
        vm.selectArticle(a1)
        vm.selectFilter(ArticleFilter.Feed("f2"))
        vm.selectArticle(a2)
        testScheduler.advanceUntilIdle()

        assertEquals("a2", vm.selectedArticle.value?.id)
        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
    }

    /** The same guard at startup: a tombstone that landed while the app was closed. */
    @Test
    fun restoringALastArticleThatWasTombstonedWhileClosedSelectsNothing() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 1L)
        val store = LocalSettingsStore(dirOverride = dir)
        store.save(store.load().copy(lastArticleId = "a1"))
        driver.stampArticleDeleted("a1", deletedAt = 10L)

        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        assertNull(vm.selectedArticle.value)
        assertTrue(vm.articles.value.none { it.id == "a1" })
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
        testScheduler.advanceUntilIdle()

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

        // selectedArticle only catches up once the body has loaded, so each step is pumped before
        // it is asserted. The cursor that drives the stepping itself is synchronous — see
        // [selectNextStepsOneRowPerCallWithoutWaitingForTheBodyToLoad].
        // No selection yet: selectNext should select the first item.
        vm.selectNext()
        testScheduler.advanceUntilIdle()
        assertEquals("a1", vm.selectedArticle.value?.id)

        vm.selectNext()
        testScheduler.advanceUntilIdle()
        assertEquals("a2", vm.selectedArticle.value?.id)

        vm.selectNext()
        testScheduler.advanceUntilIdle()
        assertEquals("a3", vm.selectedArticle.value?.id)

        // Clamp at the last item.
        vm.selectNext()
        testScheduler.advanceUntilIdle()
        assertEquals("a3", vm.selectedArticle.value?.id)

        vm.selectPrevious()
        testScheduler.advanceUntilIdle()
        assertEquals("a2", vm.selectedArticle.value?.id)

        vm.selectPrevious()
        testScheduler.advanceUntilIdle()
        assertEquals("a1", vm.selectedArticle.value?.id)

        // Clamp at the first item.
        vm.selectPrevious()
        testScheduler.advanceUntilIdle()
        assertEquals("a1", vm.selectedArticle.value?.id)
    }

    @Test
    fun canSelectNextAndCanSelectPreviousReflectPositionAndClampAtEnds() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 1L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isRead = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isRead = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // No selection yet: the reader has nothing on screen to swipe away from.
        assertFalse(vm.canSelectNext())
        assertFalse(vm.canSelectPrevious())

        vm.selectArticle(vm.articles.value.first { it.id == "a1" })
        testScheduler.advanceUntilIdle()
        // First article: nothing to move back to.
        assertTrue(vm.canSelectNext())
        assertFalse(vm.canSelectPrevious())

        vm.selectNext()
        testScheduler.advanceUntilIdle()
        // Middle article: both directions available.
        assertTrue(vm.canSelectNext())
        assertTrue(vm.canSelectPrevious())

        vm.selectNext()
        testScheduler.advanceUntilIdle()
        // Last article: nothing further to move to.
        assertFalse(vm.canSelectNext())
        assertTrue(vm.canSelectPrevious())
    }

    @Test
    fun canSelectNextAndCanSelectPreviousAreFalseForAnEmptyList() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        assertFalse(vm.canSelectNext())
        assertFalse(vm.canSelectPrevious())
    }

    @Test
    fun canSelectNextAndCanSelectPreviousReflectPositionWithinSearchResults() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 1L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 1L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)

        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        val results = vm.searchResults.value.map { it.article }
        assertEquals(2, results.size)

        vm.selectArticle(results[0])
        testScheduler.advanceUntilIdle()
        assertTrue(vm.canSelectNext())
        assertFalse(vm.canSelectPrevious())
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
    fun selectFilterOnSameFilterStillMovesTheSelectedRowInstance() = runTest {
        // Clicking the tag-nested copy of a feed already selected under its folder must move the
        // primary highlight to that row without disturbing the loaded article/pin state (the
        // filter itself is unchanged, so none of selectFilter's reset side effects may fire).
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
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), vm.selectedRowInstance.value)

        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))
        testScheduler.advanceUntilIdle()

        assertEquals(FeedListRowSelection.FeedInTag("f1", "t1"), vm.selectedRowInstance.value)
        assertEquals(ArticleFilter.Feed("f1"), vm.filter.value)
        assertEquals("a1", vm.selectedArticle.value?.id)
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun selectFilterWithoutAnInstanceFallsBackToTheCanonicalRow() = runTest {
        // The ~40 call sites that pass only a filter (search jump, notification action, menus) must
        // land on the folder-group row, not keep a stale tag-nested instance.
        db.insertFeed("f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))
        testScheduler.advanceUntilIdle()

        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertEquals(FeedListRowSelection.Starred, vm.selectedRowInstance.value)

        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), vm.selectedRowInstance.value)

        // Same-filter early-return path: selecting a tag-nested instance while its filter is
        // already active, then re-selecting the bare filter, must demote it too.
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), vm.selectedRowInstance.value)
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
        testScheduler.advanceUntilIdle()
        vm.toggleStar(db.articlesQueries.getById("a1").executeAsOne().toListRow())

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_starred)
        assertEquals(1L, vm.selectedArticle.value?.is_starred)
    }

    @Test
    fun unstarringArticleUnderStarredFilterKeepsItPinnedInList() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()

        // a2 is now unstarred in the DB but stays visible in the Starred list because it's pinned.
        assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
        assertEquals(0L, vm.articles.value.first { it.id == "a2" }.is_starred)
    }

    @Test
    fun unstarringSelectedArticleUnderStarredFilterKeepsKeyboardNavigationWorking() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 3L, createdAt = 3L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a3", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        val a2 = db.articlesQueries.getById("a2").executeAsOne()
        vm.selectArticle(a2.toListRow())
        testScheduler.advanceUntilIdle()

        vm.toggleStarSelected()
        testScheduler.advanceUntilIdle()

        // The unstarred, still-selected a2 stays in place rather than being spliced out.
        assertEquals(listOf("a1", "a2", "a3"), vm.articles.value.map { it.id })

        // Stepping past it lands on its actual neighbor, not a reset to the top of the list.
        vm.selectNext()
        testScheduler.advanceUntilIdle()
        assertEquals("a3", vm.selectedArticle.value?.id)
    }

    @Test
    fun switchingFilterAwayAndBackDropsTheUnstarredPin() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()

        // The pin was reset by the filter switch; a2 is genuinely unstarred, so it's gone.
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun restarringAnUnstarredPinnedArticleSettlesBackToTheUnpinnedRow() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        vm.toggleStar(vm.articles.value.first { it.id == "a2" })
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
        assertEquals(1L, vm.articles.value.first { it.id == "a2" }.is_starred)
    }

    /**
     * Regression guard for a "scrolls down by one row" UX glitch: re-starring updates the pin to
     * the confirmed value (rather than clearing it) precisely so the article's presence in the
     * merged list stays continuous across the write, which is what this test pins down by
     * inspecting the list *before* the (here, deliberately delayed) DB write has completed. A
     * naive eager-clear would drop the article from the list for this window, which is what trips
     * a LazyColumn keyed by article id into shifting its scroll anchor to the next row.
     */
    @Test
    fun restarringAnArticleNeverDropsItFromTheMergedListWhileTheWriteIsInFlight() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        // A controllable (non-inline) write dispatcher, so the DB write triggered by the second
        // toggleStar() call below stays queued until explicitly advanced, mirroring production
        // where dbWriteDispatcher is genuinely asynchronous.
        val vm = newViewModel(dbWriteDispatcher = StandardTestDispatcher(testScheduler))
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        vm.toggleStar(vm.articles.value.first { it.id == "a2" })

        // The write hasn't run yet (dbWriteDispatcher is still queued), but the merged list must
        // already show a2 continuously via the updated pin.
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })

        testScheduler.advanceUntilIdle()
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertEquals(listOf("a1", "a2"), vm.articles.value.map { it.id })
    }

    @Test
    fun unstarringArticleUnderStarredFilterResolvesTheFieldWhileTheWriteIsInFlight() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isStarred = 1L, publishedAt = 1L, createdAt = 1L)
        // A controllable (non-inline) write dispatcher, so the unstar write stays queued until
        // explicitly advanced, mirroring production where dbWriteDispatcher is genuinely asynchronous.
        val vm = newViewModel(dbWriteDispatcher = StandardTestDispatcher(testScheduler))
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()

        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow())

        // The write hasn't landed yet, so a2 is still is_starred=1 in `list` (the raw query result) —
        // the merge must still resolve the pin's field onto that already-present row, or the star icon
        // would flash as still-starred until the write commits.
        assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertEquals(0L, vm.articles.value.first { it.id == "a2" }.is_starred)

        testScheduler.advanceUntilIdle()
        assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_starred)
        assertEquals(0L, vm.articles.value.first { it.id == "a2" }.is_starred)
    }

    @Test
    fun markingArticleReadResolvesTheFieldWhileTheWriteIsInFlight() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        // watchArticles(All) doesn't filter by read state, so a1 stays present in `list` throughout —
        // it's the row's own is_read field, not list membership, that would otherwise read stale.
        val vm = newViewModel(dbWriteDispatcher = StandardTestDispatcher(testScheduler))
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.toggleRead(vm.articles.value.first { it.id == "a1" })

        // The write hasn't landed yet, but the merged list must resolve the pin's is_read onto the
        // already-present row.
        assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, vm.articles.value.first { it.id == "a1" }.is_read)

        testScheduler.advanceUntilIdle()
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, vm.articles.value.first { it.id == "a1" }.is_read)
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
        testScheduler.advanceUntilIdle()
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
        val activityCenter = ActivityCenter()
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
        assertTrue(activityCenter.activity.value.feedRefreshing)
        // The refresh is genuinely in flight here — simulate the user moving on to a2 before it completes.
        val article2 = db.articlesQueries.getById("a2").executeAsOne()
        vm.selectArticle(article2.toListRow())
        gate.complete(Unit)

        // The fetch resumes off the virtual scheduler (real MockEngine dispatch, see docs/testing.md),
        // so poll with short real sleeps until refreshAll settles (mirrors
        // refreshAllRaisesTrayNotificationWhenNewArticlesArrive's established pattern).
        var waited = 0
        while (activityCenter.activity.value.feedRefreshing && waited < 5_000) {
            testScheduler.advanceUntilIdle()
            Thread.sleep(50)
            waited += 50
        }
        testScheduler.advanceUntilIdle()

        // Only a2 (the current selection) should remain pinned; a1 must not survive the refresh.
        assertEquals(listOf("a2"), vm.articles.value.map { it.id })
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
        testScheduler.advanceUntilIdle()
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
     * The read pin is a deliberately optimistic cache (see [HomeViewModel.reconcilePinnedArticles]'s
     * own KDoc) — it must not hide an external change forever. Mirrors
     * [pinnedArticleSoftDeletedByAnotherDeviceIsDroppedFromPinsReactively] above, but for another
     * device syncing a "mark unread" instead of a tombstone. Asserted via the row's own `is_read`
     * field, not list membership: under [ArticleFilter.All] (no unread-only) a1 stays in the list
     * either way, so only the field's value tells a stale pin from a dropped one apart.
     */
    @Test
    fun pinnedArticleMarkedUnreadByAnotherDeviceShowsAsUnreadAgainReactively() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        vm.selectArticle(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        // Selecting a1 marks it read and pins it, so it displays as read.
        assertEquals(1L, vm.articles.value.first { it.id == "a1" }.is_read)

        // Simulate another device's sync propagating a "mark unread" for the pinned article.
        db.articlesQueries.updateReadStatus(is_read = 0L, read_at = null, updated_at = 200L, id = "a1")
        vm.toggleStar(db.articlesQueries.getById("a2").executeAsOne().toListRow()) // ticks articleChangeSignal
        testScheduler.advanceUntilIdle()

        // The pin must have been dropped, so the row now reflects the DB's current (unread) value.
        assertEquals(0L, vm.articles.value.first { it.id == "a1" }.is_read)
    }

    /**
     * The star pin's equivalent of [pinnedArticleMarkedUnreadByAnotherDeviceShowsAsUnreadAgainReactively].
     * Asserted the same way, via `is_starred`, not list membership: [ArticleFilter.Starred]'s own
     * raw query would re-include a1 the moment it is genuinely re-starred regardless of the pin, so
     * only the field's value distinguishes a stale pin from a dropped one.
     */
    @Test
    fun pinnedArticleRestarredByAnotherDeviceShowsAsStarredAgainReactively() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isStarred = 1L, publishedAt = 2L, createdAt = 2L)
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.toggleStar(db.articlesQueries.getById("a1").executeAsOne().toListRow())
        testScheduler.advanceUntilIdle()
        // Unstarring while browsing Starred pins a1 in place so it doesn't vanish mid-browse.
        assertEquals(0L, vm.articles.value.single { it.id == "a1" }.is_starred)

        // Simulate another device's sync re-starring the article the optimistic unstar-pin hid.
        db.articlesQueries.updateStarStatus(is_starred = 1L, starred_at = 200L, updated_at = 200L, id = "a1")
        db.articlesQueries.updateReadStatus(is_read = 1L, read_at = 100L, updated_at = 100L, id = "a2") // ticks articleChangeSignal
        testScheduler.advanceUntilIdle()

        // The pin must have been dropped, so the row now reflects the DB's current (starred) value.
        assertEquals(1L, vm.articles.value.single { it.id == "a1" }.is_starred)
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
        testScheduler.advanceUntilIdle()
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
    fun unreadOnlyFiltersTheStarredFilterTheSameAsEveryOtherFilter() = runTest {
        db.insertFeed("f1")
        // Starred-but-unread is a state sync merge can genuinely produce — read/star are merged
        // independently (see MergeSql / db-schema.md) — so the toggle must not special-case Starred.
        db.insertArticle("a1", "f1", isRead = 0L, isStarred = 1L)
        db.insertArticle("a2", "f1", isRead = 1L, isStarred = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("a1", "a2"), vm.articles.value.map { it.id }.toSet())

        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a1"), vm.articles.value.map { it.id })
    }

    @Test
    fun unreadOnlyIsScopedIndependentlyForTheStarredFilter() = runTest {
        db.insertFeed("f1")
        // Most starred articles are already read by the time they're starred, so a global toggle
        // inherited from the feed list would leave the Starred view looking empty.
        db.insertArticle("a1", "f1", isRead = 1L, isStarred = 1L)
        val vm = newViewModel()
        subscribeAll(vm)

        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)

        // Switching to Starred does not inherit the feed list's "unread only" state — it starts
        // at its own (unset) default, so the already-read starred article is still shown.
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.unreadOnly.value)
        assertEquals(listOf("a1"), vm.articles.value.map { it.id })

        // Turning it on within Starred filters correctly, and does not touch the feed list's toggle.
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)
        assertEquals(emptyList(), vm.articles.value.map { it.id })

        // Switching back to All restores the feed list's own (still-on) toggle state.
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)
    }

    @Test
    fun setUnreadOnlyOnTheStarredFilterPersistsSeparatelyFromTheSharedToggle() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()

        vm.setUnreadOnly(true)

        assertEquals(true, store.load().lastUnreadOnlyStarred)
        assertNull(store.load().lastUnreadOnly)
    }

    @Test
    fun restartRestoresUnreadOnlyStarredIndependentlyFromTheSharedToggle() = runTest {
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm1.setUnreadOnly(true)

        val vm2 = newViewModel()
        subscribeAll(vm2)
        vm2.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertTrue(vm2.unreadOnly.value)

        vm2.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertFalse(vm2.unreadOnly.value)
    }

    @Test
    fun searchInheritsUnderlyingFilterUnreadOnlyState() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 1L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)

        // All filter with unread-only on: search inherits it.
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)

        vm.setSearchBarVisible(true)
        testScheduler.advanceUntilIdle()
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertTrue(vm.unreadOnly.value)
        assertEquals(emptyList(), vm.searchResults.value.map { it.article.id })

        // Switch to Starred filter with its own unread-only on: search inherits Starred's toggle.
        vm.setSearchBarVisible(false)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)

        vm.setSearchBarVisible(true)
        testScheduler.advanceUntilIdle()
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertTrue(vm.unreadOnly.value)
    }

    @Test
    fun setUnreadOnlyWhileSearchingWritesToUnderlyingFilterKey() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()
        subscribeAll(vm)

        // Searching on All writes to lastUnreadOnly.
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        vm.setUnreadOnly(true)

        assertEquals(true, store.load().lastUnreadOnly)
        assertNull(store.load().lastUnreadOnlyStarred)

        // Searching on Starred writes to lastUnreadOnlyStarred.
        vm.setSearchBarVisible(false)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        vm.setUnreadOnly(true)

        assertEquals(true, store.load().lastUnreadOnlyStarred)
    }

    @Test
    fun restartRestoresUnderlyingFilterToggleUsedBySearch() = runTest {
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        vm1.setSearchBarVisible(true)
        vm1.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertTrue(vm1.unreadOnly.value)

        val vm2 = newViewModel()
        subscribeAll(vm2)
        vm2.setSearchBarVisible(true)
        vm2.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertTrue(vm2.unreadOnly.value)

        vm2.setSearchBarVisible(false)
        testScheduler.advanceUntilIdle()
        assertTrue(vm2.unreadOnly.value)
    }

    @Test
    fun setUnreadOnlyAndToggleSortFlipExposedState() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertFalse(vm.unreadOnly.value)
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.unreadOnly.value)

        assertTrue(vm.newestFirst.value)
        vm.toggleSort()
        assertFalse(vm.newestFirst.value)
        vm.toggleSort()
        assertTrue(vm.newestFirst.value)
    }

    @Test
    fun setFeedListPaneWidthAndSetArticleListPaneWidthPersistAfterDebounce() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.setFeedListPaneWidth(300.0)
        vm.setArticleListPaneWidth(400.0)
        testScheduler.advanceUntilIdle()

        val settings = LocalSettingsStore(dirOverride = dir).load()
        assertEquals(300.0, settings.feedListPaneWidth)
        assertEquals(400.0, settings.articleListPaneWidth)
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
    fun legacySearchFilterDecodesToAll() {
        // "search" was Search's own encoding from when it was still a filter that could displace
        // the one being browsed. encode() no longer produces it (Search isn't an ArticleFilter any
        // more), but decode-only compatibility keeps an older-app-version persisted value from
        // resolving to null.
        assertEquals(ArticleFilter.All, decodeArticleFilter("search"))
    }

    @Test
    fun requestSearchFocusLatchesUntilConsumed() = runTest {
        val vm = newViewModel()

        assertEquals(false, vm.pendingSearchFocus.value)
        vm.requestSearchFocus()
        assertEquals(true, vm.pendingSearchFocus.value)
        // Deliberately not consumed here — this is the case a plain one-shot SharedFlow used to
        // drop silently when no collector existed yet (see requestSearchFocus's own KDoc): the
        // latch must still be true for whatever field composes and collects it later.
        assertEquals(true, vm.pendingSearchFocus.value)
    }

    @Test
    fun consumeSearchFocusRequestClearsTheLatch() = runTest {
        val vm = newViewModel()

        vm.requestSearchFocus()
        assertEquals(true, vm.pendingSearchFocus.value)
        vm.consumeSearchFocusRequest()
        assertEquals(false, vm.pendingSearchFocus.value)
    }

    @Test
    fun collapsingTheSearchBarDropsAnUnconsumedFocusRequest() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        vm.setSearchBarVisible(true)
        vm.requestSearchFocus()
        assertEquals(true, vm.pendingSearchFocus.value)

        // Closing the bar before any field consumed the request must drop it — otherwise it would
        // steal focus at whatever unrelated field appears next.
        vm.setSearchBarVisible(false)
        assertEquals(false, vm.pendingSearchFocus.value)
    }

    @Test
    fun selectFilterNeverTouchesAPendingSearchFocusRequest() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        vm.setSearchBarVisible(true)
        vm.requestSearchFocus()
        assertEquals(true, vm.pendingSearchFocus.value)

        // Search is orthogonal to the filter now, so switching feeds while the bar is open must not
        // disturb a focus request still waiting to be consumed — only setSearchBarVisible(false)
        // (closing the bar) does that.
        vm.selectFilter(ArticleFilter.Feed("f1"))
        assertEquals(true, vm.pendingSearchFocus.value)
    }

    @Test
    fun setSearchQueryDoesNotChangeTheFilter() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)

        assertEquals(ArticleFilter.All, vm.filter.value)

        // Typing narrows whatever filter is already selected rather than switching to one of its
        // own — the whole point of search being orthogonal to ArticleFilter.
        vm.setSearchQuery("ko")
        assertEquals(ArticleFilter.All, vm.filter.value)
        assertEquals("ko", vm.searchQuery.value)

        vm.selectFilter(ArticleFilter.Feed("f1"))
        assertEquals(ArticleFilter.Feed("f1"), vm.filter.value)
        // The query survives the filter switch — it now narrows the new filter instead.
        assertEquals("ko", vm.searchQuery.value)
    }

    @Test
    fun currentArticlesSwitchesSourceToSearchResultsWhileSearchActive() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")

        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        // Not searching: currentArticles (used by J/K/arrow navigation) mirrors the feed-backed list.
        assertEquals(setOf("a1", "a2"), vm.currentArticles().map { it.id }.toSet())

        // Once searchActive (bar open + non-empty query), it draws from searchResults instead —
        // empty here (no FTS index set up), which proves the source switched away from the feed
        // list. (Real FTS hit ranking is covered by FtsSearchTest.)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("kotlin")
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), vm.currentArticles())
    }

    @Test
    fun articlesDoesNotBrieflyGoEmptyWhenSearchEnds() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")

        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("a1", "a2"), vm.articles.value.map { it.id }.toSet())

        vm.setSearchBarVisible(true)
        vm.setSearchQuery("kotlin")
        testScheduler.advanceUntilIdle()

        // Exiting search must not cancel/restart the underlying watchArticles(f) query — `articles`
        // should already hold the correct list the instant search ends, with no async gap.
        // Deliberately no advanceUntilIdle() call before this assertion.
        vm.setSearchQuery("")
        assertEquals(setOf("a1", "a2"), vm.articles.value.map { it.id }.toSet())
    }

    @Test
    fun legacySearchFilterRestoresToAllOnRestart() = runTest {
        // Simulates a user who had the removed ArticleFilter.Search selected before upgrading (back
        // when it was still a filter of its own): the persisted "search" lastFilter must not
        // restore into an empty view, since the query text was never persisted.
        val store = LocalSettingsStore(dirOverride = dir)
        store.save(store.load().copy(lastFilter = "search"))

        val vm = newViewModel()

        assertEquals(ArticleFilter.All, vm.filter.value)
    }

    @Test
    fun theSearchQuerySurvivesAFilterSwitch() = runTest {
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        vm.setSearchQuery("kotlin")

        vm.selectFilter(ArticleFilter.Feed("f2"))

        assertEquals("kotlin", vm.searchQuery.value)
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

        // Too-short (1-character) query has no usable terms, so it's not "searching" (shows the
        // too-short hint) — SEARCH_MIN_TERM_LENGTH is 2, so "a" alone is dropped by searchTerms.
        vm.setSearchQuery("a")
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

    /**
     * Unlike a filter switch, a query change does not start a fresh browsing context — it narrows
     * the *same* one (see [HomeViewModel.setSearchQuery]'s own KDoc). A pin set under the previous
     * query must therefore survive: `search_text` no longer matching the new query is exactly the
     * "pinned but no longer in the raw results" case [searchResults] deliberately does *not* merge
     * back in (unlike the base `articles` list), so an old query's pin cannot resurface a stale
     * result under the new one.
     */
    @Test
    fun changingSearchQueryKeepsPinnedReadArticles() = runTest {
        db.insertFeed("f1")
        // a1 matches both queries, a2 only "Kotlin", a3 only "Java".
        db.insertArticle("a1", "f1", title = "Kotlin and Java", content = "kotlin java", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Only", content = "kotlin", isRead = 0L)
        db.insertArticle("a3", "f1", title = "Java Only", content = "java", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        vm.setUnreadOnly(true)
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        // a1 is read but pinned, so it stays visible under unread-only.
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        // Change query: the pin survives. "Java"'s own raw results (a1, a3) still contain a1, and
        // the pin resolves its read state the same way it did under "Kotlin" — so a1 stays visible.
        vm.setSearchQuery("Java")
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a3"), vm.searchResults.value.map { it.article.id }.toSet())
    }

    /**
     * The same survival, but with the query changed while the selected article's body is still
     * loading — unlike a filter switch, [HomeViewModel.setSearchQuery] no longer bumps the
     * browsing epoch, so the in-flight [HomeViewModel.selectArticle] completes and pins normally
     * rather than being vetoed as belonging to a since-abandoned context.
     */
    @Test
    fun changingSearchQueryKeepsAPinWhoseBodyIsStillLoading() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin and Java", content = "kotlin java", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Only", content = "kotlin", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertEquals(setOf("a1", "a2"), vm.searchResults.value.map { it.article.id }.toSet())

        // No pump between the selection and the query change: a1's body load spans both.
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        vm.setSearchQuery("Java")
        advanceForSearchDebounce()

        // a1 (the only "Java" match) landed its pin despite the query changing mid-load.
        assertEquals(listOf("a1"), vm.searchResults.value.map { it.article.id })
        assertEquals(1L, vm.searchResults.value.single().article.is_read)
    }

    @Test
    fun markAllReadWhileSearchingMarksOnlyUnreadMatchesAndKeepsSelectedOnePinned() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("a2", "f1", title = "Kotlin Two", content = "kotlin content", isRead = 0L)
        db.insertArticle("a3", "f1", title = "Kotlin Three", content = "kotlin content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
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
    fun markAllReadWhileSearchingDoesNotAffectUnreadArticlesOutsideTheMatch() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content", isRead = 0L)
        db.insertArticle("other", "f1", title = "Something else", content = "unrelated content", isRead = 0L)
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        assertEquals(listOf("a1"), vm.searchResults.value.map { it.article.id })

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        // "other" never matched the search, so marking read while searching must not touch it —
        // proving this isn't a blanket mark-everything-read fallback.
        assertEquals(0L, db.articlesQueries.getById("other").executeAsOne().is_read)
    }

    @Test
    fun markAllReadAfterMarkSelectedUnreadWhileSearchingMarksSelectedArticleRead() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Kotlin One", content = "kotlin content")
        ftsManagerIndexed(driver)
        // Use a controllable dispatcher so markSelectedUnread()'s unread write stays queued until
        // markAllRead() has already inspected the stale search snapshot.
        val vm = newViewModel(dbWriteDispatcher = StandardTestDispatcher(testScheduler))
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()

        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)

        vm.markSelectedUnread()
        // Do not advance: the unread DB write is still queued and _rawSearchResults still reflects
        // the pre-unread snapshot, so markAllRead() must not treat the operation as a no-op.
        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(1L, vm.selectedArticle.value?.is_read)
    }

    @Test
    fun toggleSortHasNoEffectOnSearchResultsOrdering() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", title = "Zzz Kotlin", content = "kotlin kotlin kotlin filler padding words")
        db.insertArticle("a2", "f1", title = "Kotlin", content = "kotlin")
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Kotlin")
        advanceForSearchDebounce()
        val before = vm.searchResults.value.map { it.article.id }
        assertEquals(2, before.size)

        vm.toggleSort()
        testScheduler.advanceUntilIdle()

        assertFalse(vm.newestFirst.value)
        // The relevance-rank order is unaffected by the (search-irrelevant) sort toggle.
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
        val activityCenter = ActivityCenter()
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

    @Test
    fun toggleTagExpandedDemotesStaleTagNestedRowInstanceWhenCollapsed() = runTest {
        db.insertFeed("f1")
        db.insertTag("t1", "Kotlin")
        db.insertFeedTag("f1", "t1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.toggleTagExpanded("t1")
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))
        testScheduler.advanceUntilIdle()
        assertEquals(FeedListRowSelection.FeedInTag("f1", "t1"), vm.selectedRowInstance.value)

        // Collapsing the tag stops rendering the nested row, so the stale instance must fall
        // back to the feed's canonical (folder-group) row.
        vm.toggleTagExpanded("t1")

        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), vm.selectedRowInstance.value)
    }

    @Test
    fun deleteTagDemotesStaleTagNestedRowInstance() = runTest {
        db.insertFeed("f1")
        db.insertTag("t1", "Kotlin")
        db.insertFeedTag("f1", "t1")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.toggleTagExpanded("t1")
        // The active filter stays Feed("f1"), not Tag("t1"), so deleteTag's
        // selectFilter(ArticleFilter.All) reset branch does not fire — this exercises the
        // separate demotion path for a selection that just happens to be tag-nested.
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))
        testScheduler.advanceUntilIdle()
        assertEquals(FeedListRowSelection.FeedInTag("f1", "t1"), vm.selectedRowInstance.value)

        vm.deleteTag("t1")

        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), vm.selectedRowInstance.value)
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
        testScheduler.advanceUntilIdle()
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
        testScheduler.advanceUntilIdle()

        assertEquals("a1", store.load().lastArticleId)
    }

    @Test
    fun restartRestoresFilterAndArticle() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L)
        val vm1 = newViewModel()
        subscribeAll(vm1)
        vm1.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        val article1 = db.articlesQueries.getById("a1").executeAsOne()
        vm1.selectArticle(article1.toListRow())
        testScheduler.advanceUntilIdle()

        // Simulate an app restart: a fresh HomeViewModel over the same db/dir.
        val vm2 = newViewModel()
        subscribeAll(vm2)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Feed("f1"), vm2.filter.value)
        assertEquals("a1", vm2.selectedArticle.value?.id)
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
    fun unreadOnlyStarredIgnoresTheDeviceWideDefaultUnlikeEveryOtherFilter() = runTest {
        // article_list_default_unread_only is the fallback for the *shared* toggle only — the
        // Starred-specific one always starts OFF regardless, so it never inherits the device-wide
        // "start with unread only" preference.
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined,
        )
        settingsRepository.setArticleListDefaultUnreadOnly(true)

        val vm = newViewModel()
        subscribeAll(vm)
        assertTrue(vm.unreadOnly.value)

        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.unreadOnly.value)
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
    fun subscribeFeedsFilesIntoTheSelectedFolder() {
        db.insertFolder("d1", "Kotlin folder")
        runBlocking {
            val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
            vm.selectFilter(ArticleFilter.Folder("d1"))

            val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

            assertEquals(1, outcome.successCount)
            assertEquals("d1", db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne().folder_id)
        }
    }

    @Test
    fun subscribeFeedsFilesIntoTheFolderOfTheSelectedFeed() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("f1", folderId = "d1")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        assertEquals("d1", db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne().folder_id)
    }

    @Test
    fun subscribeFeedsLeavesNewFeedUnfiledWhenAnUnfiledFeedIsSelected() = runTest {
        // f1 has no folder (folderId omitted), so the new feed must also land unfiled instead of
        // inheriting some stale/unrelated folder id.
        db.insertFeed("f1")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        assertNull(db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne().folder_id)
    }

    @Test
    fun subscribeFeedsInsertsNewFeedDirectlyAfterTheSelectedFeedWithinItsFolder() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", folderId = "d1", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        assertEquals("d1", newFeed.folder_id)
        val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
        assertEquals(listOf("f1", newFeed.id, "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsInsertsNewFeedDirectlyAfterTheSelectedUnfiledFeed() = runTest {
        db.insertFeed("f1", sortOrder = 0L)
        db.insertFeed("f2", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        assertNull(newFeed.folder_id)
        val ordered = db.feedsQueries.getByFolder(null).executeAsList()
        assertEquals(listOf("f1", newFeed.id, "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsInsertsMultipleNewFeedsDirectlyAfterTheSelectedFeedInInputOrder() = runTest {
        db.insertFeed("f1", sortOrder = 0L)
        db.insertFeed("f2", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/a", "https://ex.com/b"))

        assertEquals(2, outcome.successCount)
        val newFeedA = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val newFeedB = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        assertNull(newFeedA.folder_id)
        assertNull(newFeedB.folder_id)
        val ordered = db.feedsQueries.getByFolder(null).executeAsList()
        assertEquals(listOf("f1", newFeedA.id, newFeedB.id, "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsKeepsChainingSuccessesAfterTheSelectedFeedAcrossAFailedSubscription() = runTest {
        db.insertFeed("f1", sortOrder = 0L)
        db.insertFeed("f2", sortOrder = 1L)
        val vm = newViewModel(
            feedFetcher = fetcherWith { request ->
                if (request.url.host == "bad.com") respond("", HttpStatusCode.NotFound)
                else respond(RSS, HttpStatusCode.OK)
            },
        )
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/a", "https://bad.com/feed", "https://ex.com/b"))

        assertEquals(2, outcome.successCount)
        assertEquals(1, outcome.failCount)
        val newFeedA = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val newFeedB = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        val ordered = db.feedsQueries.getByFolder(null).executeAsList()
        assertEquals(listOf("f1", newFeedA.id, newFeedB.id, "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsInsertsNewFeedAtTheStartOfTheSelectedFolder() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", folderId = "d1", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Folder("d1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        assertEquals("d1", newFeed.folder_id)
        val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
        assertEquals(listOf(newFeed.id, "f1", "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsAppendsIntoAnEmptySelectedFolder() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Folder("d1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        assertEquals("d1", newFeed.folder_id)
        assertEquals(0L, newFeed.sort_order)
    }

    @Test
    fun subscribeFeedsInsertsMultipleNewFeedsAtTheStartOfTheSelectedFolderInInputOrder() = runTest {
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", folderId = "d1", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Folder("d1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/a", "https://ex.com/b"))

        assertEquals(2, outcome.successCount)
        val newFeedA = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val newFeedB = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
        assertEquals(listOf(newFeedA.id, newFeedB.id, "f1", "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsWithNoSelectionInsertsAtTheStartOfTheUnfiledGroup() = runTest {
        db.insertFeed("f1", sortOrder = 0L)
        db.insertFeed("f2", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.All)

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        val ordered = db.feedsQueries.getByFolder(null).executeAsList()
        assertEquals(listOf(newFeed.id, "f1", "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsWithNoSelectionInsertsMultipleNewFeedsAtTheStartOfTheUnfiledGroupInInputOrder() = runTest {
        db.insertFeed("f1", sortOrder = 0L)
        db.insertFeed("f2", sortOrder = 1L)
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.All)

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/a", "https://ex.com/b"))

        assertEquals(2, outcome.successCount)
        val newFeedA = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val newFeedB = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        val ordered = db.feedsQueries.getByFolder(null).executeAsList()
        assertEquals(listOf(newFeedA.id, newFeedB.id, "f1", "f2"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsAttachesTheSelectedTagToTheNewFeed() = runTest {
        db.insertTag("t1", "Kotlin")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Tag("t1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        val row = db.feed_tagsQueries.watchAllActive().executeAsList().single()
        assertEquals(newFeed.id, row.feed_id)
        assertEquals("t1", row.tag_id)
    }

    @Test
    fun subscribeFeedsAttachesTheTagOfTheSelectedFeedRowNestedUnderThatTag() = runTest {
        db.insertFeed("f1")
        db.insertTag("t1", "Kotlin")
        db.insertFeedTag("f1", "t1")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
        val tagged = db.feed_tagsQueries.watchAllActive().executeAsList()
            .filter { it.tag_id == "t1" }
            .map { it.feed_id }
            .toSet()
        assertEquals(setOf("f1", newFeed.id), tagged)
    }

    @Test
    fun subscribeFeedsAttachesTheSelectedTagToEveryFeedInTheBatch() = runTest {
        db.insertTag("t1", "Kotlin")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Tag("t1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/a", "https://ex.com/b"))

        assertEquals(2, outcome.successCount)
        val newFeedA = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val newFeedB = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        val tagged = db.feed_tagsQueries.watchAllActive().executeAsList()
            .filter { it.tag_id == "t1" }
            .map { it.feed_id }
            .toSet()
        assertEquals(setOf(newFeedA.id, newFeedB.id), tagged)
    }

    @Test
    fun subscribeFeedsAttachesNoTagWhenNoTagIsSelected() = runTest {
        // A folder selection carries no tag context, so the new feed must stay untagged.
        db.insertFolder("d1", "Kotlin folder")
        db.insertTag("t1", "Kotlin")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Folder("d1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/feed"))

        assertEquals(1, outcome.successCount)
        assertTrue(db.feed_tagsQueries.watchAllActive().executeAsList().isEmpty())
    }

    @Test
    fun subscribeFeedsSkipsPositionAnchorForAnAlreadySubscribedFeedInTheBatch() = runTest {
        // "existing" is already subscribed and sits at the folder's start. The batch re-lists its
        // URL before a genuinely new one, under the same folder selection that would normally place
        // a new feed at the start via beforeFeedId.
        db.insertFolder("d1", "Kotlin folder")
        db.insertFeed("existing", folderId = "d1", sortOrder = 0L, url = "https://ex.com/existing")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Folder("d1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/existing", "https://ex.com/new"))

        assertEquals(2, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/new").executeAsOne()
        val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
        assertEquals(listOf(newFeed.id, "existing"), ordered.map { it.id })
    }

    @Test
    fun subscribeFeedsDoesNotTagAnAlreadySubscribedFeedInTheBatch() = runTest {
        db.insertFeed("existing", url = "https://ex.com/existing")
        db.insertTag("t1", "Kotlin")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectFilter(ArticleFilter.Tag("t1"))

        val outcome = vm.subscribeFeeds(listOf("https://ex.com/existing", "https://ex.com/new"))

        assertEquals(2, outcome.successCount)
        val newFeed = db.feedsQueries.getByUrl("https://ex.com/new").executeAsOne()
        val tagged = db.feed_tagsQueries.watchAllActive().executeAsList().map { it.feed_id }.toSet()
        assertEquals(setOf(newFeed.id), tagged)
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

    // --- Reader pager: pagerArticles / articleContents / requestArticleContent ---

    /**
     * The pager and `selectNext`/`selectPrevious` must agree about what "the next article" is, so
     * [HomeViewModel.pagerArticles] has to resolve exactly as `currentArticles()` does — including
     * swapping to the search results while a search is running.
     */
    @Test
    fun pagerArticlesFollowsTheVisibleListAndSwapsToSearchResults() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, publishedAt = 2L, createdAt = 2L, title = "Kotlin One")
        db.insertArticle("a2", "f1", isRead = 0L, publishedAt = 1L, createdAt = 1L, title = "Swift Two")
        ftsManagerIndexed(driver)
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a1", "a2"), vm.pagerArticles.value.map { it.id })

        vm.setSearchBarVisible(true)
        vm.setSearchQuery("Swift")
        advanceForSearchDebounce()

        assertTrue(vm.searchActive.value)
        assertEquals(vm.searchResults.value.map { it.article.id }, vm.pagerArticles.value.map { it.id })
        assertEquals(listOf("a2"), vm.pagerArticles.value.map { it.id })
    }

    /**
     * The whole point of hydrating a neighbouring page separately from selecting it: the pager
     * composes the articles either side of the one on screen, and those must not be marked read —
     * `selectArticle` is the only path allowed to do that (external-spec §7).
     */
    @Test
    fun requestArticleContentLoadsTheBodyWithoutMarkingItReadOrSelectingIt() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>neighbour</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        vm.requestArticleContent("a1")
        testScheduler.advanceUntilIdle()

        assertEquals("<p>neighbour</p>", vm.articleContents.value["a1"]?.content)
        assertNull(vm.selectedArticle.value)
        assertEquals(0L, vm.articles.value.single { it.id == "a1" }.is_read)
    }

    /**
     * A sync merge can tombstone the row between the page composing and the lookup returning.
     * Caching it would put deleted content on screen — the same race `selectArticle` guards.
     */
    @Test
    fun requestArticleContentDiscardsARowTombstonedSinceThePageComposed() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>gone</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        driver.stampArticleDeleted("a1", deletedAt = 10L)
        vm.requestArticleContent("a1")
        testScheduler.advanceUntilIdle()

        assertFalse("a1" in vm.articleContents.value)
    }

    /**
     * The cache does *not* skip the currently selected article — its body has to still be there
     * after the selection moves on to a neighbour, or the page the user just swiped away from
     * would blank out and reload, losing the reading position the pager exists to preserve
     * (`ArticlePagerSync.readerContents` is what merges the selection's own authoritative row in
     * ahead of this one while it is still current).
     */
    @Test
    fun requestArticleContentAlsoLoadsTheCurrentlySelectedArticle() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>selected</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.selectArticle(vm.articles.value.single())
        testScheduler.advanceUntilIdle()

        vm.requestArticleContent("a1")
        testScheduler.advanceUntilIdle()

        assertEquals("<p>selected</p>", vm.articleContents.value["a1"]?.content)
        assertEquals("<p>selected</p>", vm.selectedArticle.value?.content)
    }

    /**
     * Bodies survive the selection moving to a neighbour — otherwise the page just swiped away
     * from would blank out under the pager, exactly the regression this cache exists to prevent.
     */
    @Test
    fun requestedContentSurvivesTheSelectionMovingElsewhere() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>first</p>")
        db.insertArticle("a2", "f1", isRead = 0L, content = "<p>second</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        val a1 = vm.articles.value.first { it.id == "a1" }
        val a2 = vm.articles.value.first { it.id == "a2" }
        vm.selectArticle(a1)
        testScheduler.advanceUntilIdle()
        vm.requestArticleContent("a1")
        testScheduler.advanceUntilIdle()

        vm.selectArticle(a2)
        testScheduler.advanceUntilIdle()

        assertEquals("<p>first</p>", vm.articleContents.value["a1"]?.content)
    }

    /** Forgetting the cache is what keeps a long reading session from holding every body it ever
     * paged past for the ViewModel's whole life once the reader leaves the composition. */
    @Test
    fun clearArticleContentsForgetsEveryHeldBody() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1", isRead = 0L, content = "<p>content</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()
        vm.requestArticleContent("a1")
        testScheduler.advanceUntilIdle()
        assertTrue("a1" in vm.articleContents.value)

        vm.clearArticleContents()

        assertTrue(vm.articleContents.value.isEmpty())
    }

    /**
     * A long reading session must not accumulate every body it has ever paged past —
     * `ARTICLE_CONTENT_CACHE_LIMIT` bounds the map, dropping the oldest entry first.
     */
    @Test
    fun articleContentsEvictsTheOldestEntryPastTheCacheLimit() = runTest {
        db.insertFeed("f1")
        val ids = (1..ARTICLE_CONTENT_CACHE_LIMIT + 2).map { "a$it" }
        ids.forEachIndexed { index, id ->
            db.insertArticle(id, "f1", isRead = 0L, content = "<p>$id</p>", publishedAt = index.toLong(), createdAt = index.toLong())
        }
        val vm = newViewModel()
        subscribeAll(vm)
        testScheduler.advanceUntilIdle()

        ids.forEach { id ->
            vm.requestArticleContent(id)
            testScheduler.advanceUntilIdle()
        }

        assertEquals(ARTICLE_CONTENT_CACHE_LIMIT, vm.articleContents.value.size)
        // The two requested first are the two dropped; the most recent are all still in hand.
        assertEquals(ids.drop(2).toSet(), vm.articleContents.value.keys)
    }

    // --- newArticleCount (the article list's "new articles" pill) ---

    @Test
    fun newArticleCountIsZeroOnInitialLoad() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        // Nothing was "missed" yet — the first emission only seeds the baseline.
        assertEquals(0, vm.newArticleCount.value)
    }

    @Test
    fun newArticleCountIncreasesWhenArticlesAreAddedAfterTheBaselineIsSeeded() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)

        db.insertArticle("a2", "f1")
        db.insertArticle("a3", "f1")
        testScheduler.advanceUntilIdle()

        assertEquals(2, vm.newArticleCount.value)
    }

    @Test
    fun markArticlesSeenDropsOnlyTheReportedIdsFromTheCount() = runTest {
        db.insertFeed("f1")
        // A non-empty baseline before the VM even starts — an empty-then-filled one is its own
        // "still seeding" case (see NewArticleTracking.withList) and would leave nothing here for
        // markArticlesSeen to trim.
        db.insertArticle("seed", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.newArticleCount.value)

        vm.markArticlesSeen(listOf("a1"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value)
    }

    @Test
    fun markAllArticlesSeenClearsTheCount() = runTest {
        db.insertFeed("f1")
        // See markArticlesSeenDropsOnlyTheReportedIdsFromTheCount for why a seed article is needed.
        db.insertArticle("seed", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        db.insertArticle("a1", "f1")
        db.insertArticle("a2", "f1")
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.newArticleCount.value)

        vm.markAllArticlesSeen()
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)
    }

    @Test
    fun switchingFiltersDoesNotCountThePreviousFilterSExistingArticlesAsNew() = runTest {
        db.insertFeed("f1")
        db.insertArticle("a1", "f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)

        // All still contains a1 — already present before tracking on this filter began — so
        // switching must not treat it as new just because the query itself is now different.
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)
    }

    @Test
    fun togglingUnreadOnlyOrSortDirectionDoesNotChangeTheCount() = runTest {
        db.insertFeed("f1")
        // See markArticlesSeenDropsOnlyTheReportedIdsFromTheCount for why a seed article is needed.
        db.insertArticle("seed", "f1", isRead = 1L)
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        db.insertArticle("a1", "f1", isRead = 0L)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value)

        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value)

        vm.setUnreadOnly(false)
        vm.toggleSort()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value)
    }

    @Test
    fun newArticleCountIsZeroWhileSearchIsActive() = runTest {
        db.insertFeed("f1")
        // See markArticlesSeenDropsOnlyTheReportedIdsFromTheCount for why a seed article is needed.
        db.insertArticle("seed", "f1", content = "<p>unrelated</p>")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        vm.setSearchBarVisible(true)
        testScheduler.advanceUntilIdle()

        db.insertArticle("a1", "f1", content = "<p>hello world</p>")
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value)

        vm.setSearchQuery("hello")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.searchActive.value)
        assertEquals(0, vm.newArticleCount.value)
    }

    @Test
    fun newArticleCountStaysZeroWhenAFilterThatStartedEmptyGetsItsFirstArticles() = runTest {
        // A brand-new feed: selecting its filter before anything has been fetched starts the raw
        // query at an empty list, matching the real subscribe flow this guards against.
        db.insertFeed("f1")
        val vm = newViewModel()
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)

        // The initial fetch lands — nothing in the empty list could have been missed, so this
        // becomes the new baseline rather than a batch of "new" articles. A single transaction,
        // matching FeedRepository.applyFetch's own real insert (a whole fetch's articles commit
        // together): SQLDelight coalesces query-listener notifications per transaction, so two
        // separate (non-transactional) inserts here would instead surface as two raw-query
        // emissions — the first re-seeding the baseline to {a1}, the second then correctly (but
        // misleadingly, for this test) detecting a2 as new relative to that baseline.
        db.transaction {
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f1")
        }
        testScheduler.advanceUntilIdle()

        assertEquals(0, vm.newArticleCount.value)
    }

    @Test
    fun subscribingAFeedDoesNotCountItsArticlesAsNew() = runTest {
        db.insertFeed("f1")
        // A non-empty baseline on the currently selected filter (All), established before
        // subscribing — otherwise this couldn't be distinguished from the already-covered
        // empty-baseline case (see NewArticleTracking.withList).
        db.insertArticle("seed", "f1")
        val vm = newViewModel(feedFetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) })
        subscribeAll(vm)
        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.newArticleCount.value)

        vm.subscribeFeeds(listOf("https://ex.com/feed"))
        testScheduler.advanceUntilIdle()

        // The new feed's own fetched article is right there in the list the user is already
        // looking at — it was never "missed".
        assertEquals(0, vm.newArticleCount.value)
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

package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.KeryxException
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
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.random.Random

/**
 * Shared [HomeViewModel] test fixture, used by every Compose UI test under `ui/home/` that needs a
 * real, DB-backed `HomeViewModel` (feed-list drag/rename/hit-area/search/notification tests). Kept
 * as its own support file — like [works.merc.keryx.app.DbTestSupport] — rather than embedded in one
 * test file, since burying it in a single test's file is what let several call sites drift out of
 * step with its teardown contract (see [HomeViewModelFixture.close]).
 */

/** A [NotificationMessages] fake returning canned, recognizable strings. */
private class HomeViewModelFixtureNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

private class HomeViewModelFixtureTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null
    override fun save(tokens: OAuthTokens) { stored = tokens }
    override fun load(): OAuthTokens? = stored
    override fun clear() { stored = null }
}

private fun notFoundHttpClient(): HttpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
}

/**
 * Bundles the [HomeViewModel] under test with every resource [newHomeViewModel] creates outside
 * its own [HomeViewModel.viewModelScope] — the SQL [driver] itself, [SyncRepository]'s
 * channel-consumer scope, and the MockEngine [HttpClient]s — so a test can release all of them, in
 * the one order that's safe, via [close].
 */
internal class HomeViewModelFixture(
    val vm: HomeViewModel,
    private val driver: SqlDriver,
    private val syncScope: CoroutineScope,
    private val httpClients: List<HttpClient>,
) {
    /**
     * Cancels every scope this fixture owns and *joins* it before [driver] is closed.
     *
     * `cancelAndJoin`, not a plain `cancel`: cancellation is cooperative, and
     * [ComposeUiTest.waitForIdle] does not pump the AWT event queue — `SkikoComposeUiTest`'s
     * `waitForIdle` only advances Compose's own test clock and checks for pending snapshot/scene
     * invalidations, it never touches `java.awt.EventQueue`. So a continuation already queued on
     * `Dispatchers.Main` (the EDT — where [HomeViewModel.viewModelScope] and its
     * `SharingStarted.Eagerly` DB collectors run) would otherwise still resume against a [driver]
     * this function had already closed. That throws `stmt pointer is closed` with nobody to catch
     * it, which then surfaces flakily — on whichever *other* test happens to run next — as
     * `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`. Same reasoning, and the same fix, as
     * `SettingsViewModelTest.tearDown`.
     */
    suspend fun close() {
        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        syncScope.coroutineContext.job.cancelAndJoin()
        httpClients.forEach { it.close() }
        driver.close()
    }
}

/**
 * Builds a real [HomeViewModel] over an already-seeded [db] — every dispatcher is
 * [Dispatchers.Unconfined] so DB writes triggered by a gesture (e.g. via [FeedRepository.moveFeed])
 * apply synchronously within the test.
 *
 * Prefer [ComposeUiTest.useHomeViewModel] in a Compose UI test: it wraps this together with the
 * matching [HomeViewModelFixture.close], so teardown can't be forgotten, misplaced, or
 * mis-ordered. Call this directly only for a plain (non-Compose) `HomeViewModel` test, where the
 * caller must still `try { … } finally { runBlocking { fixture.close() } }` itself.
 */
internal fun newHomeViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
    activityCenter: ActivityCenter = ActivityCenter(),
    tokenStorage: TokenStorage = HomeViewModelFixtureTokenStorage(),
    appKey: String = "",
): HomeViewModelFixture {
    // A fresh, unique directory per call (not a fixed name shared across every test in this file):
    // LocalSettingsStore persists lastFilter/collapsedFolderIds/etc. to a JSON file there, and a
    // shared path would leak state between tests within the same run (e.g. a filter selected by
    // one test becoming the *restored* initial filter of the next), exactly like
    // `HomeViewModelTest`'s per-instance `Random.nextInt()`-suffixed directory.
    val dir = FileIO.join(AppDirs.tempDir(), "home-vm-test-${Random.nextInt()}")
    val fetcherClient = notFoundHttpClient()
    val faviconClient = notFoundHttpClient()
    val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
    val feedRepository = FeedRepository(
        db, FeedFetcher(fetcherClient), FaviconResolver(faviconClient), articleRepository,
        ftsManagerIndexed(driver), syncScheduler, NotificationCenter(), HomeViewModelFixtureNotificationMessages(),
        clock, Dispatchers.Unconfined,
    )
    val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
    val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
    val settingsRepository = SettingsRepository(
        db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined,
    )
    val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val syncRepository = SyncRepository(
        driver = driver,
        db = db,
        ftsManager = FtsManager(driver),
        cloudProvider = { null },
        clock = clock,
        scope = syncScope,
        activityCenter = activityCenter,
        notificationCenter = NotificationCenter(),
        notificationMessages = HomeViewModelFixtureNotificationMessages(),
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
    val vm = HomeViewModel(
        feedRepository, articleRepository, tagRepository, folderRepository, settingsRepository,
        syncRepository, cloudSession, activityCenter, clock, NewArticleNotifier(), HomeViewModelFixtureNotificationMessages(),
        Dispatchers.Unconfined, Dispatchers.Unconfined,
    )
    return HomeViewModelFixture(vm, driver, syncScope, listOf(fetcherClient, faviconClient, authClient))
}

/**
 * Builds a [HomeViewModel] over [driver]/[db], runs [block] against it, then tears the whole
 * fixture down — see [HomeViewModelFixture.close] for the order and why it matters.
 *
 * This is the only supported way to get a [HomeViewModel] in a Compose UI test: teardown cannot be
 * forgotten, cannot be placed outside the Compose test (where [ComposeUiTest.waitForIdle] isn't
 * available), and cannot be ordered wrongly.
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun <T> ComposeUiTest.useHomeViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
    activityCenter: ActivityCenter = ActivityCenter(),
    tokenStorage: TokenStorage = HomeViewModelFixtureTokenStorage(),
    appKey: String = "",
    block: suspend (HomeViewModelFixture) -> T,
): T {
    val fixture = newHomeViewModel(driver, db, syncScheduler, clock, activityCenter, tokenStorage, appKey)
    return try {
        // waitForIdle only on the success path: it rethrows Compose's own uncaught exceptions, and
        // doing that from a `finally` would mask the assertion failure that actually ended [block].
        block(fixture).also { waitForIdle() }
    } finally {
        fixture.close()
    }
}

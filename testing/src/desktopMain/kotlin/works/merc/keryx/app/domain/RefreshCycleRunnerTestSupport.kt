package works.merc.keryx.app.domain

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
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.NetworkCondition
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.singleProviderCloudSession
import java.io.IOException
import java.util.Collections

/** A one-item RSS 2.0 document, the default body every feed URL answers with in [RefreshCycleRunnerFixtures]. */
const val REFRESH_CYCLE_TEST_RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** A [RefreshCycleRunner] wired to a real DB, a MockEngine fetcher and a recording [SyncRepository]. */
class RefreshCycleRunnerFixture(
    val runner: RefreshCycleRunner,
    val activityCenter: ActivityCenter,
    val notificationCenter: NotificationCenter,
    /** "fetch:<url>", "notify:<message>" and "sync" entries, in the order they happened. */
    val log: MutableList<String>,
)

/**
 * Builds [RefreshCycleRunnerFixture]s against a real DB, a MockEngine feed fetcher, and a real
 * [SyncRepository] whose `cloudProvider` returns `null` (so a sync is an instant `Ok`) but records
 * each call — the observable "a sync ran" signal. Everything created here (HTTP clients, the sync
 * scope) is released by [close], which the test calls from its `@AfterTest`.
 *
 * Use it from `runBlocking`, not `runTest`: MockEngine plus `HttpTimeout` under virtual time can
 * misfire (see testing.md).
 *
 * @param settingsDir Directory for the `local_settings.json` the fixture's settings repository uses.
 */
class RefreshCycleRunnerFixtures(private val settingsDir: String) : AutoCloseable {
    private val cleanups = mutableListOf<() -> Unit>()

    private fun httpClient(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }.also { client -> cleanups += { client.close() } }

    fun create(
        driver: SqlDriver,
        db: KeryxDatabase,
        connected: Boolean = true,
        fetchGate: CompletableDeferred<Unit>? = null,
        /** The status each feed URL answers with, or `null` for a request that gets no response. */
        fetchStatus: (url: String) -> HttpStatusCode? = { HttpStatusCode.OK },
        fetchBody: String = REFRESH_CYCLE_TEST_RSS,
        notifySink: OsNotificationSink? = null,
        network: NetworkCondition = NetworkCondition.UNRESTRICTED,
        appForeground: AppForeground = AppForeground(),
    ): RefreshCycleRunnerFixture {
        val clock = Clock { 1000L }
        val log = Collections.synchronizedList(mutableListOf<String>())
        val activityCenter = ActivityCenter()
        val notificationCenter = NotificationCenter()
        val fetcher = FeedFetcher(
            httpClient { request ->
                log += "fetch:${request.url}"
                fetchGate?.await()
                val status = fetchStatus(request.url.toString()) ?: throw IOException("unreachable")
                respond(fetchBody, status)
            },
        )
        val favicons = FaviconResolver(httpClient { respond("", HttpStatusCode.NotFound) })
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, fetcher, favicons, articleRepository, ftsManagerIndexed(driver), SyncScheduler {},
            notificationCenter, clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
        )
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scope -> cleanups += { scope.cancel() } }
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { log += "sync"; null },
            clock = clock,
            scope = syncScope,
            activityCenter = activityCenter,
            notificationCenter = NotificationCenter(),
            localDbPath = "unused",
            tempDir = "unused",
        )
        val authClient = httpClient { respond("{}", HttpStatusCode.OK) }
        val cloudSession = singleProviderCloudSession(
            client = authClient,
            tokenStorage = FakeTokenStorage(if (connected) OAuthTokens(accessToken = "token") else null),
            authManager = DropboxAuthManager(authClient, clock = clock),
            clock = clock,
        )
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = settingsDir), SyncScheduler {}, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        val runner = RefreshCycleRunner(
            activityCenter, feedRepository, syncRepository, cloudSession,
            NewArticleNotifier(notifySink ?: OsNotificationSink { message, _ -> log += "notify:$message" }),
            settingsRepository, FakeNotificationMessages(), notificationCenter, clock,
            appForeground = appForeground,
        ) { NetworkUsagePolicy.automaticRefreshAllowed(network) }
        return RefreshCycleRunnerFixture(runner, activityCenter, notificationCenter, log)
    }

    /** Releases every client and scope created so far, newest first. */
    override fun close() {
        cleanups.asReversed().forEach { it() }
        cleanups.clear()
    }
}

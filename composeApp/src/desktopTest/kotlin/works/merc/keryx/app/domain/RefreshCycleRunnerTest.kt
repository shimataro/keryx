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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/**
 * [RefreshCycleRunner] against a real DB, a MockEngine feed fetcher, and a real [SyncRepository]
 * whose `cloudProvider` returns `null` (so a sync is an instant `Ok`) but records each call — the
 * observable "a sync ran" signal. `runBlocking` rather than `runTest`: MockEngine plus
 * `HttpTimeout` under virtual time can misfire (see testing.md).
 */
class RefreshCycleRunnerTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "refresh-cycle-runner-test-${Random.nextInt()}")
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.asReversed().forEach { it() }
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private class Fixture(
        val runner: RefreshCycleRunner,
        val activityCenter: ActivityCenter,
        /** "fetch:<url>", "notify:<message>" and "sync" entries, in the order they happened. */
        val log: MutableList<String>,
    )

    private fun httpClient(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }.also { client -> cleanups += { client.close() } }

    private fun fixture(
        driver: SqlDriver,
        db: KeryxDatabase,
        connected: Boolean = true,
        fetchGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val clock = Clock { 1000L }
        val log = Collections.synchronizedList(mutableListOf<String>())
        val activityCenter = ActivityCenter()
        val fetcher = FeedFetcher(
            httpClient { request ->
                log += "fetch:${request.url}"
                fetchGate?.await()
                respond(RSS, HttpStatusCode.OK)
            },
        )
        val favicons = FaviconResolver(httpClient { respond("", HttpStatusCode.NotFound) })
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, fetcher, favicons, articleRepository, ftsManagerIndexed(driver), SyncScheduler {},
            NotificationCenter(), FakeNotificationMessages(), clock, Dispatchers.Unconfined,
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
            notificationMessages = FakeNotificationMessages(),
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
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        val runner = RefreshCycleRunner(
            activityCenter, feedRepository, syncRepository, cloudSession,
            NewArticleNotifier { message, _ -> log += "notify:$message" },
            settingsRepository, FakeNotificationMessages(),
        )
        return Fixture(runner, activityCenter, log)
    }

    private fun withDb(block: suspend CoroutineScope.(SqlDriver, KeryxDatabase) -> Unit): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            block(driver, db)
        } finally {
            driver.close()
        }
    }

    @Test
    fun runRefreshesNotifiesThenSyncsByDefault() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runSyncsFirstWithSyncThenRefresh() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)

        f.runner.run(order = RefreshCycleRunner.CycleOrder.SYNC_THEN_REFRESH, trigger = SyncTrigger.AUTOMATIC)

        assertEquals(listOf("sync", "fetch:https://ex.com/f1", "notify:new:1"), f.log.toList())
    }

    @Test
    fun runDoesNotSyncWhenNoProviderIsConnected() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db, connected = false)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertNull(result)
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1"), f.log.toList())
    }

    @Test
    fun runWrapsEachStageInTheStepWrapper() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)
        val steps = mutableListOf<String>()

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            steps += "$name:start"
            block()
            steps += "$name:end"
        }

        assertEquals(listOf("feedRefresh:start", "feedRefresh:end", "sync:start", "sync:end"), steps)
    }

    @Test
    fun runKeepsGoingWhenTheStepWrapperSwallowsAFailedStage() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            // Stand-in for runMaintenanceStep: the refresh stage "fails" and is swallowed.
            if (name == "feedRefresh") return@run
            block()
        }

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(listOf("sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIsNotIdleAnywhereInTheCycle() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixture(driver, db, fetchGate = gate)
        val snapshots = Collections.synchronizedList(mutableListOf<ActivitySnapshot>())

        val cycle = async(Dispatchers.Default) {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { _, block ->
                snapshots += f.activityCenter.activity.value // between stages: only the cycle is up
                block()
            }
        }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        assertTrue(f.activityCenter.activity.value.refreshCycleRunning)
        gate.complete(Unit)
        cycle.await()

        assertEquals(2, snapshots.size)
        assertTrue(snapshots.all { !it.idle }, "every stage boundary must be busy: $snapshots")
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIfIdleReturnsBusyWithoutStartingWhileAnythingIsInFlight() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val busy = launch(Dispatchers.Default) { f.activityCenter.trackSync { entered.complete(Unit); gate.await() } }
        entered.await()

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Busy, outcome)
        assertTrue(f.log.isEmpty(), "nothing may be fetched or synced: ${f.log}")
        gate.complete(Unit)
        busy.join()
    }

    @Test
    fun runIfIdleRunsTheCycleForTheSelectedFeedOnly() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        db.insertFeed("f2", url = "https://ex.com/f2")
        val f = fixture(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.Feed("f2"), SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit)), outcome)
        assertEquals(listOf("fetch:https://ex.com/f2", "notify:new:1", "sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIfIdleDoesNothingForASelectionCoveringNoSubscribedFeed() = withDb { driver, db ->
        db.insertFolder("d1", "Empty")
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.Folder("d1"), SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.NothingToRefresh, outcome)
        assertTrue(f.log.isEmpty(), "nothing may be fetched or synced: ${f.log}")
        assertFalse(f.activityCenter.activity.value.refreshCycleRunning)
    }

    @Test
    fun runIfIdleStillSyncsForAllWithNoFeeds() = withDb { driver, db ->
        val f = fixture(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit)), outcome)
        assertEquals(listOf("sync"), f.log.toList())
    }

    @Test
    fun runIfIdleReportsANullSyncResultWhenNotConnected() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixture(driver, db, connected = false)

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Ran(null), outcome)
        assertFalse("sync" in f.log)
    }
}

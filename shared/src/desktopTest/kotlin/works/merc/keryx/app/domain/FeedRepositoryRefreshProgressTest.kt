package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.awaitCondition
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.ftsManagerIndexed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun rssWith(guid: String) = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post $guid</title><link>https://ex.com/$guid</link><guid>$guid</guid></item>
</channel></rss>"""

/**
 * Blocks the thread that makes the first write notification — the refresh's single writer, right
 * after it commits its first feed — until [release] is counted down, and passes everything through.
 */
private class StallFirstWriteDriver(private val delegate: app.cash.sqldelight.db.SqlDriver) :
    app.cash.sqldelight.db.SqlDriver by delegate {
    val release = CountDownLatch(1)
    private val stalled = AtomicBoolean(false)

    override fun notifyListeners(vararg queryKeys: String) {
        delegate.notifyListeners(queryKeys = queryKeys)
        // Bounded: a failing assertion before release.countDown() must not hang the test worker.
        if (stalled.compareAndSet(false, true)) release.await(10, TimeUnit.SECONDS)
    }
}

/**
 * [FeedRepository.refreshFeeds] applies each feed as soon as its own fetch completes, and
 * [FeedRepository.refreshingFeedIds] tracks in-flight refreshes. Real time (runBlocking) with explicit
 * [CompletableDeferred] gates, not virtual time: MockEngine + HttpTimeout misbehave under runTest.
 */
class FeedRepositoryRefreshProgressTest {

    private fun fetcherWith(handler: MockRequestHandler): FeedFetcher {
        val client = HttpClient(MockEngine(handler)) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    private fun faviconResolver(): FaviconResolver =
        FaviconResolver(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        })

    private fun newRepo(db: KeryxDatabase, driver: app.cash.sqldelight.db.SqlDriver, fetcher: FeedFetcher): FeedRepository {
        val sync = SyncScheduler {}
        val clock = Clock { 1000L }
        val articles = ArticleRepository(db, FtsSearch(driver), sync, clock, Dispatchers.Unconfined)
        return FeedRepository(
            db, fetcher, faviconResolver(), articles, ftsManagerIndexed(driver), sync,
            NotificationCenter(), clock, Dispatchers.Unconfined, InMemoryFaviconMissStore(),
        )
    }

    private fun articleGuids(db: KeryxDatabase): Set<String> =
        db.articlesQueries.watchAll(::ArticleListRow).executeAsList().map { it.title }.toSet()

    /** Subscribes the given paths (each serving [initial]) and returns the stored feed rows by path. */
    private suspend fun subscribe(
        db: KeryxDatabase,
        driver: app.cash.sqldelight.db.SqlDriver,
        vararg paths: String,
    ): Map<String, Feeds> {
        val setup = newRepo(db, driver, fetcherWith { respond(rssWith("initial"), HttpStatusCode.OK) })
        paths.forEach { setup.subscribeFeed("https://ex.com/$it") }
        val byUrl = db.feedsQueries.watchAll().executeAsList().associateBy { it.url }
        return paths.associateWith { byUrl.getValue("https://ex.com/$it") }
    }

    private suspend fun FeedRepository.refreshing(): Set<String> = refreshingFeedIds.first()

    @Test
    fun aFastFeedIsCommittedWhileTheSlowFeedIsStillFetching(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, "slow", "fast")
            val gate = CompletableDeferred<Unit>()
            val slowRequested = AtomicInteger(0)
            val repo = newRepo(db, driver, fetcherWith { request ->
                if (request.url.encodedPath.endsWith("/slow")) {
                    slowRequested.incrementAndGet()
                    gate.await()
                    respond(rssWith("slow-new"), HttpStatusCode.OK)
                } else {
                    respond(rssWith("fast-new"), HttpStatusCode.OK)
                }
            })
            val slow = feeds.getValue("slow")
            val fast = feeds.getValue("fast")

            val call = async(Dispatchers.Default) { repo.refreshFeeds(listOf(slow, fast)) }
            try {
                awaitCondition { "Post fast-new" in articleGuids(db) }
                assertEquals(1, slowRequested.get(), "the slow fetch should have started")
                assertFalse(call.isCompleted, "refreshFeeds must still be waiting on the slow feed")
                assertFalse("Post slow-new" in articleGuids(db), "slow feed must not be applied yet")
                // The fast feed leaves the refreshing set just after its commit; the slow one never does.
                awaitCondition { runBlocking { repo.refreshing() } == setOf(slow.id) }
            } finally {
                gate.complete(Unit)
            }
            val results = withTimeout(10_000) { call.await() }

            assertTrue("Post slow-new" in articleGuids(db))
            assertTrue(results.values.all { it is Result.Ok })
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun theResultMapKeepsFeedListOrderEvenWhenCompletionOrderDiffers(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, "a", "b", "c")
            val gateA = CompletableDeferred<Unit>()
            val repo = newRepo(db, driver, fetcherWith { request ->
                if (request.url.encodedPath.endsWith("/a")) gateA.await()
                respond(rssWith(request.url.encodedPath.trim('/') + "-new"), HttpStatusCode.OK)
            })
            val order = listOf(feeds.getValue("a"), feeds.getValue("b"), feeds.getValue("c"))

            val call = async(Dispatchers.Default) { repo.refreshFeeds(order) }
            try {
                // b and c complete first; a is last.
                awaitCondition { "Post b-new" in articleGuids(db) && "Post c-new" in articleGuids(db) }
            } finally {
                gateA.complete(Unit)
            }
            val results = withTimeout(10_000) { call.await() }

            assertEquals(order.map { it.id }, results.keys.toList())
            assertTrue(results.values.all { it is Result.Ok })
        } finally {
            driver.close()
        }
    }

    @Test
    fun aFeedIsInTheRefreshingSetOnlyWhileItsRefreshIsInFlight(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feed = subscribe(db, driver, "a").getValue("a")
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val repo = newRepo(db, driver, fetcherWith {
                started.complete(Unit)
                gate.await()
                respond(rssWith("new"), HttpStatusCode.OK)
            })
            assertEquals(emptySet(), repo.refreshing())

            val call = async(Dispatchers.Default) { repo.refreshFeed(feed) }
            try {
                withTimeout(10_000) { started.await() }
                assertEquals(setOf(feed.id), repo.refreshing())
            } finally {
                gate.complete(Unit)
            }
            assertIs<Result.Ok<Int>>(withTimeout(10_000) { call.await() })
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedsMarksEveryRequestedFeedWhileTheyAreInFlight(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, "a", "b")
            val gate = CompletableDeferred<Unit>()
            val started = AtomicInteger(0)
            val repo = newRepo(db, driver, fetcherWith {
                started.incrementAndGet()
                gate.await()
                respond(rssWith("new"), HttpStatusCode.OK)
            })

            val call = async(Dispatchers.Default) { repo.refreshFeeds(feeds.values.toList()) }
            try {
                awaitCondition { started.get() == 2 }
                assertEquals(feeds.values.map { it.id }.toSet(), repo.refreshing())
            } finally {
                gate.complete(Unit)
            }
            withTimeout(10_000) { call.await() }
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun overlappingSingleAndBulkRefreshesKeepTheIdUntilBothEnd(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, "a", "b")
            val a = feeds.getValue("a")
            val b = feeds.getValue("b")
            val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            val aRequests = AtomicInteger(0)
            val repo = newRepo(db, driver, fetcherWith { request ->
                if (request.url.encodedPath.endsWith("/a")) {
                    gates[aRequests.getAndIncrement()].await()
                }
                respond(rssWith("new"), HttpStatusCode.OK)
            })

            val single = async(Dispatchers.Default) { repo.refreshFeed(a) }
            val bulk = async(Dispatchers.Default) {
                awaitCondition { aRequests.get() == 1 }
                repo.refreshFeeds(listOf(a, b))
            }
            try {
                awaitCondition { aRequests.get() == 2 }
                awaitCondition { runBlocking { repo.refreshing() } == setOf(a.id) } // b finishes

                gates[0].complete(Unit)
                withTimeout(10_000) { single.await() }
                assertEquals(setOf(a.id), repo.refreshing(), "the bulk refresh still holds the id")
            } finally {
                gates.forEach { it.complete(Unit) }
            }
            withTimeout(10_000) { bulk.await() }
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun cancellingARefreshFeedReleasesTheId(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feed = subscribe(db, driver, "a").getValue("a")
            val started = CompletableDeferred<Unit>()
            val repo = newRepo(db, driver, fetcherWith {
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // never completes
                respond(rssWith("new"), HttpStatusCode.OK)
            })

            val call = async(Dispatchers.Default) { repo.refreshFeed(feed) }
            withTimeout(10_000) { started.await() }
            assertEquals(setOf(feed.id), repo.refreshing())

            call.cancelAndJoin()
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun cancellingRefreshFeedsReleasesEveryId(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, "a", "b").values.toList()
            val started = AtomicInteger(0)
            val repo = newRepo(db, driver, fetcherWith {
                started.incrementAndGet()
                CompletableDeferred<Unit>().await()
                respond(rssWith("new"), HttpStatusCode.OK)
            })

            val call = async(Dispatchers.Default) { repo.refreshFeeds(feeds) }
            awaitCondition { started.get() == 2 }
            assertEquals(feeds.map { it.id }.toSet(), repo.refreshing())

            call.cancelAndJoin()
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aFailingFetchReleasesTheIdAndSurfacesAsErr(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feed = subscribe(db, driver, "a").getValue("a")
            val repo = newRepo(db, driver, fetcherWith { respond("", HttpStatusCode.InternalServerError) })

            val result = repo.refreshFeed(feed)

            assertIs<Result.Err>(result)
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aFetchThatThrowsStillReleasesTheId(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feed = subscribe(db, driver, "a").getValue("a")
            val repo = newRepo(db, driver, fetcherWith { throw IllegalStateException("boom") })

            // Whether FeedFetcher maps it to Err or lets it escape, the id must not leak.
            runCatching { repo.refreshFeed(feed) }
            runCatching { repo.refreshFeeds(listOf(feed)) }

            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun everyFeedIsMarkedRefreshingEvenBeyondThePendingApplyLimit(): Unit = runBlocking {
        // More feeds than both the fetch slots and the pending-apply bound: a feed still waiting for
        // either must already show as refreshing.
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, *Array(20) { "f$it" })
            val gate = CompletableDeferred<Unit>()
            val started = AtomicInteger(0)
            val repo = newRepo(db, driver, fetcherWith {
                started.incrementAndGet()
                gate.await()
                respond(rssWith("new"), HttpStatusCode.OK)
            })

            val call = async(Dispatchers.Default) { repo.refreshFeeds(feeds.values.toList()) }
            try {
                awaitCondition { started.get() > 0 && runBlocking { repo.refreshing() }.size == feeds.size }
                assertEquals(feeds.values.map { it.id }.toSet(), repo.refreshing())
                assertTrue(started.get() < feeds.size, "the fetches beyond the fetch slots must still be queued")
            } finally {
                gate.complete(Unit)
            }
            withTimeout(10_000) { call.await() }
            assertEquals(emptySet(), repo.refreshing())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aStalledWriterPausesFetchesAtThePendingApplyLimit(): Unit = runBlocking {
        val (_, driver, db) = fileDb()
        try {
            val feeds = subscribe(db, driver, *Array(20) { "f$it" })
            val stalling = StallFirstWriteDriver(driver)
            val started = AtomicInteger(0)
            val repo = newRepo(KeryxDatabase(stalling), stalling, fetcherWith { request ->
                started.incrementAndGet()
                respond(rssWith(request.url.encodedPath.trim('/') + "-new"), HttpStatusCode.OK)
            })

            val call = async(Dispatchers.Default) { repo.refreshFeeds(feeds.values.toList()) }
            val results = try {
                // The writer is stuck on its first commit, so no feed can leave the pending-apply
                // window: fetching stops once it is full instead of running through every feed.
                awaitCondition { started.get() >= 12 }
                delay(300)
                assertEquals(12, started.get(), "fetches must pause while the writer is held back")
                assertFalse(call.isCompleted)
                stalling.release.countDown()
                withTimeout(10_000) { call.await() }
            } finally {
                stalling.release.countDown()
            }

            assertTrue(results.values.all { it is Result.Ok })
            assertEquals(20, started.get())
            assertEquals(feeds.keys.map { "Post $it-new" }.toSet(), articleGuids(db).filter { it.endsWith("-new") }.toSet())
        } finally {
            driver.close()
        }
    }
}

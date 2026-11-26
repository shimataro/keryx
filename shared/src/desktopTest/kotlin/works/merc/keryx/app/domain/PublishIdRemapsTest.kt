package works.merc.keryx.app.domain

import app.cash.sqldelight.Query
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.insertFeed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SyncRepository`'s `publishIdRemaps` hook: called once a merge has committed (a receive's and the
 * pre-delta migration's alike), never inside its transaction, and a failure of it is logged, not
 * returned.
 */
class PublishIdRemapsTest {

    private val devices = mutableListOf<SyncDevice>()
    private val clock = Clock { 1_700_000_000_000L }
    private val ids = idSource(Random(11))

    @AfterTest
    fun tearDown() {
        devices.forEach { it.close() }
    }

    private fun TestScope.device(label: String, cloud: FakeCloudStorage, publish: suspend () -> Unit): SyncDevice =
        SyncDevice(label, cloud, clock, backgroundScope, ids, publishIdRemaps = publish).also(devices::add)

    private fun KeryxDatabase.hasFeed(id: String) = feedsQueries.getById(id).executeAsOneOrNull() != null

    @Test
    fun aReceiveCallsPublishAfterItsMergeCommitted() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud) {}
        a.db.insertFeed("fa", now = 1)
        assertIs<Result.Ok<Unit>>(a.repo.sync())

        val feedVisibleAtEachPublish = mutableListOf<Boolean>()
        lateinit var b: SyncDevice
        b = device("b", cloud) { feedVisibleAtEachPublish += b.db.hasFeed("fa") }
        assertIs<Result.Ok<Unit>>(b.repo.sync())

        // Called after the commit: the received rows were already readable from the database.
        assertTrue(feedVisibleAtEachPublish.isNotEmpty())
        assertTrue(feedVisibleAtEachPublish.all { it })
    }

    @Test
    fun aThrowingPublishDoesNotFailTheSync() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud) {}
        a.db.insertFeed("fa", now = 1)
        assertIs<Result.Ok<Unit>>(a.repo.sync())

        var calls = 0
        val b = device("b", cloud) {
            calls++
            throw IllegalStateException("publish failed")
        }
        val result = b.repo.sync()

        assertIs<Result.Ok<Unit>>(result)
        assertTrue(calls > 0)
        assertNull(b.repo.lastSyncError.value)
        // The merge itself stood: the feed is there and a later sync is quiet.
        assertTrue(b.db.hasFeed("fa"))
        assertIs<Result.Ok<Unit>>(b.repo.sync())
    }

    /** The pre-delta migration merges through the same step, so it publishes as well. */
    @Test
    fun theLegacyCloudFileMergeAlsoPublishes() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(
            "keryx.db.gz",
            PreDeltaFiles.database(
                "INSERT INTO tags (id, name, sort_order, updated_at, created_at) VALUES ('ltag', 'Legacy tag', 0, 1, 1)",
                PreDeltaFiles.feed("lf1"),
            ),
        )
        val seen = mutableListOf<Pair<Boolean, List<String>>>()
        lateinit var a: SyncDevice
        a = device("a", cloud) {
            seen += a.db.hasFeed("lf1") to a.db.tagsQueries.watchAll().executeAsList().map { it.id }
        }

        assertIs<Result.Ok<Unit>>(a.repo.sync())

        assertTrue(seen.isNotEmpty(), "publish was called")
        assertEquals(listOf(true to listOf(IdGenerator.tagId("Legacy tag"))), seen.take(1))
    }

    @Test
    fun aThrowingPublishDoesNotFailTheLegacyMigration() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put("keryx.db.gz", PreDeltaFiles.database(PreDeltaFiles.feed("lf1")))
        val a = device("a", cloud) { throw IllegalStateException("publish failed") }

        assertIs<Result.Ok<Unit>>(a.repo.sync())

        assertTrue(a.db.hasFeed("lf1"))
        assertNull(a.repo.lastSyncError.value)
    }

    /**
     * A sync cancelled while it publishes the re-keys — here waiting for the map's lock, which another
     * publish holds — still tells SQLDelight's listeners about the merge it committed, so the UI never
     * misses rows that are already in the database. The cancellation itself still propagates.
     */
    @Test
    fun aSyncCancelledWhilePublishingStillNotifiesTheCommittedMerge() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud) {}
        a.db.insertFeed("fa", now = 1)
        assertIs<Result.Ok<Unit>>(a.repo.sync())

        // The map's initial load holds its publish lock until released, on a thread of its own.
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val remapThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            lateinit var remaps: IdRemaps
            val publishCalled = CompletableDeferred<Unit>()
            val b = device("b", cloud) {
                publishCalled.complete(Unit)
                remaps.publishFromDb()
            }
            var firstLoad = true
            remaps = IdRemaps({
                if (firstLoad) {
                    firstLoad = false
                    loadEntered.countDown()
                    releaseLoad.await()
                }
                loadIdRemap(b.db)
            }, remapThread)
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS), "the initial load holds the publish lock")

            var feedNotifications = 0
            b.driver.addListener("feeds", listener = Query.Listener { feedNotifications++ })

            val sync = launch { b.repo.sync() }
            // The merge has committed and the sync is publishing: it waits for the lock the initial load
            // holds, and is cancelled there.
            publishCalled.await()
            feedNotifications = 0
            sync.cancel()
            releaseLoad.countDown()
            sync.join()

            assertTrue(sync.isCancelled)
            assertTrue(b.db.hasFeed("fa"), "the merge was committed")
            assertTrue(feedNotifications > 0, "the committed merge was announced to the listeners")
        } finally {
            releaseLoad.countDown()
            remapThread.close()
        }
    }
}

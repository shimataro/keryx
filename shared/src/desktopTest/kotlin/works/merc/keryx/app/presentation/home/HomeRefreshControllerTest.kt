package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.RefreshCycleRunnerFixture
import works.merc.keryx.app.domain.RefreshCycleRunnerFixtures
import works.merc.keryx.app.domain.RefreshOutcome
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.testThreadDispatcher
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [HomeRefreshController] over a real [works.merc.keryx.app.domain.RefreshCycleRunner] (the shared
 * [RefreshCycleRunnerFixtures] wiring: real DB, MockEngine fetcher, recording sync). `runBlocking`
 * rather than `runTest`: MockEngine plus `HttpTimeout` under virtual time can misfire (see testing.md).
 */
class HomeRefreshControllerTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "home-refresh-controller-test-${Random.nextInt()}")
    private val fixtures = RefreshCycleRunnerFixtures(dir)

    @AfterTest
    fun tearDown() {
        fixtures.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private class Harness(
        val controller: HomeRefreshController,
        val fixture: RefreshCycleRunnerFixture,
        val job: Job,
        val uncaught: MutableList<Throwable>,
        private val retrims: () -> Int,
    ) {
        val retrimCount: Int get() = retrims()

        /** Waits for every coroutine the controller launched to finish. */
        suspend fun settle() = job.children.toList().forEach { it.join() }
    }

    private fun withController(
        currentFilter: () -> ArticleFilter = { ArticleFilter.All },
        fetchGate: CompletableDeferred<Unit>? = null,
        syncGate: CompletableDeferred<Unit>? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        block: suspend CoroutineScope.(Harness, SqlDriver, KeryxDatabase) -> Unit,
    ): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        val job = SupervisorJob(coroutineContext[Job])
        try {
            val uncaught = mutableListOf<Throwable>()
            val scope = CoroutineScope(coroutineContext + job + CoroutineExceptionHandler { _, e -> uncaught += e })
            db.insertFeed("f1", url = "https://ex.com/f1")
            db.insertFeed("f2", url = "https://ex.com/f2")
            val fixture = fixtures.create(driver, db, fetchGate = fetchGate, syncGate = syncGate)
            var retrims = 0
            val controller = HomeRefreshController(
                scope = scope,
                dispatcher = dispatcher,
                runner = fixture.runner,
                activityCenter = fixture.activityCenter,
                currentFilter = currentFilter,
                retrimPins = { retrims++; fixture.log += "retrim" },
            )
            try {
                block(Harness(controller, fixture, job, uncaught) { retrims }, driver, db)
            } finally {
                // A gated sync blocks a thread until released; never leave one behind.
                syncGate?.complete(Unit)
            }
        } finally {
            job.cancelAndJoin()
            driver.close()
        }
    }

    private suspend fun RefreshCycleRunnerFixture.awaitFeedRefreshing() {
        withTimeout(5_000) { activityCenter.activity.first { it.feedRefreshing } }
    }

    @Test
    fun refreshFeedWhenTheRunnerThrowsStillEndsTheStatusWithAFreshGeneration() = withController { h, driver, _ ->
        // Make the runner's feed lookup throw, then restore the column so the next refresh works.
        driver.execute(null, "ALTER TABLE feeds RENAME COLUMN description TO description_x", 0)
        h.controller.refreshFeed("f1")
        h.settle()

        assertEquals(1, h.uncaught.size, "the runner's failure propagates out of the launched coroutine")
        assertEquals(ManualRefreshStatus.Idle, h.controller.manualRefreshStatus.value, "the throw ended the status with a null outcome")
        assertTrue(h.fixture.activityCenter.activity.value.idle)

        driver.execute(null, "ALTER TABLE feeds RENAME COLUMN description_x TO description", 0)
        h.controller.refreshFeed("f1")
        h.settle()

        val finished = assertIs<ManualRefreshStatus.Finished>(h.controller.manualRefreshStatus.value)
        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), finished.outcome)
        assertEquals(2L, finished.generation, "the failed refresh's end consumed generation 1")
        assertEquals(0, h.retrimCount, "a single feed's refresh is not a cycle and re-trims nothing")
    }

    @Test
    fun aCycleThatRanRetrimsBeforeItsRefreshAndAgainWhenItsRefreshEndsNotAfterItsSync() {
        val syncGate = CompletableDeferred<Unit>()
        testThreadDispatcher("refresh-controller-io").use { io ->
            withController(syncGate = syncGate, dispatcher = io) { h, _, _ ->
                h.controller.refreshAll()
                h.fixture.awaitSyncing()

                withTimeout(5_000) { while (h.retrimCount < 2) yield() }
                assertEquals("retrim", h.fixture.log.first(), "the first re-trim precedes the fetches")
                assertTrue(
                    h.fixture.log.indexOfLast { it.startsWith("fetch:") } < h.fixture.log.lastIndexOf("retrim"),
                    "the second re-trim follows the fetches",
                )
                assertTrue(h.fixture.activityCenter.activity.value.syncing, "re-trimmed while the sync is still running")

                syncGate.complete(Unit)
                h.settle()
                assertEquals(2, h.retrimCount, "the sync's end re-trims nothing more")
                val finished = assertIs<ManualRefreshStatus.Finished>(h.controller.manualRefreshStatus.value)
                assertEquals(1L, finished.generation)
            }
        }
    }

    @Test
    fun aSelectionCoveringNoFeedRetrimsOnlyBefore() = withController { h, _, _ ->
        h.controller.pullToRefresh(ArticleFilter.Folder("empty"))
        h.settle()

        assertEquals(1, h.retrimCount)
        assertEquals(emptyList(), h.fixture.log.filter { it.startsWith("fetch:") })
        assertEquals(ManualRefreshStatus.Idle, h.controller.manualRefreshStatus.value)
        assertTrue(h.controller.pullRefreshingFilters.value.isEmpty())
    }

    @Test
    fun aBusyCycleRetrimsOnlyBeforeAndLeavesTheStatusAlone() = withController { h, _, _ ->
        // A sync alone in flight is neither idle nor an automatic cycle to join, so the call is Busy.
        val syncGate = CompletableDeferred<Unit>()
        val sync = launch { h.fixture.activityCenter.trackSync { syncGate.await() } }
        withTimeout(5_000) { h.fixture.activityCenter.activity.first { !it.idle } }

        h.controller.refreshAll()
        h.settle()

        assertEquals(1, h.retrimCount)
        assertEquals(ManualRefreshStatus.Idle, h.controller.manualRefreshStatus.value)
        assertEquals(emptyList(), h.fixture.log.filter { it.startsWith("fetch:") })
        syncGate.complete(Unit)
        sync.join()
    }

    @Test
    fun aPullOnABusyCycleWaitsForIdleAndRetrimsOnlyBefore() = withController { h, _, _ ->
        val syncGate = CompletableDeferred<Unit>()
        val sync = launch { h.fixture.activityCenter.trackSync { syncGate.await() } }
        withTimeout(5_000) { h.fixture.activityCenter.activity.first { !it.idle } }

        h.controller.pullToRefresh()
        assertEquals(setOf<ArticleFilter>(ArticleFilter.All), h.controller.pullRefreshingFilters.value)

        syncGate.complete(Unit)
        sync.join()
        h.settle()

        assertTrue(h.controller.pullRefreshingFilters.value.isEmpty())
        assertEquals(1, h.retrimCount)
    }

    @Test
    fun joiningAnAutomaticCycleRetrimsOnlyBeforeThenReportsItsOutcome() {
        val gate = CompletableDeferred<Unit>()
        withController(fetchGate = gate) { h, _, _ ->
            val automatic = launch { h.fixture.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            h.fixture.awaitFeedRefreshing()

            h.controller.refreshAll()
            withTimeout(5_000) { h.controller.manualRefreshStatus.first { it is ManualRefreshStatus.Checking } }
            assertEquals(1, h.retrimCount)

            gate.complete(Unit)
            automatic.join()
            h.settle()

            val finished = assertIs<ManualRefreshStatus.Finished>(h.controller.manualRefreshStatus.value)
            assertEquals(RefreshOutcome(newArticleCount = 2, unreachable = false), finished.outcome)
            assertEquals(1, h.retrimCount, "a joined cycle is not a Ran one, so there is no second re-trim")
        }
    }

    @Test
    fun aPullOnAFilterThatIsAlreadyPendingIsANoOp() {
        val gate = CompletableDeferred<Unit>()
        withController(fetchGate = gate) { h, _, _ ->
            h.controller.pullToRefresh(ArticleFilter.All)
            h.fixture.awaitFeedRefreshing()
            assertEquals(1, h.retrimCount)

            // The pull's own coroutine, plus the ones it handed its cycle's refresh-stage end to.
            val launched = h.job.children.count()
            h.controller.pullToRefresh(ArticleFilter.All)
            assertEquals(launched, h.job.children.count(), "the repeated pull launched nothing")
            assertEquals(setOf<ArticleFilter>(ArticleFilter.All), h.controller.pullRefreshingFilters.value)

            gate.complete(Unit)
            h.settle()

            assertEquals(2, h.retrimCount, "one cycle: a re-trim before and after")
            assertEquals(2, h.fixture.log.count { it.startsWith("fetch:") }, "each of the two feeds fetched once")
            assertTrue(h.controller.pullRefreshingFilters.value.isEmpty())
        }
    }

    @Test
    fun pullsOnDifferentFiltersAreTrackedSeparately() {
        val gate = CompletableDeferred<Unit>()
        withController(currentFilter = { ArticleFilter.Feed("f2") }, fetchGate = gate) { h, _, _ ->
            h.controller.pullToRefresh(ArticleFilter.Feed("f1"))
            h.fixture.awaitFeedRefreshing()
            // The current-selection overload tracks under the filter showing when it started.
            h.controller.pullToRefresh()
            // That pull is only queued on this event loop. Let it reach the runner and find f1's cycle
            // busy before the gate opens: otherwise f1's cycle, resumed on the fetch thread, can end
            // first and the second pull runs a cycle of its own. Its first re-trim is followed by a
            // Busy check that never suspends (Unconfined dispatcher, no suspension on the busy path),
            // so once the count reaches 2 the check has happened.
            withTimeout(5_000) { while (h.retrimCount < 2) yield() }

            assertEquals(
                setOf<ArticleFilter>(ArticleFilter.Feed("f1"), ArticleFilter.Feed("f2")),
                h.controller.pullRefreshingFilters.value,
            )

            gate.complete(Unit)
            h.settle()

            assertTrue(h.controller.pullRefreshingFilters.value.isEmpty())
            // f1's pull ran the cycle (before + after); f2's found it busy (before only).
            assertEquals(3, h.retrimCount)
            assertEquals(1, h.fixture.log.count { it == "fetch:https://ex.com/f1" })
            assertEquals(0, h.fixture.log.count { it == "fetch:https://ex.com/f2" })
        }
    }

    private suspend fun RefreshCycleRunnerFixture.awaitSyncing() {
        withTimeout(5_000) { activityCenter.activity.first { it.syncing } }
    }

    private suspend fun HomeRefreshController.awaitFinished(): ManualRefreshStatus.Finished =
        withTimeout(5_000) { manualRefreshStatus.first { it is ManualRefreshStatus.Finished } } as ManualRefreshStatus.Finished

    @Test
    fun theStatusFinishesWhileTheSyncIsStillRunning() {
        val syncGate = CompletableDeferred<Unit>()
        withController(syncGate = syncGate, dispatcher = Dispatchers.Default) { h, _, _ ->
            h.controller.refreshAll()
            h.fixture.awaitSyncing()

            val finished = h.controller.awaitFinished()
            assertEquals(RefreshOutcome(newArticleCount = 2, unreachable = false), finished.outcome)
            assertEquals(1L, finished.generation)
            assertTrue(h.fixture.activityCenter.activity.value.syncing, "the sync is still running")

            syncGate.complete(Unit)
            h.settle()
            assertEquals(finished, h.controller.manualRefreshStatus.value, "the sync's end changes nothing")
        }
    }

    @Test
    fun aPullReleasesItsFilterWhenTheRefreshEndsNotTheSync() {
        val syncGate = CompletableDeferred<Unit>()
        withController(syncGate = syncGate, dispatcher = Dispatchers.Default) { h, _, _ ->
            h.controller.pullToRefresh(ArticleFilter.All)
            h.fixture.awaitSyncing()

            withTimeout(5_000) { h.controller.pullRefreshingFilters.first { it.isEmpty() } }
            assertIs<ManualRefreshStatus.Finished>(h.controller.awaitFinished())
            withTimeout(5_000) { while (h.retrimCount < 2) yield() }
            assertTrue(h.fixture.activityCenter.activity.value.syncing, "the sync is still running")

            syncGate.complete(Unit)
            h.settle()
            assertEquals(2, h.retrimCount, "the cycle's second re-trim came with its refresh's end, not its sync's")
        }
    }

    @Test
    fun aPullWhileOnlyASyncRunsReleasesAtOnce() = withController { h, _, _ ->
        val syncGate = CompletableDeferred<Unit>()
        val sync = launch { h.fixture.activityCenter.trackSync { syncGate.await() } }
        withTimeout(5_000) { h.fixture.activityCenter.activity.first { it.syncing } }

        h.controller.pullToRefresh()

        withTimeout(5_000) { h.controller.pullRefreshingFilters.first { it.isEmpty() } }
        assertFalse(syncGate.isCompleted, "released while that sync is still running")
        assertEquals(ManualRefreshStatus.Idle, h.controller.manualRefreshStatus.value)
        syncGate.complete(Unit)
        sync.join()
        h.settle()
    }

    @Test
    fun aPullWhileAnotherManualCycleRefreshesStaysUntilThatRefreshEnds() {
        val fetchGate = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        // One thread of its own, so the barrier below runs strictly after the pull's runIfIdle.
        testThreadDispatcher("refresh-controller-io").use { io ->
            withController(fetchGate = fetchGate, syncGate = syncGate, dispatcher = io) { h, _, _ ->
                h.controller.refreshAll()
                h.fixture.awaitFeedRefreshing()

                h.controller.pullToRefresh(ArticleFilter.All)
                // The pull's pre-retrim and its dispatch of runIfIdle onto io happen in one step, so
                // once the retrim shows, runIfIdle is queued there; it returns Busy without suspending,
                // so it has returned once this barrier has run behind it.
                withTimeout(5_000) { while (h.retrimCount < 2) yield() }
                withContext(io) {}
                // Lets the pull, resumed on this thread ahead of the test, reach its Busy wait.
                yield()
                assertEquals(setOf<ArticleFilter>(ArticleFilter.All), h.controller.pullRefreshingFilters.value)

                fetchGate.complete(Unit)
                h.fixture.awaitSyncing()

                withTimeout(5_000) { h.controller.pullRefreshingFilters.first { it.isEmpty() } }
                assertTrue(h.fixture.activityCenter.activity.value.syncing, "released at the refresh's end, with the sync still running")
                assertEquals(1, h.fixture.log.count { it == "fetch:https://ex.com/f1" }, "the pull started no cycle of its own")

                syncGate.complete(Unit)
                h.settle()
            }
        }
    }

    @Test
    fun aPullJoiningAnAutomaticCycleDuringItsSyncGetsTheResultAtOnce() {
        val syncGate = CompletableDeferred<Unit>()
        withController(syncGate = syncGate, dispatcher = Dispatchers.Default) { h, _, _ ->
            val automatic = async(Dispatchers.Default) { h.fixture.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            h.fixture.awaitSyncing()

            h.controller.pullToRefresh(ArticleFilter.All)

            val finished = h.controller.awaitFinished()
            assertEquals(RefreshOutcome(newArticleCount = 2, unreachable = false), finished.outcome)
            withTimeout(5_000) { h.controller.pullRefreshingFilters.first { it.isEmpty() } }
            assertFalse(automatic.isCompleted, "the automatic cycle is still syncing")

            syncGate.complete(Unit)
            automatic.await()
            h.settle()
        }
    }

    @Test
    fun overlappingRefreshesStillCountAsOneCheckAndGenerationStillIncreases() {
        val gate = CompletableDeferred<Unit>()
        withController(fetchGate = gate) { h, _, _ ->
            val automatic = launch { h.fixture.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            h.fixture.awaitFeedRefreshing()

            h.controller.refreshAll()
            h.controller.pullToRefresh(ArticleFilter.All)
            withTimeout(5_000) { while (h.retrimCount < 2) yield() }
            repeat(50) { yield() }
            assertIs<ManualRefreshStatus.Checking>(h.controller.manualRefreshStatus.value)

            gate.complete(Unit)
            automatic.join()
            h.settle()

            val first = assertIs<ManualRefreshStatus.Finished>(h.controller.manualRefreshStatus.value)
            assertEquals(RefreshOutcome(newArticleCount = 2, unreachable = false), first.outcome, "counted once, not doubled")
            assertEquals(1L, first.generation)
            assertTrue(h.controller.pullRefreshingFilters.value.isEmpty())

            h.controller.refreshAll()
            h.settle()
            assertEquals(2L, assertIs<ManualRefreshStatus.Finished>(h.controller.manualRefreshStatus.value).generation)
        }
    }
}

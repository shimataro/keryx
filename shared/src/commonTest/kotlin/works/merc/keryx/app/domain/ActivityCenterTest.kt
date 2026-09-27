package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityCenterTest {

    // Gated operations are launched unconfined, so each `track*` call starts inline (and resumes
    // inline once its gate completes) and the assertions right after it see its effect.

    @Test
    fun startsIdle() = runTest {
        val center = ActivityCenter()
        assertFalse(center.activity.value.feedRefreshing)
    }

    @Test
    fun trackFeedRefreshIsTrueWhileRunningAndFalseAfter() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackFeedRefresh { gate.await() } }

        assertTrue(center.activity.value.feedRefreshing)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.feedRefreshing)
    }

    @Test
    fun concurrentRefreshesStayTrueUntilAllFinish() = runTest {
        val center = ActivityCenter()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackFeedRefresh { gate1.await() } }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackFeedRefresh { gate2.await() } }

        assertTrue(center.activity.value.feedRefreshing)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.feedRefreshing) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.feedRefreshing)
    }

    @Test
    fun trackFeedRefreshReturnsBlockResultAndClearsOnFailure() = runTest {
        val center = ActivityCenter()

        val result = center.trackFeedRefresh { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackFeedRefresh { error("boom") } }
        assertFalse(center.activity.value.feedRefreshing)
    }

    @Test
    fun syncStartsIdle() = runTest {
        val center = ActivityCenter()
        assertFalse(center.activity.value.syncing)
    }

    @Test
    fun trackSyncIsTrueWhileRunningAndFalseAfter() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackSync { gate.await() } }

        assertTrue(center.activity.value.syncing)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.syncing)
    }

    @Test
    fun concurrentSyncsStayTrueUntilAllFinish() = runTest {
        val center = ActivityCenter()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackSync { gate1.await() } }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackSync { gate2.await() } }

        assertTrue(center.activity.value.syncing)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.syncing) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.syncing)
    }

    @Test
    fun trackSyncReturnsBlockResultAndClearsOnFailure() = runTest {
        val center = ActivityCenter()

        val result = center.trackSync { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackSync { error("boom") } }
        assertFalse(center.activity.value.syncing)
    }

    @Test
    fun feedRefreshAndSyncAreIndependent() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackSync { gate.await() } }

        // A sync in flight must not light the feed-refresh indicator, and vice versa.
        assertTrue(center.activity.value.syncing)
        assertFalse(center.activity.value.feedRefreshing)

        gate.complete(Unit)
        job.join()
    }

    @Test
    fun refreshCycleStartsIdle() = runTest {
        val center = ActivityCenter()
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun trackRefreshCycleIsTrueWhileRunningAndFalseAfter() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackRefreshCycle { gate.await() } }

        assertTrue(center.activity.value.refreshCycleRunning)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun trackRefreshCycleReturnsBlockResultAndClearsOnFailure() = runTest {
        val center = ActivityCenter()

        val result = center.trackRefreshCycle { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackRefreshCycle { error("boom") } }
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun concurrentRefreshCyclesStayTrueUntilAllFinish() = runTest {
        val center = ActivityCenter()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackRefreshCycle { gate1.await() } }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { center.trackRefreshCycle { gate2.await() } }

        assertTrue(center.activity.value.refreshCycleRunning)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.refreshCycleRunning) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun nestedRefreshCyclesStayTrueUntilTheOuterOneFinishes() = runTest {
        val center = ActivityCenter()
        val inner = CompletableDeferred<Unit>()
        val outer = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            center.trackRefreshCycle {
                center.trackRefreshCycle { inner.await() }
                outer.await()
            }
        }

        assertTrue(center.activity.value.refreshCycleRunning)

        inner.complete(Unit)
        assertTrue(center.activity.value.refreshCycleRunning) // the outer cycle is still running

        outer.complete(Unit)
        job.join()
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun refreshCycleStaysTrueBetweenItsRefreshAndSync() = runTest {
        val center = ActivityCenter()
        val refreshGate = CompletableDeferred<Unit>()
        val gap = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            center.trackRefreshCycle {
                center.trackFeedRefresh { refreshGate.await() }
                gap.await()
                center.trackSync { syncGate.await() }
            }
        }

        refreshGate.complete(Unit)
        // Between the two operations neither per-operation flag is up, but the cycle still is.
        assertFalse(center.activity.value.feedRefreshing)
        assertFalse(center.activity.value.syncing)
        assertTrue(center.activity.value.refreshCycleRunning)

        gap.complete(Unit)
        assertTrue(center.activity.value.syncing)
        syncGate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.refreshCycleRunning)
    }

    @Test
    fun snapshotFlagsFollowTheirCounters() {
        val snapshot = ActivitySnapshot(feedRefreshCount = 2, syncCount = 0, refreshCycleCount = 1)
        assertTrue(snapshot.feedRefreshing)
        assertFalse(snapshot.syncing)
        assertTrue(snapshot.refreshCycleRunning)
    }

    @Test
    fun snapshotIsIdleOnlyWhenNothingIsInFlight() {
        assertTrue(ActivitySnapshot().idle)
        assertFalse(ActivitySnapshot(feedRefreshCount = 1).idle)
        assertFalse(ActivitySnapshot(syncCount = 1).idle)
        assertFalse(ActivitySnapshot(feedRefreshCount = 1, syncCount = 1).idle)
        // The gap between a cycle's refresh and its sync: neither per-operation counter is up.
        assertFalse(ActivitySnapshot(refreshCycleCount = 1).idle)
        assertFalse(ActivitySnapshot(feedRefreshCount = 1, syncCount = 1, refreshCycleCount = 1).idle)
    }

    /**
     * Regression: the per-flag StateFlows used to be `stateIn` copies of the counters, updated only once
     * their sharing coroutine got dispatched, so a read right after a `track*` call started could
     * still see the old value. [ActivityCenter.activity] must be current the moment each call
     * starts and ends — here read inline, with no suspension point in between that would give a
     * derived copy a chance to catch up.
     */
    @Test
    fun activityReflectsEachTrackCallImmediatelyWithoutDispatching() = runTest {
        val center = ActivityCenter()
        assertEquals(ActivitySnapshot(), center.activity.value)

        center.trackRefreshCycle {
            assertEquals(ActivitySnapshot(refreshCycleCount = 1), center.activity.value)
            center.trackFeedRefresh {
                assertEquals(ActivitySnapshot(feedRefreshCount = 1, refreshCycleCount = 1), center.activity.value)
            }
            assertEquals(ActivitySnapshot(refreshCycleCount = 1), center.activity.value)
            center.trackSync {
                assertEquals(ActivitySnapshot(syncCount = 1, refreshCycleCount = 1), center.activity.value)
            }
            assertEquals(ActivitySnapshot(refreshCycleCount = 1), center.activity.value)
        }

        assertEquals(ActivitySnapshot(), center.activity.value)
        assertTrue(center.activity.value.idle)
    }

    @Test
    fun idleOnlyOnceEveryOverlappingAndNestedOperationHasEnded() = runTest {
        val center = ActivityCenter()
        val refreshGate = CompletableDeferred<Unit>()
        val nestedSyncGate = CompletableDeferred<Unit>()
        val cycleGate = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        // A cycle whose refresh nests a sync of its own, overlapping an independent sync.
        val cycle = launch {
            center.trackRefreshCycle {
                center.trackFeedRefresh {
                    center.trackSync { nestedSyncGate.await() }
                    refreshGate.await()
                }
                cycleGate.await()
            }
        }
        val sync = launch { center.trackSync { syncGate.await() } }
        runCurrent()
        assertEquals(ActivitySnapshot(feedRefreshCount = 1, syncCount = 2, refreshCycleCount = 1), center.activity.value)
        assertFalse(center.activity.value.idle)

        nestedSyncGate.complete(Unit)
        runCurrent()
        assertEquals(ActivitySnapshot(feedRefreshCount = 1, syncCount = 1, refreshCycleCount = 1), center.activity.value)
        assertFalse(center.activity.value.idle)

        refreshGate.complete(Unit)
        runCurrent()
        assertEquals(ActivitySnapshot(syncCount = 1, refreshCycleCount = 1), center.activity.value)
        assertFalse(center.activity.value.idle)

        syncGate.complete(Unit)
        sync.join()
        // Only the cycle is left, sitting in the gap after its refresh.
        assertEquals(ActivitySnapshot(refreshCycleCount = 1), center.activity.value)
        assertFalse(center.activity.value.idle)

        cycleGate.complete(Unit)
        cycle.join()
        assertTrue(center.activity.value.idle)
    }

    @Test
    fun tryTrackRefreshCycleRunsTheBlockInsideACycleWhenIdle() = runTest {
        val center = ActivityCenter()

        val result = center.tryTrackRefreshCycle {
            assertEquals(ActivitySnapshot(refreshCycleCount = 1), center.activity.value)
            7
        }

        assertEquals(7, result)
        assertTrue(center.activity.value.idle)
    }

    @Test
    fun tryTrackRefreshCycleReturnsNullWithoutRunningWhenNotIdle() = runTest {
        val center = ActivityCenter()
        val busyStates: List<suspend (suspend () -> Unit) -> Unit> = listOf(
            { b -> center.trackFeedRefresh { b() } },
            { b -> center.trackSync { b() } },
            { b -> center.trackRefreshCycle { b() } },
        )
        for (busy in busyStates) {
            var ran = false
            busy {
                val result = center.tryTrackRefreshCycle { ran = true }
                assertEquals(null, result)
            }
            assertFalse(ran)
            assertTrue(center.activity.value.idle)
        }
    }

    @Test
    fun tryTrackRefreshCycleClearsTheCycleWhenTheBlockThrows() = runTest {
        val center = ActivityCenter()

        runCatching { center.tryTrackRefreshCycle { error("boom") } }

        assertTrue(center.activity.value.idle)
    }

    @Test
    fun concurrentTryTrackRefreshCycleCallsRunOnlyOne() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = launch(UnconfinedTestDispatcher(testScheduler)) {
            center.tryTrackRefreshCycle { runs++; gate.await() }
        }
        val second = launch(UnconfinedTestDispatcher(testScheduler)) {
            assertEquals(null, center.tryTrackRefreshCycle { runs++ })
        }
        second.join()
        gate.complete(Unit)
        first.join()

        assertEquals(1, runs)
        assertTrue(center.activity.value.idle)
    }

    @Test
    fun tryTrackRefreshCycleAdmitsExactlyOneAcrossRealThreads() = runTest {
        val center = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val runs = MutableStateFlow(0) // CAS-updated counter, safe across threads
        withContext(Dispatchers.Default) {
            val attempts = List(ATTEMPTS) {
                async { center.tryTrackRefreshCycle { runs.update { it + 1 }; gate.await() } }
            }
            // Every attempt except the winner returns null at once; release the winner afterwards.
            while (attempts.count { it.isCompleted } < ATTEMPTS - 1) yield()
            gate.complete(Unit)
            attempts.awaitAll()
        }

        assertEquals(1, runs.value)
        assertTrue(center.activity.value.idle)
    }

    @Test
    fun refreshIndicatorShownTruthTable() {
        // (feedRefreshCount, syncCount, refreshCycleCount) -> expected
        val cases = mapOf(
            Triple(0, 0, 0) to false,
            Triple(1, 0, 0) to true,
            Triple(0, 1, 0) to false,
            Triple(0, 0, 1) to true, // the gap between a cycle's refresh and its sync
            Triple(1, 1, 0) to true,
            Triple(1, 0, 1) to true,
            Triple(0, 1, 1) to false, // the cycle's sync phase: the sync control spins instead
            Triple(1, 1, 1) to true,
        )
        for ((counts, expected) in cases) {
            val snapshot = ActivitySnapshot(counts.first, counts.second, counts.third)
            assertEquals(expected, snapshot.refreshIndicatorShown, "for $snapshot")
        }
    }

    private companion object {
        const val ATTEMPTS = 16
    }
}

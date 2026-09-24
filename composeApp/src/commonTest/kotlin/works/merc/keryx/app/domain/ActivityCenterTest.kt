package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

    // An unconfined scope makes the `stateIn` sharing coroutine run inline, so `feedRefreshing.value`
    // reflects counter changes deterministically (an active collector isn't needed).

    @Test
    fun startsIdle() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        assertFalse(center.activity.value.feedRefreshing)
        scope.cancel()
    }

    @Test
    fun trackFeedRefreshIsTrueWhileRunningAndFalseAfter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackFeedRefresh { gate.await() } }

        assertTrue(center.activity.value.feedRefreshing)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.feedRefreshing)
        scope.cancel()
    }

    @Test
    fun concurrentRefreshesStayTrueUntilAllFinish() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = scope.launch { center.trackFeedRefresh { gate1.await() } }
        val job2 = scope.launch { center.trackFeedRefresh { gate2.await() } }

        assertTrue(center.activity.value.feedRefreshing)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.feedRefreshing) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.feedRefreshing)
        scope.cancel()
    }

    @Test
    fun trackFeedRefreshReturnsBlockResultAndClearsOnFailure() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)

        val result = center.trackFeedRefresh { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackFeedRefresh { error("boom") } }
        assertFalse(center.activity.value.feedRefreshing)
        scope.cancel()
    }

    @Test
    fun syncStartsIdle() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        assertFalse(center.activity.value.syncing)
        scope.cancel()
    }

    @Test
    fun trackSyncIsTrueWhileRunningAndFalseAfter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackSync { gate.await() } }

        assertTrue(center.activity.value.syncing)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.syncing)
        scope.cancel()
    }

    @Test
    fun concurrentSyncsStayTrueUntilAllFinish() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = scope.launch { center.trackSync { gate1.await() } }
        val job2 = scope.launch { center.trackSync { gate2.await() } }

        assertTrue(center.activity.value.syncing)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.syncing) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.syncing)
        scope.cancel()
    }

    @Test
    fun trackSyncReturnsBlockResultAndClearsOnFailure() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)

        val result = center.trackSync { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackSync { error("boom") } }
        assertFalse(center.activity.value.syncing)
        scope.cancel()
    }

    @Test
    fun feedRefreshAndSyncAreIndependent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackSync { gate.await() } }

        // A sync in flight must not light the feed-refresh indicator, and vice versa.
        assertTrue(center.activity.value.syncing)
        assertFalse(center.activity.value.feedRefreshing)

        gate.complete(Unit)
        job.join()
        scope.cancel()
    }

    @Test
    fun refreshCycleStartsIdle() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        assertFalse(center.activity.value.refreshCycleRunning)
        scope.cancel()
    }

    @Test
    fun trackRefreshCycleIsTrueWhileRunningAndFalseAfter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackRefreshCycle { gate.await() } }

        assertTrue(center.activity.value.refreshCycleRunning)

        gate.complete(Unit)
        job.join()
        assertFalse(center.activity.value.refreshCycleRunning)
        scope.cancel()
    }

    @Test
    fun trackRefreshCycleReturnsBlockResultAndClearsOnFailure() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)

        val result = center.trackRefreshCycle { 42 }
        assertEquals(42, result)

        // A throwing block must still decrement the counter (finally).
        runCatching { center.trackRefreshCycle { error("boom") } }
        assertFalse(center.activity.value.refreshCycleRunning)
        scope.cancel()
    }

    @Test
    fun concurrentRefreshCyclesStayTrueUntilAllFinish() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val job1 = scope.launch { center.trackRefreshCycle { gate1.await() } }
        val job2 = scope.launch { center.trackRefreshCycle { gate2.await() } }

        assertTrue(center.activity.value.refreshCycleRunning)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.activity.value.refreshCycleRunning) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.activity.value.refreshCycleRunning)
        scope.cancel()
    }

    @Test
    fun nestedRefreshCyclesStayTrueUntilTheOuterOneFinishes() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val inner = CompletableDeferred<Unit>()
        val outer = CompletableDeferred<Unit>()
        val job = scope.launch {
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
        scope.cancel()
    }

    @Test
    fun refreshCycleStaysTrueBetweenItsRefreshAndSync() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val refreshGate = CompletableDeferred<Unit>()
        val gap = CompletableDeferred<Unit>()
        val syncGate = CompletableDeferred<Unit>()
        val job = scope.launch {
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
        scope.cancel()
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
}

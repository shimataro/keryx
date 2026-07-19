package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        assertFalse(center.feedRefreshing.value)
        scope.cancel()
    }

    @Test
    fun trackFeedRefreshIsTrueWhileRunningAndFalseAfter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackFeedRefresh { gate.await() } }

        assertTrue(center.feedRefreshing.value)

        gate.complete(Unit)
        job.join()
        assertFalse(center.feedRefreshing.value)
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

        assertTrue(center.feedRefreshing.value)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.feedRefreshing.value) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.feedRefreshing.value)
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
        assertFalse(center.feedRefreshing.value)
        scope.cancel()
    }

    @Test
    fun syncStartsIdle() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        assertFalse(center.syncing.value)
        scope.cancel()
    }

    @Test
    fun trackSyncIsTrueWhileRunningAndFalseAfter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackSync { gate.await() } }

        assertTrue(center.syncing.value)

        gate.complete(Unit)
        job.join()
        assertFalse(center.syncing.value)
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

        assertTrue(center.syncing.value)

        gate1.complete(Unit)
        job1.join()
        assertTrue(center.syncing.value) // one still in flight

        gate2.complete(Unit)
        job2.join()
        assertFalse(center.syncing.value)
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
        assertFalse(center.syncing.value)
        scope.cancel()
    }

    @Test
    fun feedRefreshAndSyncAreIndependent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val center = ActivityCenter(scope)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { center.trackSync { gate.await() } }

        // A sync in flight must not light the feed-refresh indicator, and vice versa.
        assertTrue(center.syncing.value)
        assertFalse(center.feedRefreshing.value)

        gate.complete(Unit)
        job.join()
        scope.cancel()
    }
}

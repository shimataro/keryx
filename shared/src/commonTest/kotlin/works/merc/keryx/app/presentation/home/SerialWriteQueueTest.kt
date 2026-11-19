package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.domain.PendingLocalWrites
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SerialWriteQueueTest {

    @Test
    fun aSuspendedWriteHoldsBackTheWritesQueuedAfterIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val queue = SerialWriteQueue(scope, dispatcher)
        val gate = CompletableDeferred<Unit>()
        val landed = mutableListOf<String>()

        queue.enqueue { gate.await(); landed += "first" }
        queue.enqueue { landed += "second" }
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), landed)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("first", "second"), landed)
        scope.cancel()
    }

    @Test
    fun aFailingWriteReachesTheScopesHandlerAndTheNextOneStillRuns() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> failures += e })
        val queue = SerialWriteQueue(scope, dispatcher)
        val landed = mutableListOf<String>()

        queue.enqueue { throw IllegalStateException("write failed") }
        queue.enqueue { landed += "next" }
        testScheduler.advanceUntilIdle()

        assertIs<IllegalStateException>(failures.single())
        assertEquals(listOf("next"), landed)
        scope.cancel()
    }

    @Test
    fun afterWriteRunsOnlyOnceItsWriteSucceeded() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
        val queue = SerialWriteQueue(scope, dispatcher)
        val gate = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()

        queue.enqueue({ gate.await(); steps += "write" }, { steps += "after" })
        queue.enqueue({ throw IllegalStateException("write failed") }, { steps += "after failed" })
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), steps)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("write", "after"), steps)
        scope.cancel()
    }

    @Test
    fun aWriteIsPendingFromEnqueueUntilItHasRunWhetherItSucceedsOrFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
        val pending = PendingLocalWrites()
        val queue = SerialWriteQueue(scope, dispatcher, pending)
        val gate = CompletableDeferred<Unit>()

        queue.enqueue { gate.await() }
        queue.enqueue { throw IllegalStateException("write failed") }
        val idle = launch { pending.awaitIdle() }
        testScheduler.advanceUntilIdle()
        assertFalse(idle.isCompleted)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(idle.isCompleted)
        scope.cancel()
    }

    @Test
    fun writesDroppedWithTheScopeAreNoLongerPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pending = PendingLocalWrites()
        val queue = SerialWriteQueue(scope, dispatcher, pending)
        val ran = mutableListOf<String>()

        queue.enqueue { awaitCancellation() }
        queue.enqueue { ran += "queued" }
        testScheduler.advanceUntilIdle()
        scope.cancel()
        // Refused by the cancelled queue.
        queue.enqueue { ran += "late" }
        testScheduler.advanceUntilIdle()

        val idle = launch { pending.awaitIdle() }
        testScheduler.advanceUntilIdle()
        assertTrue(idle.isCompleted)
        assertEquals(emptyList(), ran)
    }
}

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
}

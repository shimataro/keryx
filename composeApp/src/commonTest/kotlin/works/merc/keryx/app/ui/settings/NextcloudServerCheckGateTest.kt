package works.merc.keryx.app.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.domain.CloudConnectRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NextcloudServerCheckGate] hands a passed server check on only while the dialog is still open, so a
 * check that passes after Cancel never starts the sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextcloudServerCheckGateTest {
    private val request = CloudConnectRequest(serverUrl = "https://cloud.example.com")

    @Test
    fun closingWhileTheCheckRunsDropsItsResult() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        val result = CompletableDeferred<CloudConnectRequest?>()
        val passed = mutableListOf<CloudConnectRequest>()
        gate.start({ result.await() }) { passed += it }
        runCurrent()

        gate.close()
        result.complete(request)
        runCurrent()

        assertTrue(passed.isEmpty())
    }

    @Test
    fun closingCancelsTheRunningCheck() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        var cancelled = false
        gate.start({
            try {
                CompletableDeferred<CloudConnectRequest?>().await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }) {}
        runCurrent()

        gate.close()
        runCurrent()

        assertTrue(cancelled)
    }

    @Test
    fun aPassedCheckIsHandedOnOnce() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        val result = CompletableDeferred<CloudConnectRequest?>()
        val passed = mutableListOf<CloudConnectRequest>()
        gate.start({ result.await() }) { passed += it }
        runCurrent()

        result.complete(request)
        runCurrent()

        assertEquals(listOf(request), passed)
    }

    @Test
    fun aCheckThatProducedNothingIsNotHandedOn() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        val passed = mutableListOf<CloudConnectRequest>()
        gate.start({ null }) { passed += it }
        runCurrent()

        assertTrue(passed.isEmpty())
    }

    @Test
    fun startingAfterCloseDoesNothing() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        var ran = false
        val passed = mutableListOf<CloudConnectRequest>()
        gate.close()

        gate.start({ ran = true; request }) { passed += it }
        runCurrent()

        assertFalse(ran)
        assertTrue(passed.isEmpty())
    }

    @Test
    fun aSecondStartLeavesTheRunningCheckAlone() = runTest {
        val gate = NextcloudServerCheckGate(backgroundScope)
        val first = CompletableDeferred<CloudConnectRequest?>()
        val passed = mutableListOf<CloudConnectRequest>()
        gate.start({ first.await() }) { passed += it }
        runCurrent()
        // The controller refuses a second submit while one runs, which reaches the gate as null.
        gate.start({ null }) { passed += it }
        runCurrent()

        first.complete(request)
        runCurrent()

        assertEquals(listOf(request), passed)
    }
}

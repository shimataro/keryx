package works.merc.keryx.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers [runCatchingUnlessCancelled]: everything captured except the coroutine's own cancellation. */
class CancellationTest {

    @Test
    fun returnsTheBlocksValue() {
        assertEquals(7, runCatchingUnlessCancelled { 7 }.getOrNull())
    }

    @Test
    fun capturesAnOrdinaryFailure() {
        val boom = IllegalStateException("boom")

        val result = runCatchingUnlessCancelled { throw boom }

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun capturesAnErrorToo() {
        assertTrue(runCatchingUnlessCancelled { throw AssertionError("not an Exception") }.isFailure)
    }

    @Test
    fun rethrowsCancellationInsteadOfCapturingIt() {
        assertFailsWith<CancellationException> {
            runCatchingUnlessCancelled { throw CancellationException("stop") }
        }
    }

    @Test
    fun theBlockMaySuspend() = runTest {
        val result = runCatchingUnlessCancelled {
            yield()
            "done"
        }

        assertEquals("done", result.getOrNull())
    }
}

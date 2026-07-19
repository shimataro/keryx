package works.merc.keryx.app.data.cloud

import java.io.IOException
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the real [ProcessBuilder]-backed [RealCommandRunner] against actual subprocesses
 * (not a fake), since its whole job is bounding real OS process I/O.
 */
class RealCommandRunnerTest {
    @Test
    fun runReturnsExitCodeAndOutputForACompletedProcess() {
        val result = RealCommandRunner(timeoutMillis = 5_000).run(listOf("echo", "hello"))
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun runAbortsAndThrowsWhenTheProcessOutlivesTheTimeout() {
        val runner = RealCommandRunner(timeoutMillis = 200)
        val elapsed = measureTimeMillis {
            assertFailsWith<IOException> { runner.run(listOf("sleep", "5")) }
        }
        // Aborted well before the process's own 5s sleep would have finished.
        assertTrue(elapsed < 4_000, "expected the timeout to abort quickly, took ${elapsed}ms")
    }
}

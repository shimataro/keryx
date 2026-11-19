package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PendingLocalWritesTest {

    @Test
    fun awaitIdleReturnsAtOnceWithNothingPending() = runTest {
        val pending = PendingLocalWrites()

        val idle = launch { pending.awaitIdle() }
        runCurrent()

        assertTrue(idle.isCompleted)
    }

    @Test
    fun awaitIdleWaitsUntilEveryBegunWriteHasEnded() = runTest {
        val pending = PendingLocalWrites()
        pending.begin()
        pending.begin()

        val idle = launch { pending.awaitIdle() }
        runCurrent()
        assertFalse(idle.isCompleted)

        pending.end()
        runCurrent()
        assertFalse(idle.isCompleted)

        pending.end()
        runCurrent()
        assertTrue(idle.isCompleted)
    }
}

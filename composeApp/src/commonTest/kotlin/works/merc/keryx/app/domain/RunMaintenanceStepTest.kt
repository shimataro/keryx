package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunMaintenanceStepTest {

    @Test
    fun aSuccessfulStepRuns() = runTest {
        var ran = false
        runMaintenanceStep("step") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun anExceptionInTheStepIsSwallowedRatherThanPropagated() = runTest {
        runMaintenanceStep("step") { error("boom") }
    }

    @Test
    fun cancellationPropagatesInsteadOfBeingSwallowed() = runTest {
        assertFailsWith<CancellationException> {
            runMaintenanceStep("step") { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun laterStepsStillRunAfterAnEarlierStepFails() = runTest {
        val executed = mutableListOf<String>()
        runMaintenanceStep("first") { error("fails") }
        runMaintenanceStep("second") { executed += "second" }
        runMaintenanceStep("third") { executed += "third" }
        assertEquals(listOf("second", "third"), executed)
    }
}

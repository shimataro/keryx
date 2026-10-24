package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.RefreshOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ManualRefreshStatusTest {
    private val idle: ManualRefreshStatus = ManualRefreshStatus.Idle

    @Test
    fun aRefreshChecksThenFinishesWithItsOutcome() {
        val checking = idle.started()
        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = null), checking)

        val outcome = RefreshOutcome(newArticleCount = 3, unreachable = false)
        assertEquals(ManualRefreshStatus.Finished(outcome, generation = 1), checking.ended(outcome, generation = 1))
    }

    @Test
    fun aRefreshThatCoveredNoFeedReturnsToIdle() {
        assertEquals(ManualRefreshStatus.Idle, idle.started().ended(null, generation = 1))
    }

    @Test
    fun overlappingRefreshesKeepCheckingUntilTheLastEndsAndCombineTheirOutcomes() {
        val first = RefreshOutcome(newArticleCount = 2, unreachable = false)
        val second = RefreshOutcome(newArticleCount = 0, unreachable = true)

        val oneLeft = idle.started().started().ended(first, generation = 1)
        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = first), oneLeft)

        assertEquals(
            ManualRefreshStatus.Finished(RefreshOutcome(newArticleCount = 2, unreachable = false), generation = 2),
            oneLeft.ended(second, generation = 2),
            "reaching any feed means the check as a whole was not unreachable",
        )
    }

    @Test
    fun theCombinedOutcomeIsUnreachableOnlyWhenEveryRefreshWas() {
        val unreachable = RefreshOutcome(newArticleCount = 0, unreachable = true)

        val status = idle.started().started().ended(unreachable, generation = 1).ended(unreachable, generation = 2)

        assertEquals(ManualRefreshStatus.Finished(unreachable, generation = 2), status)
    }

    @Test
    fun aRefreshStartingAfterAResultChecksAfresh() {
        val finished = idle.started().ended(RefreshOutcome(newArticleCount = 1, unreachable = false), generation = 1)

        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = null), finished.started())
    }

    @Test
    fun twoChecksWithTheSameOutcomeFinishUnequal() {
        val outcome = RefreshOutcome(newArticleCount = 0, unreachable = true)

        val first = idle.started().ended(outcome, generation = 1)
        val second = first.started().ended(outcome, generation = 2)

        assertNotEquals(first, second, "a StateFlow would otherwise conflate the second result away")
    }

    @Test
    fun anEndWithNothingRunningIsIgnored() {
        assertEquals(idle, idle.ended(RefreshOutcome(newArticleCount = 1, unreachable = false), generation = 1))
    }
}

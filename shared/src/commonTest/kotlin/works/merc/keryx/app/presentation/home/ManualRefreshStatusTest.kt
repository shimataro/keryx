package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.RefreshOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualRefreshStatusTest {
    private val idle: ManualRefreshStatus = ManualRefreshStatus.Idle

    @Test
    fun aRefreshChecksThenFinishesWithItsOutcome() {
        val checking = idle.started()
        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = null), checking)

        val outcome = RefreshOutcome(newArticleCount = 3, unreachable = false)
        assertEquals(ManualRefreshStatus.Finished(outcome), checking.ended(outcome))
    }

    @Test
    fun aRefreshThatCoveredNoFeedReturnsToIdle() {
        assertEquals(ManualRefreshStatus.Idle, idle.started().ended(null))
    }

    @Test
    fun overlappingRefreshesKeepCheckingUntilTheLastEndsAndCombineTheirOutcomes() {
        val first = RefreshOutcome(newArticleCount = 2, unreachable = false)
        val second = RefreshOutcome(newArticleCount = 0, unreachable = true)

        val oneLeft = idle.started().started().ended(first)
        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = first), oneLeft)

        assertEquals(
            ManualRefreshStatus.Finished(RefreshOutcome(newArticleCount = 2, unreachable = false)),
            oneLeft.ended(second),
            "reaching any feed means the check as a whole was not unreachable",
        )
    }

    @Test
    fun theCombinedOutcomeIsUnreachableOnlyWhenEveryRefreshWas() {
        val unreachable = RefreshOutcome(newArticleCount = 0, unreachable = true)

        val status = idle.started().started().ended(unreachable).ended(unreachable)

        assertEquals(ManualRefreshStatus.Finished(unreachable), status)
    }

    @Test
    fun aRefreshStartingAfterAResultChecksAfresh() {
        val finished = idle.started().ended(RefreshOutcome(newArticleCount = 1, unreachable = false))

        assertEquals(ManualRefreshStatus.Checking(running = 1, pending = null), finished.started())
    }

    @Test
    fun anEndWithNothingRunningIsIgnored() {
        assertEquals(idle, idle.ended(RefreshOutcome(newArticleCount = 1, unreachable = false)))
    }
}

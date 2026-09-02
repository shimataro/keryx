package works.merc.keryx.app.ui.home

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure-logic coverage for the "flash the row for the article you just backed out of" feature —
 * see `HomePaneLayoutTest`'s `shouldFlashReturnedArticle` cases for when this is triggered at all.
 */
class ArticleReturnRippleTest {

    // --- ripplePulseFor ---

    @Test
    fun ripplePulseForReturnsThePulseForTheMatchingArticle() {
        assertEquals(7, ripplePulseFor(articleId = "a1", selectedId = "a1", returnRipplePulse = 7))
    }

    @Test
    fun ripplePulseForReturnsZeroForAnyOtherArticle() {
        assertEquals(0, ripplePulseFor(articleId = "a2", selectedId = "a1", returnRipplePulse = 7))
    }

    @Test
    fun ripplePulseForReturnsZeroWhenThePulseItselfIsZero() {
        assertEquals(0, ripplePulseFor(articleId = "a1", selectedId = "a1", returnRipplePulse = 0))
    }

    @Test
    fun ripplePulseForReturnsZeroWhenNothingIsSelected() {
        assertEquals(0, ripplePulseFor(articleId = "a1", selectedId = null, returnRipplePulse = 7))
    }

    // --- playPulseRipple ---

    // UnconfinedTestDispatcher, not the default StandardTestDispatcher: it starts a launched
    // coroutine eagerly (synchronously, up to its first suspension point) rather than merely
    // queuing it, which is what lets the collector below actually be subscribed before
    // playPulseRipple's first emit — with the default dispatcher the collector would still be
    // waiting to be scheduled at that point.
    @Test
    fun playPulseRippleEmitsPressThenReleaseInOrder() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        backgroundScope.launch { source.interactions.collect { interactions += it } }

        source.playPulseRipple()

        assertEquals(2, interactions.size)
        assertIs<PressInteraction.Press>(interactions[0])
        val release = assertIs<PressInteraction.Release>(interactions[1])
        assertEquals(interactions[0], release.press)
    }

    @Test
    fun playPulseRippleEmitsCancelWhenInterruptedBeforeTheHoldCompletes() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        backgroundScope.launch { source.interactions.collect { interactions += it } }

        val job = launch { source.playPulseRipple() }
        // Unconfined runs `job` eagerly up to its first real suspension point (delay()) before
        // launch() even returns — same rationale as the UnconfinedTestDispatcher note above. So
        // by this point Press has already been emitted and the coroutine is parked in delay(),
        // not yet auto-advanced past it.
        job.cancel()
        job.join()

        assertEquals(2, interactions.size)
        assertIs<PressInteraction.Press>(interactions[0])
        val cancel = assertIs<PressInteraction.Cancel>(interactions[1])
        assertEquals(interactions[0], cancel.press)
    }
}

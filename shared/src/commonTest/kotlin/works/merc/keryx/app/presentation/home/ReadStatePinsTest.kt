package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The comparison rules [HomeViewModel]'s reconcile and read-state re-trim judge a pin by. Their
 * concurrent interleavings cannot be staged on the single-scheduler view-model tests, so each
 * branch is pinned here instead.
 */
class ReadStatePinsTest {
    /** Stands in for a pin row: a data class, so two instances can be equal yet distinct. */
    private data class Pin(val isRead: Long)

    private fun sameValue(a: Pin, b: Pin) = a.isRead == b.isRead

    @Test
    fun aPinAddedSinceTheSnapshotCountsAsReplaced() {
        assertTrue(pinReplacedSince(null, Pin(1L)))
    }

    @Test
    fun anEqualButDistinctPinCountsAsReplaced() {
        assertTrue(pinReplacedSince(Pin(1L), Pin(1L)))
    }

    @Test
    fun theSameInstanceIsNotReplaced() {
        val pin = Pin(1L)
        assertFalse(pinReplacedSince(pin, pin))
    }

    @Test
    fun withNoPinBeforeOrNowTheCandidateIsAdded() {
        val candidate = Pin(1L)
        assertSame(candidate, retrimmedSelectionPin(null, null, candidate, ::sameValue))
    }

    @Test
    fun aPinDroppedSinceStaysDropped() {
        assertNull(retrimmedSelectionPin(Pin(1L), null, Pin(1L), ::sameValue))
    }

    @Test
    fun aPinReplacedSinceKeepsItsValue() {
        val now = Pin(0L)
        assertSame(now, retrimmedSelectionPin(Pin(1L), now, Pin(1L), ::sameValue))
    }

    @Test
    fun aPinAddedSinceKeepsItsValue() {
        val now = Pin(0L)
        assertSame(now, retrimmedSelectionPin(null, now, Pin(1L), ::sameValue))
    }

    @Test
    fun anUnchangedPinWithTheCandidatesValueKeepsItsInstance() {
        val pin = Pin(1L)
        assertSame(pin, retrimmedSelectionPin(pin, pin, Pin(1L), ::sameValue))
    }

    @Test
    fun anUnchangedPinWithADifferentValueIsReplacedByTheCandidate() {
        val pin = Pin(0L)
        val candidate = Pin(1L)
        assertSame(candidate, retrimmedSelectionPin(pin, pin, candidate, ::sameValue))
    }
}

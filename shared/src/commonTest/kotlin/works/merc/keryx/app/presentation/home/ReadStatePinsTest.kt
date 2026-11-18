package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleFlags
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertSame(candidate, retrimmedSelectionPin(null, null, candidate))
    }

    @Test
    fun aPinDroppedSinceStaysDropped() {
        assertNull(retrimmedSelectionPin(Pin(1L), null, Pin(1L)))
    }

    @Test
    fun aPinReplacedSinceKeepsItsValue() {
        val now = Pin(0L)
        assertSame(now, retrimmedSelectionPin(Pin(1L), now, Pin(1L)))
    }

    @Test
    fun aPinAddedSinceKeepsItsValue() {
        val now = Pin(0L)
        assertSame(now, retrimmedSelectionPin(null, now, Pin(1L)))
    }

    @Test
    fun anUnchangedPinWithTheCandidatesValueKeepsItsInstance() {
        val pin = Pin(1L)
        assertSame(pin, retrimmedSelectionPin(pin, pin, Pin(1L)))
    }

    /**
     * The candidate comes from the selection, which a concurrent reconcile pass refreshes only after
     * the pin: an unchanged pin disagreeing with it is the pass's newer verdict, not something to undo.
     */
    @Test
    fun anUnchangedPinWithADifferentValueIsKeptOverTheCandidate() {
        val pin = Pin(0L)
        assertSame(pin, retrimmedSelectionPin(pin, pin, Pin(1L)))
    }

    private fun row(id: String, isRead: Long = 0L, isStarred: Long = 0L) = ArticleListRow(
        id = id,
        feed_id = "feed",
        title = "title $id",
        url = "https://example.com/$id",
        published_at = 1L,
        created_at = 1L,
        is_read = isRead,
        is_starred = isStarred,
    )

    private fun flags(isRead: Long = 0L, isStarred: Long = 0L) = ArticleFlags(isRead, isStarred)

    @Test
    fun readPinReplacedSinceTheSnapshotIsKeptEvenIfFlagsDisagree() {
        val old = row("a", isRead = 1L)
        val replaced = old.copy()
        val result = reconciledReadPins(mapOf("a" to replaced), mapOf("a" to old), mapOf("a" to flags(isRead = 0L)), null)
        assertSame(replaced, result["a"])
    }

    @Test
    fun readPinAddedSinceTheSnapshotIsKept() {
        val added = row("a", isRead = 1L)
        val result = reconciledReadPins(mapOf("a" to added), emptyMap(), emptyMap(), null)
        assertSame(added, result["a"])
    }

    @Test
    fun readPinMatchingCurrentIsReadIsKept() {
        val pin = row("a", isRead = 1L)
        val result = reconciledReadPins(mapOf("a" to pin), mapOf("a" to pin), mapOf("a" to flags(isRead = 1L)), null)
        assertSame(pin, result["a"])
    }

    @Test
    fun nonSelectedReadPinChangedExternallyIsDropped() {
        val pin = row("a", isRead = 1L)
        val result = reconciledReadPins(mapOf("a" to pin), mapOf("a" to pin), mapOf("a" to flags(isRead = 0L)), "other")
        assertTrue(result.isEmpty())
    }

    @Test
    fun nonSelectedReadPinOfATombstonedArticleIsDropped() {
        val pin = row("a", isRead = 1L)
        val result = reconciledReadPins(mapOf("a" to pin), mapOf("a" to pin), emptyMap(), "other")
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectedReadPinIsRefreshedToTheCurrentValueWhileAlive() {
        val pin = row("a", isRead = 0L)
        val result = reconciledReadPins(mapOf("a" to pin), mapOf("a" to pin), mapOf("a" to flags(isRead = 1L)), "a")
        assertEquals(pin.copy(is_read = 1L), result["a"])
    }

    @Test
    fun selectedReadPinIsDroppedWhenItsArticleIsGone() {
        val pin = row("a", isRead = 0L)
        val result = reconciledReadPins(mapOf("a" to pin), mapOf("a" to pin), emptyMap(), "a")
        assertTrue(result.isEmpty())
    }

    @Test
    fun readPinsAreJudgedIndependently() {
        val keep = row("keep", isRead = 1L)
        val drop = row("drop", isRead = 1L)
        val result = reconciledReadPins(
            mapOf("keep" to keep, "drop" to drop),
            mapOf("keep" to keep, "drop" to drop),
            mapOf("keep" to flags(isRead = 1L), "drop" to flags(isRead = 0L)),
            null,
        )
        assertEquals(setOf("keep"), result.keys)
    }

    @Test
    fun unstarredPinAddedSinceTheSnapshotIsKept() {
        val added = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to added), emptyMap(), mapOf("a" to flags(isStarred = 1L)))
        assertSame(added, result["a"])
    }

    @Test
    fun unstarredPinIsKeptWhileIsStarredMatches() {
        val pin = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to pin), mapOf("a" to pin), mapOf("a" to flags(isStarred = 0L)))
        assertSame(pin, result["a"])
    }

    @Test
    fun unstarredPinIsDroppedWhenIsStarredChanged() {
        val pin = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to pin), mapOf("a" to pin), mapOf("a" to flags(isStarred = 1L)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun unstarredPinIsDroppedWhenItsArticleIsGone() {
        val pin = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to pin), mapOf("a" to pin), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun unstarredPinReplacedSinceTheSnapshotIsKeptDespiteStaleFlags() {
        // A re-starred pin, then unstarred again after the flags were requested: the flags still
        // say starred, but they predate the write behind the replacement.
        val old = row("a", isStarred = 1L)
        val replaced = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to replaced), mapOf("a" to old), mapOf("a" to flags(isStarred = 1L)))
        assertSame(replaced, result["a"])
    }

    @Test
    fun unstarredPinRePinnedWithAnEqualValueIsKeptDespiteStaleFlags() {
        val old = row("a", isStarred = 0L)
        val rePinned = row("a", isStarred = 0L)
        val result = reconciledUnstarredPins(mapOf("a" to rePinned), mapOf("a" to old), mapOf("a" to flags(isStarred = 1L)))
        assertSame(rePinned, result["a"])
    }
}

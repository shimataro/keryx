package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * `slotIndex` assigns each of `ArticleWebViewCarousel`'s fixed physical `WebView` slots the page
 * index sharing its residue modulo `ARTICLE_READER_SLOT_COUNT` (3). This is what lets a swipe
 * between adjacent pages reuse two of the three `WebView`s untouched — see that composable's own
 * KDoc for why reuse (rather than the pager's own per-page composition) is what the fix relies on.
 */
class ArticleWebViewSlotTest {
    @Test
    fun theThreeSlotsCoverTheSettledPageAndBothNeighbours() {
        val indices = (0..2).map { slot -> slotIndex(slot, currentPage = 5, pageCount = 10) }
        assertEquals(setOf(4, 5, 6), indices.toSet())
    }

    @Test
    fun consecutivePagesNeverShareASlot() {
        // Any three consecutive indices occupy three distinct residues mod 3 — the invariant the
        // whole reuse scheme depends on.
        for (currentPage in 1..8) {
            val slots = (currentPage - 1..currentPage + 1).map { index ->
                ((index % ARTICLE_READER_SLOT_COUNT) + ARTICLE_READER_SLOT_COUNT) % ARTICLE_READER_SLOT_COUNT
            }
            assertEquals(3, slots.toSet().size, "currentPage=$currentPage produced colliding slots: $slots")
        }
    }

    @Test
    fun steppingForwardByOneKeepsTwoSlotsUnchanged() {
        val before = (0..2).map { slot -> slotIndex(slot, currentPage = 5, pageCount = 10) }
        val after = (0..2).map { slot -> slotIndex(slot, currentPage = 6, pageCount = 10) }
        val unchanged = before.indices.count { before[it] == after[it] }
        assertEquals(2, unchanged)
    }

    @Test
    fun noSlotForBeforeTheFirstPage() {
        val indices = (0..2).map { slot -> slotIndex(slot, currentPage = 0, pageCount = 10) }
        assertEquals(setOf(null, 0, 1), indices.toSet())
    }

    @Test
    fun noSlotForAfterTheLastPage() {
        val indices = (0..2).map { slot -> slotIndex(slot, currentPage = 9, pageCount = 10) }
        assertEquals(setOf(8, 9, null), indices.toSet())
    }

    @Test
    fun aSinglePagePagerLeavesTwoSlotsEmpty() {
        val indices = (0..2).map { slot -> slotIndex(slot, currentPage = 0, pageCount = 1) }
        assertEquals(1, indices.count { it == 0 })
        assertEquals(2, indices.count { it == null })
    }

    @Test
    fun anEmptyPagerAssignsNothing() {
        for (slot in 0..2) {
            assertNull(slotIndex(slot, currentPage = 0, pageCount = 0))
        }
    }

    @Test
    fun theSameSlotFollowsItsPageAcrossASwipeAwayAndBack() {
        // The slot holding the settled page at currentPage=5 must still hold it once the user swipes
        // to 6 and back — this is what preserves reading position one page away.
        val slotAtFive = (0..2).first { slotIndex(it, currentPage = 5, pageCount = 10) == 5 }
        assertEquals(5, slotIndex(slotAtFive, currentPage = 6, pageCount = 10))
        assertEquals(5, slotIndex(slotAtFive, currentPage = 5, pageCount = 10))
    }

    @Test
    fun twoPagesAwayReassignsTheSlot() {
        // Swiping two pages further no longer resolves to the original page for that same slot —
        // the documented "two articles away and back loses position" limit.
        val slotAtFive = (0..2).first { slotIndex(it, currentPage = 5, pageCount = 10) == 5 }
        assertNotEquals(5, slotIndex(slotAtFive, currentPage = 7, pageCount = 10))
    }
}

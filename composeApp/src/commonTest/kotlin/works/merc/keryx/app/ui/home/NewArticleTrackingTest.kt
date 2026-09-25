package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Most tests below are about the id-diff bookkeeping alone, so they fold in a result whose every id
 * is a freshly inserted row (the rowid check passes for all of them); the rowid-specific behavior
 * has its own tests further down, calling the two-argument [withList] directly.
 */
private fun NewArticleTracking.withList(ids: Set<String>): NewArticleTracking = withList(ids, inserted = ids)

class NewArticleTrackingTest {

    // --- withList: seeding ---

    @Test
    fun withListSeedsKnownIdsWithoutMarkingAnythingUnseen() {
        val tracking = NewArticleTracking().withList(setOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), tracking.knownIds)
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withListOnAnAlreadySeededTrackerDetectsNewIds() {
        val seeded = NewArticleTracking().withList(setOf("a", "b"))
        val updated = seeded.withList(setOf("a", "b", "c", "d"))
        assertEquals(setOf("c", "d"), updated.unseenIds)
        assertEquals(setOf("a", "b", "c", "d"), updated.knownIds)
    }

    @Test
    fun withListAccumulatesAcrossMultipleUpdates() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b"))
            .withList(setOf("a", "b", "c"))
        assertEquals(setOf("b", "c"), tracking.unseenIds)
    }

    @Test
    fun withListDropsUnseenIdsThatFellOutOfTheListAgain() {
        // e.g. an article deleted or filtered out of the query before it was ever seen.
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b"))
            .withList(setOf("a"))
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withListLeavesAlreadySeenIdsOutOfUnseenEvenIfTheyReappear() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b"))
            .withVisible(setOf("b"))
            .withList(setOf("a", "b"))
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withListTreatsAnEmptyBaselineAsStillSeeding() {
        // e.g. a brand-new feed with no articles yet, then its first fetch lands: there was
        // nothing in the empty list the user could have missed, so the arrival becomes the new
        // baseline rather than a batch of "new" articles.
        val tracking = NewArticleTracking()
            .withList(emptySet())
            .withList(setOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), tracking.knownIds)
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withListStillDetectsArrivalsAfterAnEmptyBaselineHasFilled() {
        val tracking = NewArticleTracking()
            .withList(emptySet())
            .withList(setOf("a"))
            .withList(setOf("a", "b"))
        assertEquals(setOf("b"), tracking.unseenIds)
    }

    @Test
    fun withListSeedingIgnoresInsertedAndKeepsTheWatermark() {
        val tracking = NewArticleTracking(insertedAfterRowId = 42).withList(setOf("a", "b"), inserted = setOf("a", "b"))
        assertEquals(setOf("a", "b"), tracking.knownIds)
        assertTrue(tracking.unseenIds.isEmpty())
        assertEquals(42, tracking.insertedAfterRowId)
    }

    // --- withList: rowid-inserted check ---

    @Test
    fun withListDoesNotCountACandidateThatWasNotNewlyInserted() {
        // e.g. an existing article re-starred while browsing Starred, or its feed moved into the
        // folder being viewed: new to this list's id set, but not a newly inserted row.
        val tracking = NewArticleTracking()
            .withList(setOf("a"), inserted = emptySet())
            .withList(setOf("a", "b", "c"), inserted = setOf("c"))
        assertEquals(setOf("c"), tracking.unseenIds)
        assertEquals(setOf("a", "b", "c"), tracking.knownIds)
    }

    @Test
    fun withListIgnoresInsertedIdsThatAreAlreadyKnown() {
        val tracking = NewArticleTracking()
            .withList(setOf("a", "b"), inserted = emptySet())
            .withList(setOf("a", "b"), inserted = setOf("b"))
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withListKeepsKnownIdsCumulativeSoAReenteringArticleIsNotRecounted() {
        val counted = NewArticleTracking()
            .withList(setOf("a"), inserted = emptySet())
            .withList(setOf("a", "b"), inserted = setOf("b"))
        assertEquals(setOf("b"), counted.unseenIds)
        // "b" leaves the result (e.g. unstarred) — it drops out of unseen but stays known...
        val left = counted.withList(setOf("a"), inserted = emptySet())
        assertTrue(left.unseenIds.isEmpty())
        assertEquals(setOf("a", "b"), left.knownIds)
        // ...so coming back (re-starred) doesn't count it again, even though its row is still
        // above the watermark.
        val back = left.withList(setOf("a", "b"), inserted = setOf("b"))
        assertTrue(back.unseenIds.isEmpty())
    }

    @Test
    fun withListDoesNotCountAnExistingArticleReenteringAfterLeaving() {
        val tracking = NewArticleTracking()
            .withList(setOf("a", "b"), inserted = emptySet())
            .withList(setOf("a"), inserted = emptySet())
            .withList(setOf("a", "b"), inserted = emptySet())
        assertTrue(tracking.unseenIds.isEmpty())
    }

    // --- candidatesIn ---

    @Test
    fun candidatesInIsEmptyForAnUnseededTracker() {
        assertTrue(NewArticleTracking().candidatesIn(setOf("a")).isEmpty())
    }

    @Test
    fun candidatesInIsEmptyForAnEmptyBaseline() {
        assertTrue(NewArticleTracking().withList(emptySet()).candidatesIn(setOf("a")).isEmpty())
    }

    @Test
    fun candidatesInReturnsTheIdsNotYetKnown() {
        val tracking = NewArticleTracking().withList(setOf("a")).withList(setOf("b"))
        assertEquals(setOf("c"), tracking.candidatesIn(setOf("a", "b", "c")))
    }

    // --- withAcknowledged ---

    @Test
    fun withAcknowledgedLeavesAnUnseededTrackerUnchanged() {
        val tracking = NewArticleTracking()
        assertEquals(tracking, tracking.withAcknowledged(setOf("a")))
    }

    @Test
    fun withAcknowledgedLeavesAnEmptyBaselineUnchanged() {
        val tracking = NewArticleTracking().withList(emptySet())
        assertEquals(tracking, tracking.withAcknowledged(setOf("a")))
    }

    @Test
    fun withAcknowledgedBeforeTheEmissionKeepsThoseIdsFromBeingCounted() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withAcknowledged(setOf("b"))
            .withList(setOf("a", "b"))
        assertTrue(tracking.unseenIds.isEmpty())
        assertEquals(setOf("a", "b"), tracking.knownIds)
    }

    @Test
    fun withAcknowledgedAfterTheEmissionRemovesThoseIdsFromUnseen() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b"))
            .withAcknowledged(setOf("b"))
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun withAcknowledgedKeepsOtherUnseenIds() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b", "c"))
            .withAcknowledged(setOf("b", "x"))
        assertEquals(setOf("c"), tracking.unseenIds)
    }

    @Test
    fun withAcknowledgedGivesTheSameResultWhicheverSideOfTheEmissionItLands() {
        val seeded = NewArticleTracking().withList(setOf("a")).withList(setOf("a", "c"))
        val before = seeded.withAcknowledged(setOf("b")).withList(setOf("a", "b", "c"))
        val after = seeded.withList(setOf("a", "b", "c")).withAcknowledged(setOf("b"))
        assertEquals(setOf("c"), before.unseenIds)
        assertEquals(before.unseenIds, after.unseenIds)
    }

    // --- withVisible ---

    @Test
    fun withVisibleRemovesReportedIdsFromUnseen() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b", "c"))
            .withVisible(setOf("b"))
        assertEquals(setOf("c"), tracking.unseenIds)
    }

    @Test
    fun withVisibleIsANoOpWhenNothingIsUnseen() {
        val tracking = NewArticleTracking().withList(setOf("a"))
        val result = tracking.withVisible(setOf("a"))
        assertEquals(tracking, result)
    }

    // --- allSeen ---

    @Test
    fun allSeenClearsEveryUnseenId() {
        val tracking = NewArticleTracking()
            .withList(setOf("a"))
            .withList(setOf("a", "b", "c"))
            .allSeen()
        assertTrue(tracking.unseenIds.isEmpty())
    }

    @Test
    fun allSeenIsANoOpWhenAlreadyEmpty() {
        val tracking = NewArticleTracking().withList(setOf("a"))
        val result = tracking.allSeen()
        assertEquals(tracking, result)
    }
}

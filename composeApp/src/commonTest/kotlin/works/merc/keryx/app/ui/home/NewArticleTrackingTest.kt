package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

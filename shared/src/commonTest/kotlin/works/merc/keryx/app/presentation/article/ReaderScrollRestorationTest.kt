package works.merc.keryx.app.presentation.article

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderScrollRestorationTest {
    private val start = 1_000L
    private val oneSecondLater = start + 1_000L
    private val settled = start + READER_SCROLL_SETTLE_WINDOW_MS

    /** A document loaded for the first time with [fraction] to restore. */
    private fun restoring(fraction: Double, key: String = "a"): ReaderScrollRestoration =
        ReaderScrollRestoration(SavedReaderScroll(key, fraction)).also { it.documentReplaced(key) }

    @Test
    fun aReloadReturnsToTheLastRecordedPositionOnceLoaded() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.reloadStarted()
        assertEquals(0.4, restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aReloadAtTheTopRestoresNothing() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.scrolled(0.0, start)
        restoration.reloadStarted()
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aSecondReloadReturnsToTheSamePositionEvenIfTheFirstNeverFinished() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.reloadStarted()
        restoration.reloadStarted()
        assertEquals(0.4, restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aFinishedLoadWithoutARestoreScrollsNowhere() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
        assertNull(restoration.contentResized(start))
        assertFalse(restoration.isRestoring(start))
    }

    @Test
    fun aNewDocumentForgetsThePreviousPosition() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.documentReplaced("b")
        restoration.reloadStarted()
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
        // Nor does returning to the first document bring its position back.
        restoration.documentReplaced("a")
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aRestoredPositionIsAppliedToItsDocumentOnceLoaded() {
        val restoration = restoring(0.7)
        assertEquals(0.7, restoration.lastFraction)
        assertEquals(0.7, restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aRestoredPositionIsOfferedToItsOwnDocumentOnly() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(documentKey = "g", fraction = 0.6))
        // The placeholder shown while the body loads comes first and leaves the offer in place.
        restoration.documentReplaced("c")
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
        assertEquals(SavedReaderScroll("g", 0.6), restoration.saved)
        restoration.documentReplaced("g")
        assertEquals(0.6, restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aRestoredPositionIsOfferedOnce() {
        val restoration = restoring(0.6, key = "g")
        restoration.userInteracted()
        restoration.scrolled(0.0, start)
        restoration.documentReplaced("h")
        restoration.documentReplaced("g")
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun theUsersOwnInputWithdrawsAnUnusedOffer() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(documentKey = "g", fraction = 0.6))
        restoration.documentReplaced("c")
        restoration.userInteracted()
        assertNull(restoration.saved)
        restoration.documentReplaced("g")
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aRestoredTopOrInvalidPositionOffersNothing() {
        assertNull(ReaderScrollRestoration(SavedReaderScroll("g", 0.0)).saved)
        assertNull(ReaderScrollRestoration(SavedReaderScroll("g", -0.2)).saved)
        assertNull(ReaderScrollRestoration(SavedReaderScroll("g", Double.NaN)).saved)
        assertNull(ReaderScrollRestoration(SavedReaderScroll("g", Double.POSITIVE_INFINITY)).saved)
        assertNull(restoring(0.0).documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aRestoredPositionPastTheBottomIsClamped() {
        assertEquals(1.0, restoring(1.5).documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun growthWithinTheSettleWindowReappliesThePosition() {
        val restoration = restoring(0.5)
        restoration.documentFinished(start, reapplyOnGrowth = true)
        assertTrue(restoration.isRestoring(oneSecondLater))
        assertEquals(0.5, restoration.contentResized(oneSecondLater))
        assertEquals(0.5, restoration.contentResized(start + 2_000L))
    }

    @Test
    fun growthAfterTheSettleWindowLeavesThePageAlone() {
        val restoration = restoring(0.5)
        restoration.documentFinished(start, reapplyOnGrowth = true)
        assertNull(restoration.contentResized(settled))
        assertFalse(restoration.isRestoring(settled))
    }

    @Test
    fun withAScreenReaderThePositionIsAppliedOnceAndNeverAgainOnGrowth() {
        val restoration = restoring(0.5)
        assertEquals(0.5, restoration.documentFinished(start, reapplyOnGrowth = false))
        assertFalse(restoration.isRestoring(start))
        assertNull(restoration.contentResized(start))
        // The screen reader's own scrolling is recorded straight away.
        assertEquals(0.3, restoration.scrolled(0.3, start))
        assertEquals(SavedReaderScroll("a", 0.3), restoration.saved)
    }

    @Test
    fun theUsersOwnInputEndsTheRestore() {
        val restoration = restoring(0.5)
        restoration.documentFinished(start, reapplyOnGrowth = true)
        restoration.userInteracted()
        assertNull(restoration.contentResized(start))
        assertEquals(0.2, restoration.scrolled(0.2, start))
        assertEquals(0.2, restoration.lastFraction)
    }

    @Test
    fun theReadersOwnScrollWhileRestoringIsNotRecorded() {
        val restoration = restoring(0.5)
        assertNull(restoration.scrolled(0.1, start))
        restoration.documentFinished(start, reapplyOnGrowth = true)
        assertNull(restoration.scrolled(0.3, oneSecondLater))
        assertEquals(0.5, restoration.lastFraction)
        assertEquals(SavedReaderScroll("a", 0.5), restoration.saved)
    }

    @Test
    fun scrollingIsRecordedAgainOnceTheSettleWindowPassed() {
        val restoration = restoring(0.5)
        restoration.documentFinished(start, reapplyOnGrowth = true)
        assertEquals(0.6, restoration.scrolled(0.6, settled))
        assertEquals(SavedReaderScroll("a", 0.6), restoration.saved)
    }

    @Test
    fun aRecordedScrollIsReportedBackClampedAndAnInvalidOneIsNot() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        assertEquals(0.4, restoration.scrolled(0.4, start))
        assertEquals(1.0, restoration.scrolled(1.4, start))
        assertEquals(0.0, restoration.scrolled(-0.2, start))
        assertNull(restoration.lastFraction)
        assertNull(restoration.scrolled(Double.NaN, start))
    }

    @Test
    fun aViewRebuiltAfterItsContentProcessDiedReturnsToThePosition() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.reloadStarted()
        // The rebuilt web view loads the same document again: not a replacement.
        restoration.documentReplaced("a")
        assertEquals(0.4, restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun aDifferentDocumentArrivingDuringTheRebuildStartsAtTheTop() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        restoration.scrolled(0.4, start)
        restoration.reloadStarted()
        restoration.documentReplaced("b")
        assertNull(restoration.documentFinished(start, reapplyOnGrowth = true))
    }

    @Test
    fun onlyAPositionBelowTheTopIsRestorable() {
        assertEquals(0.5, restorableScrollFraction(0.5))
        assertEquals(1.0, restorableScrollFraction(1.4))
        assertNull(restorableScrollFraction(0.0))
        assertNull(restorableScrollFraction(-0.2))
        assertNull(restorableScrollFraction(Double.NaN))
        assertNull(restorableScrollFraction(Double.POSITIVE_INFINITY))
    }
}

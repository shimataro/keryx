package works.merc.keryx.app.ui.home

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ReaderScrollRestorationTest {
    private val time = TestTimeSource()
    private val start = time.markNow()

    @Test
    fun aReloadReturnsToTheLastRecordedPositionOnceLoaded() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        restoration.scrolled(0.4f, start)
        restoration.reloadStarted()
        assertEquals(0.4f, restoration.documentFinished(start))
    }

    @Test
    fun aReloadAtTheTopRestoresNothing() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        restoration.scrolled(0.4f, start)
        restoration.scrolled(0f, start)
        restoration.reloadStarted()
        assertNull(restoration.documentFinished(start))
    }

    @Test
    fun aFinishedLoadWithoutARestoreScrollsNowhere() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        restoration.scrolled(0.4f, start)
        assertNull(restoration.documentFinished(start))
        assertNull(restoration.contentResized(start))
        assertFalse(restoration.isRestoring(start))
    }

    @Test
    fun aNewDocumentForgetsThePreviousPosition() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        restoration.scrolled(0.4f, start)
        restoration.documentReplaced(2)
        restoration.reloadStarted()
        assertNull(restoration.documentFinished(start))
        // Nor does returning to the first document bring its position back.
        restoration.documentReplaced(1)
        assertNull(restoration.documentFinished(start))
    }

    @Test
    fun aRestoredPositionIsOfferedToItsOwnDocumentOnly() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(documentKey = 7, fraction = 0.6f))
        // The placeholder shown while the body loads comes first and leaves the offer in place.
        restoration.documentReplaced(3)
        assertNull(restoration.documentFinished(start))
        assertEquals(SavedReaderScroll(7, 0.6f), restoration.saved)
        restoration.documentReplaced(7)
        assertEquals(0.6f, restoration.documentFinished(start))
    }

    @Test
    fun aRestoredPositionIsOfferedOnce() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(documentKey = 7, fraction = 0.6f))
        restoration.documentReplaced(7)
        restoration.userInteracted()
        restoration.scrolled(0f, start)
        restoration.documentReplaced(8)
        restoration.documentReplaced(7)
        assertNull(restoration.documentFinished(start))
    }

    @Test
    fun theUsersOwnInputWithdrawsAnUnusedOffer() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(documentKey = 7, fraction = 0.6f))
        restoration.documentReplaced(3)
        restoration.userInteracted()
        assertNull(restoration.saved)
        restoration.documentReplaced(7)
        assertNull(restoration.documentFinished(start))
    }

    @Test
    fun aRestoredTopOrInvalidPositionOffersNothing() {
        assertNull(ReaderScrollRestoration(SavedReaderScroll(7, 0f)).saved)
        assertNull(ReaderScrollRestoration(SavedReaderScroll(7, Float.NaN)).saved)
    }

    @Test
    fun growthWithinTheSettleWindowReappliesThePosition() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(7, 0.5f))
        restoration.documentReplaced(7)
        restoration.documentFinished(start)
        assertTrue(restoration.isRestoring(start + 1.seconds))
        assertEquals(0.5f, restoration.contentResized(start + 1.seconds))
        assertEquals(0.5f, restoration.contentResized(start + 2.seconds))
    }

    @Test
    fun growthAfterTheSettleWindowLeavesThePageAlone() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(7, 0.5f))
        restoration.documentReplaced(7)
        restoration.documentFinished(start)
        assertNull(restoration.contentResized(start + READER_SCROLL_SETTLE_WINDOW))
        assertFalse(restoration.isRestoring(start + READER_SCROLL_SETTLE_WINDOW))
    }

    @Test
    fun theUsersOwnInputEndsTheRestore() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(7, 0.5f))
        restoration.documentReplaced(7)
        restoration.documentFinished(start)
        restoration.userInteracted()
        assertNull(restoration.contentResized(start))
        assertEquals(0.2f, restoration.scrolled(0.2f, start))
        assertEquals(0.2f, restoration.lastFraction)
    }

    @Test
    fun theReadersOwnScrollWhileRestoringIsNotRecorded() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(7, 0.5f))
        restoration.documentReplaced(7)
        assertNull(restoration.scrolled(0.1f, start))
        restoration.documentFinished(start)
        assertNull(restoration.scrolled(0.3f, start + 1.seconds))
        assertEquals(0.5f, restoration.lastFraction)
        assertEquals(SavedReaderScroll(7, 0.5f), restoration.saved)
    }

    @Test
    fun scrollingIsRecordedAgainOnceTheSettleWindowPassed() {
        val restoration = ReaderScrollRestoration(SavedReaderScroll(7, 0.5f))
        restoration.documentReplaced(7)
        restoration.documentFinished(start)
        assertEquals(0.6f, restoration.scrolled(0.6f, start + READER_SCROLL_SETTLE_WINDOW))
        assertEquals(SavedReaderScroll(7, 0.6f), restoration.saved)
    }

    @Test
    fun aRecordedScrollIsClamped() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        assertEquals(1f, restoration.scrolled(1.4f, start))
        assertEquals(0f, restoration.scrolled(-0.2f, start))
        assertNull(restoration.lastFraction)
    }

    @Test
    fun theSaverRoundTripsThePositionAndSavesNothingWithoutOne() {
        val scope = SaverScope { true }
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced(1)
        assertNull(with(ReaderScrollRestorationSaver) { scope.save(restoration) })

        restoration.scrolled(0.4f, start)
        val saved = with(ReaderScrollRestorationSaver) { scope.save(restoration) }!!
        val restored = ReaderScrollRestorationSaver.restore(saved)!!
        assertEquals(SavedReaderScroll(1, 0.4f), restored.saved)
        restored.documentReplaced(1)
        assertEquals(0.4f, restored.documentFinished(start))
    }
}

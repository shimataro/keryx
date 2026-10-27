package works.merc.keryx.app.ui.home

import androidx.compose.runtime.saveable.SaverScope
import works.merc.keryx.app.presentation.article.ReaderScrollRestoration
import works.merc.keryx.app.presentation.article.SavedReaderScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReaderScrollRestorationSaverTest {
    private val scope = SaverScope { true }

    private fun save(restoration: ReaderScrollRestoration): Any? =
        with(ReaderScrollRestorationSaver) { scope.save(restoration) }

    @Test
    fun theSaverRoundTripsThePositionAndSavesNothingWithoutOne() {
        val restoration = ReaderScrollRestoration()
        restoration.documentReplaced("a")
        assertNull(save(restoration))

        restoration.scrolled(0.4, 0L)
        val restored = ReaderScrollRestorationSaver.restore(save(restoration)!!)!!
        assertEquals(SavedReaderScroll("a", 0.4), restored.saved)
        restored.documentReplaced("a")
        assertEquals(0.4, restored.documentFinished(0L, reapplyOnGrowth = true))
    }

    @Test
    fun aSavedValueOfAnotherShapeRestoresNothing() {
        // An older build's shape (an Int key and a Float), and values that are no list at all.
        for (saved in listOf<Any>(listOf(7, 0.5f), listOf("a"), listOf("a", "b"), "a", 42)) {
            val restored = ReaderScrollRestorationSaver.restore(saved)!!
            assertNull(restored.saved, "restored from $saved")
            restored.documentReplaced("a")
            assertNull(restored.documentFinished(0L, reapplyOnGrowth = true), "restored from $saved")
        }
    }

    @Test
    fun theDocumentKeyTellsDocumentsApart() {
        assertEquals(readerDocumentKey("<p>a</p>"), readerDocumentKey("<p>a</p>"))
        assertNotEquals(readerDocumentKey("<p>a</p>"), readerDocumentKey("<p>b</p>"))
    }
}

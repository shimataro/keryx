package works.merc.keryx.app.presentation.article

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderDocumentLoadTest {
    @Test
    fun nothingIsRetriedBeforeAnyLoad() {
        assertFalse(ReaderDocumentLoad().documentMayBeLost())
    }

    @Test
    fun aLoadInFlightIsRetriedWhenItMayBeLost() {
        val load = ReaderDocumentLoad()
        load.documentRequested()
        assertTrue(load.documentMayBeLost())
    }

    @Test
    fun aConfirmedDocumentIsNotRetried() {
        val load = ReaderDocumentLoad()
        load.documentRequested()
        load.documentConfirmed()
        assertFalse(load.isPending)
        assertFalse(load.documentMayBeLost())
    }

    @Test
    fun retriesStopAtTheLimitAndTheReaderStopsWaiting() {
        val load = ReaderDocumentLoad()
        load.documentRequested()
        repeat(READER_DOCUMENT_RELOAD_LIMIT) {
            assertTrue(load.documentMayBeLost())
            load.retryStarted()
        }
        assertFalse(load.documentMayBeLost())
        assertFalse(load.isPending)
    }

    @Test
    fun aRetryKeepsTheBudgetSpentSoFar() {
        val load = ReaderDocumentLoad()
        load.documentRequested()
        repeat(READER_DOCUMENT_RELOAD_LIMIT - 1) {
            load.documentMayBeLost()
            load.retryStarted()
        }
        assertTrue(load.documentMayBeLost())
        load.retryStarted()
        assertFalse(load.documentMayBeLost())
    }

    @Test
    fun aNewDocumentGetsTheWholeBudgetAgain() {
        val load = ReaderDocumentLoad()
        load.documentRequested()
        repeat(READER_DOCUMENT_RELOAD_LIMIT) {
            load.documentMayBeLost()
            load.retryStarted()
        }
        load.documentRequested()
        repeat(READER_DOCUMENT_RELOAD_LIMIT) { assertTrue(load.documentMayBeLost()) }
    }
}

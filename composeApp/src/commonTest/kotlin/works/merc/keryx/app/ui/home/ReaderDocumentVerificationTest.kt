package works.merc.keryx.app.ui.home

import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.presentation.article.READER_DOCUMENT_RELOAD_LIMIT
import works.merc.keryx.app.presentation.article.ReaderDocumentLoad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `verifyReaderDocument` — the loop that reads the pushed document's marker back from the page and
 * pushes it again while it is not on screen — driven with a fake page in virtual time.
 */
class ReaderDocumentVerificationTest {
    private val marker = readerDocumentMarker(2)

    /** A fresh document load, as `ArticleWebView` starts one before verifying it. */
    private fun pendingLoad() = ReaderDocumentLoad().apply { documentRequested() }

    @Test
    fun aDocumentThatNeverShowsIsPushedAgainUpToTheLimitThenGivenUpOn() = runTest {
        val load = pendingLoad()
        var reloads = 0

        verifyReaderDocument(
            marker = marker,
            documentLoad = load,
            // The previous push's document is still on screen.
            probe = { onResult -> onResult("\"${readerDocumentMarker(1)}\"") },
            reload = { reloads++ },
            isCurrent = { true },
        )

        assertEquals(READER_DOCUMENT_RELOAD_LIMIT, reloads)
        assertFalse(load.isPending)
    }

    /**
     * A probe the web view never answers counts as the document not being shown: a page whose script
     * keeps navigating may never answer, and that is the case the reloads exist to recover from.
     */
    @Test
    fun anUnansweredProbeIsPushedAgainUpToTheLimitThenGivenUpOn() = runTest {
        val load = pendingLoad()
        var reloads = 0

        verifyReaderDocument(
            marker = marker,
            documentLoad = load,
            probe = { _ -> },
            reload = { reloads++ },
            isCurrent = { true },
        )

        assertEquals(READER_DOCUMENT_RELOAD_LIMIT, reloads)
        assertFalse(load.isPending)
    }

    @Test
    fun aDocumentOnScreenIsConfirmedWithoutBeingPushedAgain() = runTest {
        val load = pendingLoad()
        var reloads = 0

        verifyReaderDocument(
            marker = marker,
            documentLoad = load,
            probe = { onResult -> onResult("\"$marker\"") },
            reload = { reloads++ },
            isCurrent = { true },
        )

        assertEquals(0, reloads)
        assertFalse(load.isPending)
    }

    @Test
    fun aDocumentThatShowsAfterOnePushAgainStopsThere() = runTest {
        val load = pendingLoad()
        var reloads = 0

        verifyReaderDocument(
            marker = marker,
            documentLoad = load,
            // Nothing on screen until the document has been pushed again once.
            probe = { onResult -> onResult(if (reloads == 0) "\"\"" else "\"$marker\"") },
            reload = { reloads++ },
            isCurrent = { true },
        )

        assertEquals(1, reloads)
        assertFalse(load.isPending)
    }

    @Test
    fun aNewerDocumentTakingOverDuringAProbeEndsTheCheckWithoutAPush() = runTest {
        val load = pendingLoad()
        var current = true
        var reloads = 0

        verifyReaderDocument(
            marker = marker,
            documentLoad = load,
            // The page answers that the document is missing, but a newer one was pushed meanwhile.
            probe = { onResult ->
                current = false
                onResult("\"\"")
            },
            reload = { reloads++ },
            isCurrent = { current },
        )

        assertEquals(0, reloads)
        // The newer document's own check owns the load state now; this one leaves it alone.
        assertTrue(load.isPending)
    }
}

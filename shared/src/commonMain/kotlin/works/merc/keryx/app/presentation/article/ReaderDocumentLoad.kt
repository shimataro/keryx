package works.merc.keryx.app.presentation.article

/** How many times the reader loads the same document again after its load was lost. */
const val READER_DOCUMENT_RELOAD_LIMIT: Int = 5

/**
 * Decides when the article reader loads its document again because the load it started was lost —
 * the one rule the Compose reader (desktop / Android) and the SwiftUI reader (iOS / macOS) both
 * follow.
 *
 * A web view drops a document load still in flight when another top-level navigation starts in it:
 * the article on screen can run script (see "A feed body's own JavaScript runs inside the article
 * reader" in `docs/known-issues.md`), and a navigation it starts while the next document is loading
 * replaces that load — often without any failure being reported. The reader then believed the new
 * document was shown and never loaded it again, so the previous article stayed on screen while the
 * list showed the new selection.
 *
 * The reader reports the document it starts loading ([documentRequested], or [retryStarted] for the
 * same one again), the moment it is confirmed on screen ([documentConfirmed]), and every sign that a
 * load still in flight may have been lost ([documentMayBeLost]), which answers whether to load the
 * document again. Only a load still in flight is retried — once confirmed, the document's own
 * navigations are not this rule's business — and at most [READER_DOCUMENT_RELOAD_LIMIT] times per
 * document, so a page that keeps navigating cannot make the reader loop.
 *
 * Not thread-safe: use one instance from the main thread only.
 */
class ReaderDocumentLoad {
    /** Whether a load of the reader's own document was started and is not confirmed yet. */
    var isPending: Boolean = false
        private set

    private var reloads = 0

    /** A new document starts loading: it gets the whole reload budget. */
    fun documentRequested() {
        reloads = 0
        isPending = true
    }

    /** The current document starts loading again (a retry or a recovery): it keeps its budget. */
    fun retryStarted() {
        isPending = true
    }

    /** The reader's own document is on screen. */
    fun documentConfirmed() {
        isPending = false
    }

    /**
     * Something suggests the load still in flight was lost (another top-level navigation was refused
     * or replaced it, or it failed). Returns whether to load the document again, spending one reload;
     * false when nothing is in flight or the document has used up its reloads — the reader then
     * stops waiting for it.
     */
    fun documentMayBeLost(): Boolean {
        if (!isPending) return false
        if (reloads >= READER_DOCUMENT_RELOAD_LIMIT) {
            isPending = false
            return false
        }
        reloads++
        return true
    }
}

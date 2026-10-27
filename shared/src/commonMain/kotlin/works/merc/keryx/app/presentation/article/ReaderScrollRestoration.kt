package works.merc.keryx.app.presentation.article

/** How long after a restored document finishes loading the restore keeps re-applying while it grows. */
const val READER_SCROLL_SETTLE_WINDOW_MS: Long = 3_000L

/**
 * A reader document's scroll position as the reader saves it to restore later: which document
 * ([documentKey], an identifier the platform derives from the document, such as a hash of its HTML)
 * and how far down it was, as a fraction of its scrollable height (0 at the top, 1 at the bottom).
 */
data class SavedReaderScroll(val documentKey: String, val fraction: Double)

/**
 * [fraction] clamped to 0..1, or null where there is nothing to restore (the top, or a non-finite
 * value) — the one rule for which positions are worth restoring.
 */
fun restorableScrollFraction(fraction: Double): Double? {
    if (!fraction.isFinite()) return null
    return fraction.coerceIn(0.0, 1.0).takeIf { it > 0.0 }
}

/**
 * Decides where the article reader scrolls a document back to after loading it again — the one rule
 * the Compose reader (Android) and the SwiftUI reader (iOS / macOS) both follow.
 *
 * The web view reports its vertical scroll position as a fraction of its scrollable height, and this
 * keeps the last one for the current document. A document is scrolled back to a position in two
 * cases: when it is loaded again after the web view's content process died ([reloadStarted]), and
 * when the app was terminated in the background and the reader restored — [restored] is then the
 * position saved for a document, offered to that document the first time it loads (a different
 * document loading first, such as the placeholder shown while the body is still loading, leaves the
 * offer in place). Either way the position is applied once the load finishes, then again each time
 * the content grows (images arriving) during [READER_SCROLL_SETTLE_WINDOW_MS] — until the user
 * touches, scrolls or presses a key, which ends the restore, and withdraws an unused offer, at once.
 * While a restore is running, the scroll the reader itself causes is not recorded.
 *
 * A screen reader's own scrolling inside the page cannot be told apart from the reader's, so with one
 * running the position is applied only once, when the load finishes ([documentFinished]'s
 * `reapplyOnGrowth`), never pulling the page back from where the screen reader moved it.
 *
 * Times are monotonic milliseconds from any fixed origin, compared only with each other. Not
 * thread-safe: use one instance from the main thread only.
 */
class ReaderScrollRestoration(restored: SavedReaderScroll? = null) {
    private sealed interface Phase {
        /** Recording the user's scroll position. */
        data object Idle : Phase

        /** A load is in flight that should end at [target]. */
        data class Pending(val target: Double) : Phase

        /** The load finished: re-apply [target] on growth until [deadlineMillis]. */
        data class Applying(val target: Double, val deadlineMillis: Long) : Phase
    }

    private var phase: Phase = Phase.Idle
    private var offer: SavedReaderScroll? = restored?.takeIf { restorableScrollFraction(it.fraction) != null }
    private var documentKey: String? = null

    /** The last recorded position of the current document, if it was scrolled away from the top. */
    var lastFraction: Double? = null
        private set

    /** What to save for a later restore: the current document's position, else the unused offer. */
    val saved: SavedReaderScroll?
        get() {
            val key = documentKey
            val fraction = lastFraction
            return if (key != null && fraction != null) SavedReaderScroll(key, fraction) else offer
        }

    /**
     * The reader is about to load the document keyed [key] because its content changed. Where the
     * offer is for that document, [documentFinished] then scrolls it there; otherwise it starts at the
     * top. The current document loaded again (into a web view rebuilt after its content process died)
     * is no replacement, and leaves a pending [reloadStarted] in place.
     */
    fun documentReplaced(key: String) {
        if (key == documentKey) return
        documentKey = key
        val target = offer?.takeIf { it.documentKey == key }?.let { restorableScrollFraction(it.fraction) }
        if (target != null) offer = null
        lastFraction = target
        phase = target?.let { Phase.Pending(it) } ?: Phase.Idle
    }

    /** The reader is about to load the current document again: scroll it back to the last position. */
    fun reloadStarted() {
        phase = lastFraction?.let { Phase.Pending(it) } ?: Phase.Idle
    }

    /**
     * The web view reports it is now scrolled to [fraction]. Returns the position recorded (clamped to
     * 0..1), or null when it was not recorded because a restore is running (or [fraction] is not
     * finite). Also ends a restore whose settle window has passed by [nowMillis].
     */
    fun scrolled(fraction: Double, nowMillis: Long): Double? {
        expireIfDue(nowMillis)
        if (phase != Phase.Idle || !fraction.isFinite()) return null
        lastFraction = restorableScrollFraction(fraction)
        return lastFraction ?: 0.0
    }

    /**
     * The document finished loading at [nowMillis]: the fraction to scroll it to now, if any. With
     * [reapplyOnGrowth] false (a screen reader is running) the restore ends here; otherwise it keeps
     * settling ([contentResized]) for [READER_SCROLL_SETTLE_WINDOW_MS].
     */
    fun documentFinished(nowMillis: Long, reapplyOnGrowth: Boolean): Double? {
        val pending = phase as? Phase.Pending ?: return null
        phase = if (reapplyOnGrowth) {
            Phase.Applying(pending.target, nowMillis + READER_SCROLL_SETTLE_WINDOW_MS)
        } else {
            Phase.Idle
        }
        return pending.target
    }

    /**
     * The document's content changed size: the fraction to scroll it to again, if a restore is still
     * settling. Also ends a restore whose settle window has passed by [nowMillis].
     */
    fun contentResized(nowMillis: Long): Double? {
        expireIfDue(nowMillis)
        return (phase as? Phase.Applying)?.target
    }

    /**
     * Whether a restore is still waiting for its load or settling at [nowMillis]. Not a pure query:
     * it also ends a restore whose settle window has passed by then.
     */
    fun isRestoring(nowMillis: Long): Boolean {
        expireIfDue(nowMillis)
        return phase != Phase.Idle
    }

    /** The user touched, scrolled or pressed a key in the page: any restore, and the offer, end here. */
    fun userInteracted() {
        phase = Phase.Idle
        offer = null
    }

    private fun expireIfDue(nowMillis: Long) {
        val applying = phase as? Phase.Applying ?: return
        if (nowMillis >= applying.deadlineMillis) phase = Phase.Idle
    }
}

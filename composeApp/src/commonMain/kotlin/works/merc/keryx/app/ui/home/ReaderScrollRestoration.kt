package works.merc.keryx.app.ui.home

import androidx.compose.runtime.saveable.Saver
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long after a restored document finishes loading the restore keeps re-applying while it grows. */
internal val READER_SCROLL_SETTLE_WINDOW: Duration = 3.seconds

/**
 * A reader document's scroll position as [ArticleWebView] saves it across process death: which
 * document ([documentKey], the hash of its HTML) and how far down it was, as a fraction of its
 * scrollable height (0 at the top, 1 at the bottom).
 */
internal data class SavedReaderScroll(val documentKey: Int, val fraction: Float)

/**
 * Decides where the reader scrolls a document back to after loading it again — the Compose
 * counterpart of the SwiftUI reader's own `ReaderScrollRestoration`, following the same rule.
 *
 * The WebView reports its vertical scroll position as a fraction of its scrollable height, and this
 * keeps the last one for the current document. A document is scrolled back to a position in two
 * cases: when it is loaded again after the WebView's renderer process died ([reloadStarted]), and
 * when the Android process itself was killed in the background and the reader's state restored —
 * [restored] is then the position saved for that document, offered to it the first time it loads in
 * the restored composition (a different document loading first, such as the placeholder shown while
 * the body is still loading, leaves the offer in place). Either way the position is applied once the
 * load finishes, then again each time the content grows (images arriving) during
 * [READER_SCROLL_SETTLE_WINDOW] — until the user touches, scrolls or presses a key, which ends the
 * restore at once. While a restore is running, the scroll the reader itself causes is not recorded.
 */
internal class ReaderScrollRestoration(restored: SavedReaderScroll? = null) {
    private sealed interface Phase {
        /** Recording the user's scroll position. */
        data object Idle : Phase

        /** A load is in flight that should end at [target]. */
        data class Pending(val target: Float) : Phase

        /** The load finished: re-apply [target] on growth until [deadline]. */
        data class Applying(val target: Float, val deadline: ComparableTimeMark) : Phase
    }

    private var phase: Phase = Phase.Idle
    private var offer: SavedReaderScroll? = restored?.takeIf { restorable(it.fraction) != null }
    private var documentKey: Int? = null

    /** The last recorded position of the current document, if it was scrolled away from the top. */
    var lastFraction: Float? = null
        private set

    /** What to save across process death: the current document's position, else the unused offer. */
    val saved: SavedReaderScroll?
        get() {
            val key = documentKey
            val fraction = lastFraction
            return if (key != null && fraction != null) SavedReaderScroll(key, fraction) else offer
        }

    /**
     * The reader is about to load the document keyed [key] because its content changed. Where the
     * restored offer is for that document, [documentFinished] then scrolls it there; otherwise it
     * starts at the top.
     */
    fun documentReplaced(key: Int) {
        documentKey = key
        val target = offer?.takeIf { it.documentKey == key }?.let { restorable(it.fraction) }
        if (target != null) offer = null
        lastFraction = target
        phase = target?.let { Phase.Pending(it) } ?: Phase.Idle
    }

    /** The reader is about to load the current document again: scroll it back to the last position. */
    fun reloadStarted() {
        phase = lastFraction?.let { Phase.Pending(it) } ?: Phase.Idle
    }

    /**
     * The WebView reports it is now scrolled to [fraction]. Returns the position recorded (clamped to
     * 0..1), or null when it was not recorded because a restore is running.
     */
    fun scrolled(fraction: Float, now: ComparableTimeMark): Float? {
        expireIfDue(now)
        if (phase != Phase.Idle || fraction.isNaN()) return null
        val recorded = fraction.coerceIn(0f, 1f)
        lastFraction = restorable(recorded)
        return recorded
    }

    /** The document finished loading: the fraction to scroll it to now, if any. */
    fun documentFinished(now: ComparableTimeMark): Float? {
        val pending = phase as? Phase.Pending ?: return null
        phase = Phase.Applying(pending.target, now + READER_SCROLL_SETTLE_WINDOW)
        return pending.target
    }

    /** The document's content changed size: the fraction to scroll it to again, if still settling. */
    fun contentResized(now: ComparableTimeMark): Float? {
        expireIfDue(now)
        return (phase as? Phase.Applying)?.target
    }

    /** Whether a restore is still waiting for its load or settling at [now]. */
    fun isRestoring(now: ComparableTimeMark): Boolean {
        expireIfDue(now)
        return phase != Phase.Idle
    }

    /** The user touched, scrolled or pressed a key in the page: any restore, and the offer, end here. */
    fun userInteracted() {
        phase = Phase.Idle
        offer = null
    }

    private fun expireIfDue(now: ComparableTimeMark) {
        val applying = phase as? Phase.Applying ?: return
        if (now >= applying.deadline) phase = Phase.Idle
    }

    /** [fraction] clamped to 0..1, or null where there is nothing to restore (the top, or invalid). */
    private fun restorable(fraction: Float): Float? {
        if (fraction.isNaN()) return null
        val clamped = fraction.coerceIn(0f, 1f)
        return clamped.takeIf { it > 0f }
    }
}

/**
 * Saves a [ReaderScrollRestoration] as its [ReaderScrollRestoration.saved] position (nothing at all
 * when it has none) and restores it as a fresh restoration offering that position.
 */
internal val ReaderScrollRestorationSaver: Saver<ReaderScrollRestoration, Any> = Saver(
    save = { restoration -> restoration.saved?.let { listOf(it.documentKey, it.fraction) } },
    restore = { saved ->
        val values = saved as List<*>
        ReaderScrollRestoration(SavedReaderScroll(values[0] as Int, values[1] as Float))
    },
)

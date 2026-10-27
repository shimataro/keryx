package works.merc.keryx.app.ui.home

import androidx.compose.runtime.saveable.Saver
import works.merc.keryx.app.presentation.article.ReaderScrollRestoration
import works.merc.keryx.app.presentation.article.SavedReaderScroll
import kotlin.time.TimeSource

/**
 * Saves a [ReaderScrollRestoration] as its [ReaderScrollRestoration.saved] position (nothing at all
 * when it has none) and restores it as a fresh restoration offering that position. A saved value of
 * any other shape — one written by an older build, which keyed documents by an `Int` hash and kept a
 * `Float` — restores a restoration that offers nothing.
 */
internal val ReaderScrollRestorationSaver: Saver<ReaderScrollRestoration, Any> = Saver(
    save = { restoration -> restoration.saved?.let { listOf(it.documentKey, it.fraction) } },
    restore = { saved ->
        val values = saved as? List<*>
        val key = values?.getOrNull(0) as? String
        val fraction = values?.getOrNull(1) as? Double
        ReaderScrollRestoration(if (key != null && fraction != null) SavedReaderScroll(key, fraction) else null)
    },
)

/** The key [ReaderScrollRestoration] tells the reader's documents apart by: a hash of [html]. */
internal fun readerDocumentKey(html: String): String = html.hashCode().toString()

private val readerClockOrigin = TimeSource.Monotonic.markNow()

/** The monotonic milliseconds [ReaderScrollRestoration] measures its settle window in. */
internal fun readerScrollNowMillis(): Long = readerClockOrigin.elapsedNow().inWholeMilliseconds

package works.merc.keryx.app.ui.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.ARTICLE_CONTENT_CACHE_LIMIT
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.toReaderRow

/**
 * Drops the oldest entries until [rows] is back within [limit], keeping insertion order.
 *
 * Insertion order alone is enough and a true LRU's move-to-end is deliberately left out:
 * re-ordering on every hit would emit a new map — recomposing every reader page — each time a page
 * composes, to protect a working set (three pages) that already sits far below the cap.
 */
internal fun trimmedToCacheLimit(
    rows: Map<String, ArticleReaderRow>,
    limit: Int,
): Map<String, ArticleReaderRow> =
    if (rows.size <= limit) rows
    else rows.entries.drop(rows.size - limit).associate { it.key to it.value }

/**
 * The article bodies the reader's pager is holding ready, keyed by article id.
 *
 * The pager composes the pages either side of the one on screen
 * (`beyondViewportPageCount = 1`), and those need a body the list row does not carry. Split out of
 * `HomeViewModel` because it is a self-contained piece of state with its own lifecycle, and
 * because it can then be tested without standing up the whole ViewModel.
 *
 * **Loading a body is not selecting it.** [load] must be a plain read; `HomeViewModel.selectArticle`
 * remains the only path that marks an article read, so a page the user has not swiped to stays
 * unread.
 *
 * @param scope The scope lookups run on. Every mutation of this class's state happens on it, which
 *   is what lets [inFlight] and [rows] be read and written without further synchronization.
 * @param dispatcher Where the (blocking) DB read itself runs — never [scope]'s own dispatcher.
 * @param limit How many bodies to keep; see [trimmedToCacheLimit].
 * @param load Reads one article by id, or returns `null` when it no longer exists.
 */
internal class ArticleContentCache(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val limit: Int = ARTICLE_CONTENT_CACHE_LIMIT,
    private val load: suspend (String) -> Articles?,
) {
    private val _rows = MutableStateFlow<Map<String, ArticleReaderRow>>(emptyMap())
    val rows: StateFlow<Map<String, ArticleReaderRow>> = _rows.asStateFlow()

    /**
     * Ids whose lookup is running, so a recomposition cannot queue a second one.
     *
     * A `MutableStateFlow` rather than a bare `MutableSet` so the check-and-add is a single atomic
     * update: this class's own mutations are all on [scope], but `requestArticleContent` is public
     * on the ViewModel and a future caller on another dispatcher would otherwise be able to slip
     * between the check and the add — losing a lookup, or worse, leaving an id in the set forever
     * and permanently refusing that article.
     */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Loads [id]'s body, unless it is already held or already loading.
     *
     * @param id The article to hydrate.
     */
    fun request(id: String) {
        if (id in _rows.value) return
        var started = false
        inFlight.update { current ->
            if (id in current) current else { started = true; current + id }
        }
        if (!started) return
        scope.launch {
            val full = try {
                withContext(dispatcher) { load(id) }
            } finally {
                inFlight.update { it - id }
            }
            // A sync merge can tombstone the row between the page composing and this returning;
            // rendering it would put deleted content back on screen, the same thing
            // HomeViewModel.selectArticle's own guard prevents for the selection.
            if (full == null || full.deleted_at != null) return@launch
            _rows.update { trimmedToCacheLimit(it + (id to full.toReaderRow()), limit) }
        }
    }

    /**
     * Forgets every held body.
     *
     * Called when the reader leaves the composition: nothing is holding these pages open any more,
     * and without this the bodies of every article paged past would stay resident for the
     * ViewModel's whole life.
     */
    fun clear() {
        _rows.value = emptyMap()
    }
}

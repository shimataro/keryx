package works.merc.keryx.app.ui.home

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.toReaderRow

/**
 * Everything the article reader needs to render as a pager at a narrow layout.
 *
 * `null` is how a caller says "no pager here" — the same null-means-`PaneLayout.Triple` idiom
 * [ArticleSwipeNavigation] already uses, and the two are always supplied together (see
 * `ArticleDetailPane`, which builds this only where a swipe exists at all). They are nevertheless
 * separate parameters rather than one: [ArticleSwipeNavigation] is a deliberately stable,
 * `remember`ed bundle of callbacks, while this one carries data that changes as articles load, and
 * folding the two together would invalidate the callbacks on every load.
 *
 * @property pages The article rows to page through, in the order the list shows them.
 * @property contents Bodies held for those pages, keyed by article id.
 * @property requestContent Asks for a page's body to be loaded
 *   (`HomeViewModel.requestArticleContent`). Loading a page does not select it or mark it read.
 * @property onPageSettled Called with the row a *swipe* has come to rest on
 *   (`HomeViewModel.selectArticle`) — the moment a swiped-to article counts as opened, and
 *   therefore the moment it is marked read. See [settledPageSelects] for why not every settle
 *   qualifies.
 */
internal data class ArticleReaderPaging(
    val pages: List<ArticleListRow>,
    val contents: Map<String, ArticleReaderRow>,
    val requestContent: (String) -> Unit,
    val onPageSettled: (ArticleListRow) -> Unit,
)

/**
 * The bodies the reader renders, with the selected article's own fully-loaded row laid over the
 * held copies.
 *
 * The selected article is in [cached] too (the cache does not skip it), which is what keeps its
 * page rendered after a swipe moves the selection to a neighbour — without that, the page the user
 * just swiped away from would blank out and reload, losing exactly the reading position the pager
 * exists to preserve. [selected] still wins where both have it, so the page on screen shows the
 * authoritative row rather than whatever the cache read earlier.
 *
 * @param cached `HomeViewModel.articleContents`.
 * @param selected The currently selected article, or `null` when nothing is selected.
 */
internal fun readerContents(
    cached: Map<String, ArticleReaderRow>,
    selected: Articles?,
): Map<String, ArticleReaderRow> =
    if (selected == null) cached else cached + (selected.id to selected.toReaderRow())

/**
 * The pages the pager actually shows.
 *
 * Normally just [pages]. The exception is the frame — or the few frames — where the selection is
 * not in the list yet or no longer in it: the article list flow is `WhileSubscribed`, so it starts
 * empty, and a tombstone can drop the selected row. Falling back to a one-page list built from the
 * selection keeps the reader on *the same composition slot* throughout: swapping between a pager
 * and a plain reader, or between an empty pager and a full one, would tear down and rebuild the
 * page's `WebView` and visibly reload the article. Once the real list arrives, the pager's `key`
 * (the article id) keeps that page's WebView alive across the swap.
 *
 * @param pages The list rows to page through.
 * @param selected The currently selected article, or `null` when nothing is selected.
 */
internal fun readerPages(pages: List<ArticleListRow>, selected: Articles?): List<ArticleListRow> {
    if (selected == null) return pages
    return if (pages.any { it.id == selected.id }) pages else listOf(selected.toListRow())
}

/**
 * Whether the page the pager has settled on should now become the selected article.
 *
 * [isSwipeTarget] is what confines this to a page turn the user actually asked for. `settledPage`
 * moves on its own in cases that have nothing to do with a swipe — most importantly `PagerState`
 * clamps it when the backing list shrinks below the current page, which happens whenever a search
 * narrows the list, a sync merge tombstones rows, or "unread only" hides the article just read.
 * Without this gate that clamp would select — and therefore **mark read** — whatever article
 * happened to land at the clamped index, an article the user has never seen.
 *
 * Compared by article id rather than page index for the same reason: the same index can mean a
 * different article from one emission to the next.
 *
 * A `null` [selectedId] deliberately selects nothing. A narrow tablet layout keeps the reader on
 * screen beside the list with nothing selected yet, and a pager resting on page 0 in that state
 * would otherwise open the first article without the user ever touching it.
 *
 * @param settledId The id of the page the pager has come to rest on, or `null` when there is none.
 * @param selectedId The currently selected article's id, or `null` when nothing is selected.
 * @param isSwipeTarget Whether that page is the one a committed swipe was turning to
 *   ([ArticleSwipeController.pendingSelectionPage]).
 */
internal fun settledPageSelects(settledId: String?, selectedId: String?, isSwipeTarget: Boolean): Boolean =
    isSwipeTarget && selectedId != null && settledId != null && settledId != selectedId

/**
 * Which page the pager should be moved to so that it shows the selected article, or `null` to
 * leave it where it is.
 *
 * This is the other direction of the same synchronization: it follows a selection made somewhere
 * else (the article list, J/K, the reader's own accessibility actions) and, because it resolves the
 * index afresh from [pages] every time they change, it is also what re-anchors the pager when the
 * backing list shifts underneath it.
 *
 * @param pages The rows the pager is showing ([ArticleReaderPaging.pages]).
 * @param selectedId The currently selected article's id, or `null` when nothing is selected.
 * @param currentPage The pager's current page index.
 * @param gestureInProgress Whether a swipe owns the pager right now
 *   ([ArticleSwipeController.gestureInProgress]) — a drag in flight is the user's own and must
 *   never be yanked elsewhere mid-gesture. Deliberately *not* `PagerState.isScrollInProgress`,
 *   which also goes false in the gap between the finger lifting and the settle animation starting.
 * @return The index to scroll to, or `null` when the pager already shows the selection, the
 *   selection is not in [pages] at all (a tombstoned row; `HomeViewModel` resolves that itself),
 *   or a gesture is in flight.
 */
internal fun pageIndexToRestore(
    pages: List<ArticleListRow>,
    selectedId: String?,
    currentPage: Int,
    gestureInProgress: Boolean,
): Int? {
    if (gestureInProgress || selectedId == null) return null
    val index = pages.indexOfFirst { it.id == selectedId }
    return index.takeIf { it >= 0 && it != currentPage }
}

/**
 * Keeps [pagerState] and the selected article in step, in both directions.
 *
 * Lives here, beside the two pure functions it drives, rather than inline in
 * `ArticleDetailPaneContent` — the effect wiring is only meaningful together with the decisions it
 * defers to.
 *
 * @param pages The rows the pager is showing.
 * @param selectedId The currently selected article's id.
 * @param controller The swipe controller, for whether a gesture owns the pager and which page a
 *   committed swipe is turning to.
 * @param onPageSettled `HomeViewModel.selectArticle`, or `null` where there is no pager.
 */
@Composable
internal fun ArticleReaderPagerSync(
    pagerState: PagerState,
    pages: List<ArticleListRow>,
    selectedId: String?,
    controller: ArticleSwipeController,
    onPageSettled: ((ArticleListRow) -> Unit)?,
) {
    // Keyed on the pager alone, with everything it reads held in rememberUpdatedState: `pages` is a
    // fresh list every time an article is written, and keying on it would tear this collector down
    // and rebuild it each time. Inert where there is no pager — an empty page list resolves no row.
    val currentPages by rememberUpdatedState(pages)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val settled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState, controller) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val isSwipeTarget = controller.consumePendingSelection(page)
            val row = currentPages.getOrNull(page) ?: return@collect
            if (settledPageSelects(row.id, currentSelectedId, isSwipeTarget)) settled?.invoke(row)
        }
    }
    // The other direction: follow a selection made elsewhere (the article list, J/K, the reader's
    // own accessibility actions) and re-anchor when the backing list shifts underneath.
    LaunchedEffect(pagerState, pages, selectedId, controller.gestureInProgress) {
        val target = pageIndexToRestore(pages, selectedId, pagerState.currentPage, controller.gestureInProgress)
        if (target != null) pagerState.scrollToPage(target)
    }
}

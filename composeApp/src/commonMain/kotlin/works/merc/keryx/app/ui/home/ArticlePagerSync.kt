package works.merc.keryx.app.ui.home

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.presentation.home.pageIndexToRestore
import works.merc.keryx.app.presentation.home.settledPageSelects

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

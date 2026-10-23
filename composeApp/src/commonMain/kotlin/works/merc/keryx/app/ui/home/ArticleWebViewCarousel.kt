package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import io.github.kdroidfilter.webview.web.WebView
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.presentation.article.ArticleHtmlTheme

/**
 * How many physical [ArticleWebView] instances the reader keeps alive at a narrow layout — one
 * for the settled page and one for each neighbour [HorizontalPager]'s `beyondViewportPageCount = 1`
 * keeps composed. See [ArticleWebViewCarousel] for why this must match that value.
 */
internal const val ARTICLE_READER_SLOT_COUNT = 3

/**
 * The always-alive `WebView`s the narrow-layout reader actually shows, positioned by hand to track
 * [pagerState]'s own scroll rather than living inside its lazily-composed page slots.
 *
 * The [HorizontalPager] above this (see its call site's own comment) is invisible on purpose: a
 * `LazyLayout` disposes a page's composition — and therefore any `AndroidView`-hosted native
 * `WebView` inside it — the moment that page scrolls past `beyondViewportPageCount`, and recreates
 * it from scratch when the page re-enters range. On Android that recreation briefly shows nothing
 * (the article the user is mid-swipe on going blank for a couple of frames — confirmed on-device
 * with frame-by-frame capture), because it tears down and rebuilds the real `android.webkit.WebView`
 * behind it, not just its Compose content. Forward and backward swipes are asymmetric here for
 * reasons rooted in the pager's own internals, not this app's code, which is why the flicker was
 * only ever seen swiping towards the next article. See "Article Reader" in `docs/app-architecture.md`
 * for the full investigation.
 *
 * This composable sidesteps the whole mechanism: [ARTICLE_READER_SLOT_COUNT] physical slots are
 * composed unconditionally, at fixed call sites, for the life of this composable — the same
 * "compose it once, never behind an `if`" idiom [ArticleDetailPaneContent] already uses for the
 * `PaneLayout.Triple` reader — so their `AndroidView`-hosted `WebView`s are never disposed by an
 * ordinary page turn (see [ArticleWebViewSlot] for the one slot an edge of the list does release).
 * [slotIndex] assigns each slot the page index that shares its residue modulo
 * [ARTICLE_READER_SLOT_COUNT]; since any three consecutive page indices always occupy three
 * distinct residues, the settled page and both its neighbours are guaranteed distinct slots, and
 * stepping to an adjacent page changes at most one slot's assignment — the two pages already
 * resident (including the one just left) keep the exact same `WebView`, just repositioned via
 * [ArticleWebViewSlot]'s own `Modifier.offset`. Reading position across a swipe survives for
 * exactly the same reason it did before ([HorizontalPager]'s own `beyondViewportPageCount = 1`
 * KDoc): a page more than one away from the settled one is no longer any slot's assignment, so its
 * `WebView` gets reused for whatever page *is* newly in range — reloading fresh content — the same
 * "two articles away and back loses position" limit `docs/testing.md` already documents.
 */
@Composable
internal fun ArticleWebViewCarousel(
    pagerState: PagerState,
    pages: List<ArticleListRow>,
    contents: Map<String, ArticleReaderRow>,
    selectedId: String?,
    bylineFeeds: Map<String, Feeds>,
    requestContent: (String) -> Unit,
    widthPx: () -> Float,
    theme: ArticleHtmlTheme,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit,
) {
    for (slot in 0 until ARTICLE_READER_SLOT_COUNT) {
        key(slot) {
            ArticleWebViewSlot(
                slot = slot,
                pagerState = pagerState,
                pages = pages,
                contents = contents,
                selectedId = selectedId,
                bylineFeeds = bylineFeeds,
                requestContent = requestContent,
                widthPx = widthPx,
                theme = theme,
                noTitleText = noTitleText,
                noContentText = noContentText,
                openInBrowserTooltip = openInBrowserTooltip,
                reader = reader,
            )
        }
    }
}

/**
 * The page index [slot] should show right now, or `null` when no in-range page has that residue
 * (an edge of the list — there is no page before the first one, or after the last).
 *
 * Only the settled page and its immediate neighbours (the same range
 * `HorizontalPager`'s `beyondViewportPageCount = 1` used to compose) are ever candidates, so this
 * never has to scan the whole list.
 */
internal fun slotIndex(slot: Int, currentPage: Int, pageCount: Int): Int? {
    for (candidate in (currentPage - 1)..(currentPage + 1)) {
        if (candidate !in 0 until pageCount) continue
        val residue = ((candidate % ARTICLE_READER_SLOT_COUNT) + ARTICLE_READER_SLOT_COUNT) % ARTICLE_READER_SLOT_COUNT
        if (residue == slot) return candidate
    }
    return null
}

/**
 * One physical slot of [ArticleWebViewCarousel]: composed unconditionally so its [reader] (and the
 * native `WebView` inside it) survives every ordinary page turn, tracking [pagerState]'s scroll
 * through a lambda-based [Modifier.offset] — read at layout time, not recomposition, the same way
 * the gesture's own rubber band already is (see [ArticleSwipeController.offset]'s call site).
 *
 * A slot with no assignment emits nothing at all, which disposes whatever it last held: Compose
 * removes the child composition — and with it the native `WebView` — when a composable returns
 * before emitting. That only happens at an edge of the list, where fewer than three pages are
 * within reach, and only ever to a slot holding a page two or more away from the settled one; the
 * settled page and both its neighbours always have an assignment, so nothing on screen (or one
 * swipe from it) is ever torn down here. Such a page is already outside the reading position this
 * carousel preserves, so the cost is one `WebView` rebuilt the next time the list edge is left,
 * not a lost reading position.
 */
@Composable
internal fun ArticleWebViewSlot(
    slot: Int,
    pagerState: PagerState,
    pages: List<ArticleListRow>,
    contents: Map<String, ArticleReaderRow>,
    selectedId: String?,
    bylineFeeds: Map<String, Feeds>,
    requestContent: (String) -> Unit,
    widthPx: () -> Float,
    theme: ArticleHtmlTheme,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit,
) {
    val index = slotIndex(slot, pagerState.currentPage, pages.size) ?: return
    val row = pages.getOrNull(index) ?: return
    LaunchedEffect(row.id) { requestContent(row.id) }
    val full = contents[row.id]
    // Resolved from this page's own row, so a neighbour in a mixed list (All Feeds, a folder, a tag)
    // names its own feed. Keyed by value: a feeds emission that leaves this page's name and favicon
    // alone does not rebuild — and so reload — its document.
    val bylineFeed = bylineFeeds[row.feed_id]
    val bylineFeedName = bylineFeed?.displayTitle()
    val bylineFaviconUrl = bylineFeed?.favicon_url
    val document = remember(theme, row, full, bylineFeedName, bylineFaviconUrl, noTitleText, noContentText, openInBrowserTooltip) {
        readerDocument(theme, row, full, bylineFeedName, bylineFaviconUrl, noTitleText, noContentText, openInBrowserTooltip)
    }
    // A page the user has not swiped to is held ready, not shown. It must not be reachable by a
    // screen reader's linear traversal (its WebView is a real view, clipped rather than
    // semantically hidden), and its own document must not be able to send anyone to an external
    // browser — see ArticleWebView.
    val active = row.id == selectedId
    Box(
        Modifier.fillMaxSize()
            .offset {
                val offsetFraction = index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                IntOffset((offsetFraction * widthPx()).roundToInt(), 0)
            }
            .let { if (active) it else it.clearAndSetSemantics {} },
    ) {
        reader(document.html, document.body, document.articleUrl, active)
    }
}

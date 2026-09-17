package works.merc.keryx.app.ui.home

import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function coverage for the two directions the article reader's pager and `HomeViewModel`'s
 * selection keep each other in step — deliberately free of `PagerState` and of composition, in the
 * same shape as `ArticleDetailLoadGuardTest`.
 */
class ArticlePagerSyncTest {

    private fun rows(vararg ids: String): List<ArticleListRow> = ids.map { id ->
        ArticleListRow(
            id = id,
            feed_id = "f1",
            title = "Article $id",
            url = "https://example.com/$id",
            published_at = 1L,
            created_at = 1L,
            is_read = 0L,
            is_starred = 0L,
        )
    }

    private fun article(id: String): Articles = Articles(
        id = id,
        feed_id = "f1",
        guid = "g$id",
        url = "https://example.com/$id",
        title = "Article $id",
        summary = null,
        content = "<p>content $id</p>",
        author = null,
        published_at = 1L,
        thumbnail_url = null,
        is_read = 0L,
        read_at = null,
        is_starred = 0L,
        starred_at = null,
        cached_at = 0L,
        search_text = "content $id",
        updated_at = 0L,
        created_at = 0L,
        deleted_at = null,
        deleted_updated_at = null,
    )

    // --- settledPageSelects ---

    @Test
    fun settledPageSelectsWhenASwipeCameToRestOnADifferentArticle() {
        assertTrue(settledPageSelects(settledId = "a2", selectedId = "a1", isSwipeTarget = true))
    }

    @Test
    fun settledPageSelectsNothingWhenTheSettleWasNotASwipeTarget() {
        // PagerState clamps currentPage (and therefore settledPage) whenever the backing list
        // shrinks below it — a search, a sync-merge tombstone, "unread only" hiding the article
        // just read — with no swipe involved at all. Selecting (and marking read) whatever landed
        // there would open an article the user never touched.
        assertFalse(settledPageSelects(settledId = "a2", selectedId = "a1", isSwipeTarget = false))
    }

    @Test
    fun settledPageSelectsNothingWhenItIsAlreadyTheSelectedArticle() {
        // Otherwise every settle would re-enter selectArticle and re-run its read write and pinning.
        assertFalse(settledPageSelects(settledId = "a1", selectedId = "a1", isSwipeTarget = true))
    }

    @Test
    fun settledPageSelectsNothingWhenNothingIsSelectedYet() {
        // A tablet-width layout keeps the reader on screen beside the list with no selection; a
        // pager resting on page 0 there must not open — and mark read — the first article by itself.
        assertFalse(settledPageSelects(settledId = "a1", selectedId = null, isSwipeTarget = true))
    }

    @Test
    fun settledPageSelectsNothingWhenThereIsNoPageToRestOn() {
        assertFalse(settledPageSelects(settledId = null, selectedId = "a1", isSwipeTarget = true))
    }

    // --- pageIndexToRestore ---

    @Test
    fun pageIndexToRestoreFollowsASelectionMadeElsewhere() {
        // The article list, J/K, or the reader's own accessibility actions moved the selection.
        assertEquals(2, pageIndexToRestore(rows("a1", "a2", "a3"), selectedId = "a3", currentPage = 0, gestureInProgress = false))
    }

    @Test
    fun pageIndexToRestoreLeavesThePagerAloneWhenItAlreadyShowsTheSelection() {
        assertNull(pageIndexToRestore(rows("a1", "a2", "a3"), selectedId = "a2", currentPage = 1, gestureInProgress = false))
    }

    @Test
    fun pageIndexToRestoreReAnchorsWhenTheListShiftsUnderThePager() {
        // Same selection, but a row ahead of it disappeared (a sync merge tombstone, or an "unread
        // only" toggle hiding the article just marked read), so its index moved.
        assertEquals(0, pageIndexToRestore(rows("a2", "a3"), selectedId = "a2", currentPage = 1, gestureInProgress = false))
    }

    @Test
    fun pageIndexToRestoreNeverInterruptsAGestureInFlight() {
        // The gesture is the user's own finger — including the gap between releasing it and the
        // settle animation starting, where PagerState.isScrollInProgress has already gone false but
        // the swipe still owns the pager. Yanking it elsewhere there would take the page out from
        // under them.
        assertNull(pageIndexToRestore(rows("a1", "a2", "a3"), selectedId = "a3", currentPage = 0, gestureInProgress = true))
    }

    @Test
    fun pageIndexToRestoreLeavesThePagerAloneWhenTheSelectionIsNotInTheList() {
        // A tombstoned selection: HomeViewModel resolves that itself (reconcilePinnedArticlesAndSelection),
        // and moving the pager to an arbitrary page in the meantime would show the wrong article.
        assertNull(pageIndexToRestore(rows("a1", "a2"), selectedId = "gone", currentPage = 1, gestureInProgress = false))
    }

    @Test
    fun pageIndexToRestoreLeavesThePagerAloneWhenNothingIsSelected() {
        assertNull(pageIndexToRestore(rows("a1", "a2"), selectedId = null, currentPage = 0, gestureInProgress = false))
    }

    // --- readerContents ---

    @Test
    fun readerContentsAddsTheSelectedArticleOverTheCache() {
        val cached = mapOf("a1" to ArticleReaderRow("a1", "u1", "cached title", null, 1L, "<p>old</p>", null))
        val result = readerContents(cached, selected = article("a2"))

        assertTrue("a1" in result)
        assertEquals("<p>content a2</p>", result.getValue("a2").content)
    }

    @Test
    fun readerContentsPrefersTheSelectedRowOverAStaleCacheEntry() {
        // The cache does not skip the selected article, so a stale copy of it can coexist with the
        // ViewModel's own authoritative row. The selected row must win, or the page on screen would
        // show a body that could be older than what selectArticle already loaded.
        val cached = mapOf("a1" to ArticleReaderRow("a1", "u1", "stale", null, 1L, "<p>stale</p>", null))
        val result = readerContents(cached, selected = article("a1"))

        assertEquals("<p>content a1</p>", result.getValue("a1").content)
    }

    @Test
    fun readerContentsReturnsTheCacheUnchangedWhenNothingIsSelected() {
        val cached = mapOf("a1" to ArticleReaderRow("a1", "u1", "t", null, 1L, "<p>c</p>", null))
        assertEquals(cached, readerContents(cached, selected = null))
    }

    // --- readerPages ---

    @Test
    fun readerPagesReturnsTheListUnchangedWhenTheSelectionIsInIt() {
        val pages = rows("a1", "a2")
        assertEquals(pages, readerPages(pages, selected = article("a1")))
    }

    @Test
    fun readerPagesReturnsTheListUnchangedWhenNothingIsSelected() {
        val pages = rows("a1", "a2")
        assertEquals(pages, readerPages(pages, selected = null))
    }

    @Test
    fun readerPagesFallsBackToAOnePageListBuiltFromTheSelection() {
        // The article list starts empty (pagerArticles is WhileSubscribed) or can briefly omit a
        // freshly-selected row; falling back keeps the reader on the same composition slot instead
        // of tearing down its WebView by swapping between an empty pager and a populated one.
        val result = readerPages(emptyList(), selected = article("a1"))

        assertEquals(listOf("a1"), result.map { it.id })
    }
}

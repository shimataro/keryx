package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.toListRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [resolveSelectedRow] and [resolveReaderContent] — what the toolbar and the reader show per selection state. */
class ReaderContentTest {

    private fun row(id: String, isStarred: Long = 0L) = ArticleListRow(
        id = id, feed_id = "f1", title = "Article $id", url = "https://example.com/$id",
        published_at = 1L, created_at = 1L, is_read = 1L, is_starred = isStarred,
    )

    private fun article(id: String) = Articles(
        id = id, feed_id = "f1", guid = "g$id", url = "https://example.com/$id", title = "Article $id",
        summary = null, content = "<p>$id</p>", author = null, published_at = 1L, thumbnail_url = null,
        is_read = 1L, read_at = null, is_starred = 0L, starred_at = null, cached_at = 0L, search_text = "",
        updated_at = 0L, created_at = 1L, deleted_at = null, deleted_updated_at = null,
    )

    @Test
    fun theLoadedArticleWinsOnceItIsTheCursors() {
        val loaded = article("a1")
        assertEquals(loaded.toListRow(), resolveSelectedRow("a1", loaded, row("a1", isStarred = 1L)))
    }

    @Test
    fun thePendingRowStandsInWhileTheLoadedArticleIsStillThePreviousOne() {
        val pending = row("a2")
        assertEquals(pending, resolveSelectedRow("a2", article("a1"), pending))
    }

    @Test
    fun nothingIsSelectedWithoutACursor() {
        assertNull(resolveSelectedRow(null, null, null))
        assertNull(resolveSelectedRow("a2", article("a1"), null), "a row for another article is never borrowed")
    }

    @Test
    fun theReaderKeepsThePreviousArticleUntilTheHeaderIsAskedFor() {
        val previous = article("a1")
        assertEquals(ReaderContent.Full(previous), resolveReaderContent("a2", previous, row("a2"), headerId = null))
    }

    @Test
    fun theReaderShowsTheHeaderOnceAskedForAndTheBodyIsStillMissing() {
        val pending = row("a2")
        assertEquals(ReaderContent.Header(pending), resolveReaderContent("a2", article("a1"), pending, headerId = "a2"))
    }

    @Test
    fun aHeaderForAnotherArticleOrAnAlreadyLoadedOneIsNotShown() {
        val loaded = article("a2")
        assertEquals(ReaderContent.Full(loaded), resolveReaderContent("a2", loaded, row("a2"), headerId = "a2"))
        assertEquals(ReaderContent.None, resolveReaderContent("a3", null, row("a3"), headerId = "a2"))
    }

    @Test
    fun theReaderShowsNothingWithNothingLoaded() {
        assertEquals(ReaderContent.None, resolveReaderContent(null, null, null, headerId = null))
    }

    @Test
    fun listRowNamesTheArticleEachContentShows() {
        val loaded = article("a1")
        assertNull(ReaderContent.None.listRow())
        assertEquals(row("a2"), ReaderContent.Header(row("a2")).listRow())
        assertEquals(loaded.toListRow(), ReaderContent.Full(loaded).listRow())
    }
}

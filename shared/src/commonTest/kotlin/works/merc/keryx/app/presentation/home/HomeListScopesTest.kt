package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeListScopesTest {

    @Test
    fun narrowingForAllDoesNotNarrow() {
        assertEquals(ListNarrowing(unreadOnly = false, starredOnly = false), narrowingFor(ArticleListMode.All))
    }

    @Test
    fun narrowingForUnreadIsUnreadOnly() {
        assertEquals(ListNarrowing(unreadOnly = true, starredOnly = false), narrowingFor(ArticleListMode.Unread))
    }

    @Test
    fun narrowingForStarredIsStarredOnly() {
        assertEquals(ListNarrowing(unreadOnly = false, starredOnly = true), narrowingFor(ArticleListMode.Starred))
    }

    @Test
    fun searchListDisplayRowsAndTitleMarksMatchTheResultsInOrder() {
        val a = row("a")
        val b = row("b")
        val display = SearchListDisplay(listOf(ArticleSearchResult(a, "<m>A</m>"), ArticleSearchResult(b, "B")))

        assertEquals(listOf(a, b), display.rows)
        assertEquals(mapOf("a" to "<m>A</m>", "b" to "B"), display.titleMarkedById)
    }

    @Test
    fun searchListDisplayOfNoResultsIsEmpty() {
        val display = SearchListDisplay(emptyList())

        assertTrue(display.rows.isEmpty())
        assertTrue(display.titleMarkedById.isEmpty())
    }

    private fun row(id: String) = ArticleListRow(
        id = id, feed_id = "f", title = "Title $id", url = "https://example.com/$id",
        published_at = null, created_at = 0L, is_read = 0L, is_starred = 0L,
    )
}

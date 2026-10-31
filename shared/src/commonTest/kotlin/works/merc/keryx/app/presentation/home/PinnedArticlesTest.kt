package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** [resolvePinnedArticles], [resolvePinnedSearchResults] and the read/unread pin partition. */
class PinnedArticlesTest {

    private fun row(
        id: String,
        isRead: Long = 0L,
        isStarred: Long = 0L,
        publishedAt: Long? = 1L,
        createdAt: Long = 1L,
    ) = ArticleListRow(
        id = id, feed_id = "f1", title = "Article $id", url = "https://example.com/$id",
        published_at = publishedAt, created_at = createdAt, is_read = isRead, is_starred = isStarred,
    )

    private fun pins(vararg rows: ArticleListRow): Map<String, ArticleListRow> = rows.associateBy { it.id }

    private fun resolve(
        list: List<ArticleListRow>,
        unread: Boolean = false,
        newest: Boolean = true,
        read: Map<String, ArticleListRow> = emptyMap(),
        unstarred: Map<String, ArticleListRow> = emptyMap(),
    ) = resolvePinnedArticles(list, unread, newest, read, unstarred)

    private fun result(r: ArticleListRow) = ArticleSearchResult(r, "<b>${r.title}</b>")

    // ---- resolvePinnedArticles ----

    @Test
    fun noPinsReturnsTheSameListInstance() {
        val list = listOf(row("a"), row("b", isRead = 1L))
        assertSame(list, resolve(list))
    }

    @Test
    fun noPinsWithOldestFirstReversesTheList() {
        val list = listOf(row("a"), row("b"))
        assertEquals(listOf("b", "a"), resolve(list, newest = false).map { it.id })
    }

    @Test
    fun noPinsUnreadOnlyFiltersReadRows() {
        val list = listOf(row("a"), row("b", isRead = 1L), row("c"))
        assertEquals(listOf("a", "c"), resolve(list, unread = true).map { it.id })
    }

    @Test
    fun aPinnedRowWhoseFieldsAlreadyMatchKeepsItsInstance() {
        val a = row("a", isRead = 1L)
        val b = row("b")
        val list = listOf(a, b)
        val result = resolve(list, read = pins(row("a", isRead = 1L)))
        assertSame(list, result)
        assertSame(a, result[0])
    }

    @Test
    fun aPinnedRowWhoseFieldsDifferIsReplacedAndOthersKeepTheirInstance() {
        val a = row("a")
        val b = row("b")
        val result = resolve(listOf(a, b), read = pins(row("a", isRead = 1L)))
        assertEquals(row("a", isRead = 1L), result[0])
        assertNotSame(a, result[0])
        assertSame(b, result[1])
    }

    @Test
    fun readPinAndUnstarPinOnTheSameIdResolvePerField() {
        val a = row("a", isRead = 0L, isStarred = 1L)
        val result = resolve(
            listOf(a),
            read = pins(row("a", isRead = 1L, isStarred = 1L)),
            unstarred = pins(row("a", isRead = 0L, isStarred = 0L)),
        )
        assertEquals(listOf(row("a", isRead = 1L, isStarred = 0L)), result)
    }

    @Test
    fun unstarPinAloneKeepsTheRowsReadState() {
        val a = row("a", isRead = 1L, isStarred = 1L)
        val result = resolve(listOf(a), unstarred = pins(row("a", isRead = 0L, isStarred = 0L)))
        assertEquals(listOf(row("a", isRead = 1L, isStarred = 0L)), result)
    }

    @Test
    fun aReadPinnedRowMissingFromTheListIsReAddedInSortedPosition() {
        val newer = row("n", publishedAt = 300L)
        val older = row("o", publishedAt = 100L)
        val result = resolve(listOf(newer, older), read = pins(row("m", isRead = 1L, publishedAt = 200L)))
        assertEquals(listOf("n", "m", "o"), result.map { it.id })
    }

    @Test
    fun reAddedRowsSortByPublishedThenCreatedThenIdDescending() {
        val list = listOf(
            row("a", publishedAt = 10L, createdAt = 5L),
            row("c", publishedAt = 10L, createdAt = 5L),
        )
        val result = resolve(
            list,
            read = pins(
                row("b", isRead = 1L, publishedAt = 10L, createdAt = 5L), // same keys: id decides
                row("d", isRead = 1L, publishedAt = 10L, createdAt = 9L), // later created_at first
            ),
        )
        assertEquals(listOf("d", "c", "b", "a"), result.map { it.id })
    }

    @Test
    fun nullPublishedAtIsTreatedAsZeroWhenSorting() {
        val list = listOf(row("a", publishedAt = 1L), row("z", publishedAt = 0L, createdAt = 1L))
        val result = resolve(list, read = pins(row("m", isRead = 1L, publishedAt = null, createdAt = 1L)))
        // m (0, 1, "m") sorts after a (1) and between nothing else: ties with z on 0/1, id "z" > "m".
        assertEquals(listOf("a", "z", "m"), result.map { it.id })
    }

    @Test
    fun anUnreadPinnedRowMissingFromTheListIsNotReAdded() {
        val a = row("a")
        val result = resolve(listOf(a), read = pins(row("gone", isRead = 0L)))
        assertEquals(listOf(a), result)
    }

    @Test
    fun anUnstarPinOnAMissingRowIsReAddedWithItsStarFromTheUnstarPinOverTheReadPinsBase() {
        val result = resolve(
            listOf(row("a", publishedAt = 50L)),
            read = pins(row("m", isRead = 1L, isStarred = 1L, publishedAt = 100L)),
            unstarred = pins(row("m", isRead = 0L, isStarred = 0L, publishedAt = 100L)),
        )
        assertEquals(listOf(row("m", isRead = 1L, isStarred = 0L, publishedAt = 100L), row("a", publishedAt = 50L)), result)
    }

    @Test
    fun anUnstarPinOnAMissingRowWithNoReadPinIsReAddedAsPinned() {
        val pin = row("m", isRead = 0L, isStarred = 0L, publishedAt = 100L)
        val result = resolve(listOf(row("a", publishedAt = 50L)), unstarred = pins(pin))
        assertEquals(listOf("m", "a"), result.map { it.id })
        assertEquals(pin, result[0])
    }

    @Test
    fun unreadOnlyKeepsUnreadRowsAndRowsWithAReadPin() {
        val unreadRow = row("u")
        val readRow = row("r", isRead = 1L)
        val pinnedRead = row("p") // list still says unread; the pin makes it read
        val result = resolve(
            listOf(unreadRow, readRow, pinnedRead),
            unread = true,
            read = pins(row("p", isRead = 1L)),
        )
        assertEquals(listOf("u", "p"), result.map { it.id })
        assertEquals(1L, result[1].is_read)
    }

    @Test
    fun unreadOnlyKeepsAReAddedReadPinnedRow() {
        val result = resolve(listOf(row("u", publishedAt = 1L)), unread = true, read = pins(row("m", isRead = 1L, publishedAt = 5L)))
        assertEquals(listOf("m", "u"), result.map { it.id })
    }

    @Test
    fun anUnreadPinTurnsAStaleReadRowUnreadAndItSurvivesUnreadOnly() {
        val result = resolve(listOf(row("a", isRead = 1L)), unread = true, read = pins(row("a", isRead = 0L)))
        assertEquals(listOf(row("a", isRead = 0L)), result)
    }

    @Test
    fun oldestFirstReversesTheResolvedList() {
        val result = resolve(
            listOf(row("n", publishedAt = 300L), row("o", publishedAt = 100L)),
            newest = false,
            read = pins(row("m", isRead = 1L, publishedAt = 200L)),
        )
        assertEquals(listOf("o", "m", "n"), result.map { it.id })
    }

    // ---- resolvePinnedSearchResults ----

    @Test
    fun searchWithNoPinsKeepsTheResultInstances() {
        val raw = listOf(result(row("a")), result(row("b", isRead = 1L)))
        val out = resolvePinnedSearchResults(raw, false, emptyMap(), emptyMap())
        assertEquals(raw, out)
        assertSame(raw[0], out[0])
        assertSame(raw[1], out[1])
    }

    @Test
    fun searchMergesReadAndStarFieldsPerFieldKeepingTheMarkup() {
        val raw = listOf(result(row("a", isRead = 0L, isStarred = 1L)), result(row("b")))
        val out = resolvePinnedSearchResults(
            raw, false,
            pins(row("a", isRead = 1L, isStarred = 1L)),
            pins(row("a", isRead = 0L, isStarred = 0L)),
        )
        assertEquals(row("a", isRead = 1L, isStarred = 0L), out[0].article)
        assertEquals(raw[0].titleMarked, out[0].titleMarked)
        assertSame(raw[1], out[1])
    }

    @Test
    fun searchTakesOnlyTheReadStateFromAReadPinNotItsStaleStar() {
        val raw = listOf(result(row("a", isRead = 1L, isStarred = 1L)))
        val out = resolvePinnedSearchResults(raw, false, pins(row("a", isRead = 1L, isStarred = 0L)), emptyMap())
        assertEquals(1L, out[0].article.is_starred)
    }

    @Test
    fun searchUnreadFilterKeepsUnreadAndReadPinnedMembers() {
        val raw = listOf(result(row("u")), result(row("r", isRead = 1L)), result(row("p")))
        val out = resolvePinnedSearchResults(raw, true, pins(row("p", isRead = 1L)), emptyMap())
        assertEquals(listOf("u", "p"), out.map { it.article.id })
    }

    @Test
    fun searchDoesNotReAddPinnedRowsAbsentFromTheResult() {
        val raw = listOf(result(row("a")))
        val out = resolvePinnedSearchResults(
            raw, false,
            pins(row("gone", isRead = 1L)),
            pins(row("gone2", isStarred = 0L)),
        )
        assertEquals(listOf("a"), out.map { it.article.id })
    }

    // ---- readPins / unreadPins ----

    @Test
    fun readAndUnreadPinsPartitionTheMap() {
        val map = pins(row("r1", isRead = 1L), row("u1", isRead = 0L), row("r2", isRead = 1L))
        assertEquals(setOf("r1", "r2"), map.readPins().keys)
        assertEquals(setOf("u1"), map.unreadPins().keys)
        assertEquals(map.keys, map.readPins().keys + map.unreadPins().keys)
    }

    @Test
    fun partitionOfAnEmptyMapIsEmpty() {
        assertEquals(emptyMap(), emptyMap<String, ArticleListRow>().readPins())
        assertEquals(emptyMap(), emptyMap<String, ArticleListRow>().unreadPins())
    }
}

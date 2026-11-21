package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [SearchTextBackfill] and the `ks_search_pending` skip in [FtsManager.indexMissing]. */
class SearchTextBackfillTest {

    private val driver: SqlDriver
    private val db: KeryxDatabase

    init {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
        db.insertFeed("f1")
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** A merged article: `search_text` still `''`, listed in `ks_search_pending`. */
    private fun mergedArticle(id: String, content: String?, summary: String? = null) {
        db.articlesQueries.insert(
            id = id, feed_id = "f1", guid = id, url = "https://article/$id", title = "Title $id",
            summary = summary, content = content, author = null, published_at = null,
            thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
        )
        driver.execute(null, "INSERT INTO ks_search_pending (rowid) SELECT rowid FROM articles WHERE id = '$id'", 0)
    }

    private fun searchText(id: String): String {
        var text = ""
        driver.executeQuery(null, "SELECT search_text FROM articles WHERE id = '$id'", { c ->
            c.next(); text = c.getString(0)!!; QueryResult.Unit
        }, 0)
        return text
    }

    private fun pendingIds(): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            null,
            "SELECT a.id FROM ks_search_pending p JOIN articles a ON a.rowid = p.rowid ORDER BY a.id",
            { c -> while (c.next().value) ids += c.getString(0)!!; QueryResult.Unit },
            0,
        )
        return ids
    }

    private fun pendingCount(): Long {
        var n = 0L
        driver.executeQuery(null, "SELECT COUNT(*) FROM ks_search_pending", { c -> c.next(); n = c.getLong(0)!!; QueryResult.Unit }, 0)
        return n
    }

    private fun setContent(id: String, content: String) =
        driver.execute(null, "UPDATE articles SET content = '$content' WHERE id = '$id'", 0)

    private fun indexedIds(): Set<String> {
        val ids = mutableSetOf<String>()
        driver.executeQuery(
            null,
            "SELECT a.id FROM articles a WHERE a.rowid IN (SELECT id FROM articles_fts_docsize)",
            { c -> while (c.next().value) ids += c.getString(0)!!; QueryResult.Unit },
            0,
        )
        return ids
    }

    private fun backfill(batchSize: Long = 500, fts: FtsManager = FtsManager(driver), toPlainText: (String) -> String = { it.removePrefix("<p>").removeSuffix("</p>") }) =
        SearchTextBackfill(db, fts, Dispatchers.Unconfined, batchSize, toPlainText)

    @Test
    fun recomputesEveryPendingRowInBatches() = runTest {
        for (i in 1..5) mergedArticle("a$i", content = "<p>body $i</p>")
        mergedArticle("s1", content = null, summary = "<p>summary only</p>")
        mergedArticle("n1", content = null)
        val converted = mutableListOf<String>()
        val done = backfill(batchSize = 2) { html -> converted += html; html.removePrefix("<p>").removeSuffix("</p>") }
            .recomputePending()
        assertEquals(7, done, "a body-less article counts too")
        assertEquals(0L, pendingCount())
        assertEquals("body 3", searchText("a3"))
        assertEquals("summary only", searchText("s1"))
        assertEquals("", searchText("n1"))
        assertEquals(6, converted.size, "a body-less article needs no conversion")
    }

    @Test
    fun aBodyChangedDuringConversionIsRecomputedFromTheNewBody() = runTest {
        mergedArticle("a1", content = "<p>old</p>")
        mergedArticle("a2", content = "<p>other</p>")
        var changed = false
        val done = backfill { html ->
            // A feed refresh rewrites a1's body between the read and the write.
            if (html == "<p>old</p>" && !changed) { changed = true; setContent("a1", "<p>new</p>") }
            html.removePrefix("<p>").removeSuffix("</p>")
        }.recomputePending()
        assertEquals(2, done)
        assertEquals("new", searchText("a1"), "the stale text is never written")
        assertEquals("other", searchText("a2"))
        assertEquals(0L, pendingCount())
    }

    @Test
    fun aBodyThatKeepsChangingStaysPendingWithoutAStaleText() = runTest {
        mergedArticle("a1", content = "<p>v0</p>")
        mergedArticle("a2", content = "<p>stable</p>")
        var version = 0
        val done = backfill { html ->
            if (html.startsWith("<p>v")) setContent("a1", "<p>v${++version}</p>")
            html.removePrefix("<p>").removeSuffix("</p>")
        }.recomputePending()
        assertEquals(1, done)
        assertEquals(listOf("a1"), pendingIds())
        assertEquals("", searchText("a1"))
        assertEquals("stable", searchText("a2"))
        assertTrue(version in 2..5, "a few passes, not an endless loop: $version")
    }

    @Test
    fun aConversionFailureWritesAnEmptyTextAndMovesOn() = runTest {
        mergedArticle("a1", content = "<p>boom</p>")
        mergedArticle("a2", content = "<p>fine</p>")
        val done = backfill { html ->
            if ("boom" in html) throw IllegalStateException("unparseable")
            html.removePrefix("<p>").removeSuffix("</p>")
        }.recomputePending()
        assertEquals(2, done)
        assertEquals("", searchText("a1"))
        assertEquals("fine", searchText("a2"))
        assertEquals(0L, pendingCount())
    }

    @Test
    fun aCancelledPassResumesWhereItStopped() = runTest {
        for (i in 1..4) mergedArticle("a$i", content = "<p>body $i</p>")
        assertFailsWith<CancellationException> {
            backfill(batchSize = 1) { html ->
                if ("body 3" in html) throw CancellationException("cancelled")
                html.removePrefix("<p>").removeSuffix("</p>")
            }.recomputePending()
        }
        // The first two batches committed; the rest are still pending.
        assertEquals(listOf("a3", "a4"), pendingIds())
        assertEquals("body 1", searchText("a1"))

        assertEquals(2, backfill(batchSize = 1).recomputePending())
        assertEquals(0L, pendingCount())
        assertEquals("body 4", searchText("a4"))
    }

    @Test
    fun aPendingRowWithoutAnArticleIsDropped() = runTest {
        mergedArticle("a1", content = "<p>x</p>")
        driver.execute(null, "INSERT INTO ks_search_pending (rowid) VALUES (9999)", 0)
        assertEquals(1, backfill().recomputePending())
        assertEquals(0L, pendingCount())
    }

    @Test
    fun indexMissingSkipsPendingRowsUntilTheyAreRecomputed() = runTest {
        val fts = ftsManagerIndexed(driver)
        mergedArticle("a1", content = "<p>kotlin multiplatform</p>")
        db.articlesQueries.insert(
            id = "local", feed_id = "f1", guid = "local", url = "https://article/local", title = "Local",
            summary = null, content = "<p>kotlin local</p>", author = null, published_at = null,
            thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
            cached_at = 0L, search_text = "kotlin local", updated_at = 0L, created_at = 0L,
        )

        fts.indexMissing()
        assertEquals(setOf("local"), indexedIds(), "a pending row is not indexed with its empty text")

        backfill(fts = fts).recomputePendingAndIndex()
        assertEquals(setOf("local", "a1"), indexedIds())
        assertEquals(listOf("a1"), FtsSearch(driver).search("multiplatform", ArticleFilter.All).map { it.id })
    }

    @Test
    fun recomputePendingAndIndexSkipsIndexingWhenTheTableIsAbsent() = runTest {
        mergedArticle("a1", content = "<p>x</p>")
        backfill().recomputePendingAndIndex() // no articles_fts yet: must not fail
        assertEquals(0L, pendingCount())
    }
}

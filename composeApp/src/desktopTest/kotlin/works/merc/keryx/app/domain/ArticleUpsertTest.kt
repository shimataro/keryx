package works.merc.keryx.app.domain

import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.ParsedArticle
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleUpsertTest {
    @Test
    fun countsNewAndPreservesReadStateOnReupsert() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L })
            db.insertFeed("f1")

            val first = repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A", content = "body", url = "u")))
            assertEquals(1, first)

            val id = db.articlesQueries.getByFeedAndGuid("f1", "g1").executeAsOne().id
            repo.markAsRead(id)
            assertEquals(1L, db.articlesQueries.getById(id).executeAsOne().is_read)

            // Same guid again: not new, read state preserved, metadata refreshed.
            val second = repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A2", content = "body2")))
            assertEquals(0, second)

            val after = db.articlesQueries.getById(id).executeAsOne()
            assertEquals(1L, after.is_read)
            assertEquals("A2", after.title)
            assertEquals("body2", after.search_text)
        } finally {
            driver.close()
        }
    }

    @Test
    fun sameArticleGetsSameIdAcrossIndependentDatabases() {
        // Reproduces the cross-device sync bug: two devices independently fetching the same article
        // (same feed_id + guid) must store it under the SAME id, otherwise the merge (matched by id)
        // skips it and read/star state never propagates. Before the deterministic-id fix these ids
        // were random and differed.
        fun storedIdForSameArticle(): String {
            val (driver, db) = inMemoryDb()
            try {
                val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L })
                db.insertFeed("f1")
                repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A", url = "u")))
                return db.articlesQueries.getByFeedAndGuid("f1", "g1").executeAsOne().id
            } finally {
                driver.close()
            }
        }

        assertEquals(storedIdForSameArticle(), storedIdForSameArticle())
    }

    @Test
    fun searchTextPrefersContentThenSummary() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1L })
            db.insertFeed("f1")
            repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", summary = "only summary")))
            val a = db.articlesQueries.getByFeedAndGuid("f1", "g1").executeAsOne()
            assertEquals("only summary", a.search_text)
        } finally {
            driver.close()
        }
    }

    @Test
    fun searchTextStripsHtmlTagsSoTagNamesAreNotSearchable() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1L })
            db.insertFeed("f1")
            val html = "<div class=\"post\"><p>Kotlin Multiplatform rocks</p></div>"
            repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", content = html)))

            val a = db.articlesQueries.getByFeedAndGuid("f1", "g1").executeAsOne()
            // Visible text kept; markup removed. content itself stays raw HTML for rendering.
            assertEquals("Kotlin Multiplatform rocks", a.search_text)
            assertEquals(html, a.content)
            assertFalse(a.search_text.contains("div"))
            assertFalse(a.search_text.contains("class"))

            ftsManager(driver).ensureIndexed()
            // The visible word is searchable; the HTML tag name / attribute is not.
            assertContains(FtsSearch(driver).search("Kotlin").map { it.id }, a.id)
            assertTrue(FtsSearch(driver).search("div").isEmpty())
            assertTrue(FtsSearch(driver).search("class").isEmpty())
        } finally {
            driver.close()
        }
    }
}

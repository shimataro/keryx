package works.merc.keryx.app.domain

import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.ParsedArticle
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.stampArticleDeleted
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

    @Test
    fun duplicateGuidWithinOneBatchIsCountedOnce() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L })
            db.insertFeed("f1")
            // Same guid twice in one batch: the second collides on UNIQUE(feed_id, guid), so only one
            // row exists and it must be counted new exactly once (matching the previous behavior, where
            // the in-transaction re-read saw the first insert before checking the second).
            val count = repo.upsertParsed(
                "f1",
                listOf(
                    ParsedArticle(guid = "g1", title = "A", content = "one"),
                    ParsedArticle(guid = "g1", title = "A again", content = "two"),
                ),
            )
            assertEquals(1, count)
            assertEquals(1L, db.articlesQueries.countArticles().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun mixedBatchCountsOnlyPreviouslyUnseenGuids() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L })
            db.insertFeed("f1")
            assertEquals(1, repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A"))))
            // g1 already stored; g2 and g3 are new -> exactly 2 counted new.
            val count = repo.upsertParsed(
                "f1",
                listOf(
                    ParsedArticle(guid = "g1", title = "A2"),
                    ParsedArticle(guid = "g2", title = "B"),
                    ParsedArticle(guid = "g3", title = "C"),
                ),
            )
            assertEquals(2, count)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reupsertOfSoftDeletedGuidIsNotCountedAsNew() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L })
            db.insertFeed("f1")
            repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A")))
            val id = db.articlesQueries.getByFeedAndGuid("f1", "g1").executeAsOne().id
            driver.stampArticleDeleted(id, deletedAt = 1000L)
            // The row still exists (tombstoned); its guid is already known, so a re-upsert is not
            // "new" — matching the pre-change getByFeedAndGuid existence check, which had no
            // deleted_at filter (getGuidsByFeed likewise includes tombstoned rows).
            assertEquals(0, repo.upsertParsed("f1", listOf(ParsedArticle(guid = "g1", title = "A2"))))
        } finally {
            driver.close()
        }
    }
}

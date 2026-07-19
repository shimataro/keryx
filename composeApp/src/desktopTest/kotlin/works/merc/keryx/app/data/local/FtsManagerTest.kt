package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Inserts an article directly (bypassing the repository) for FTS tests. */
private fun KeryxDatabase.insertArticle(id: String, feedId: String, title: String, content: String?) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = null,
        thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
        cached_at = 0L, search_text = content ?: "", updated_at = 0L, created_at = 0L,
    )
}

class FtsManagerTest {
    @Test
    fun existsIsFalseBeforeTableIsCreated() {
        val (driver, _) = inMemoryDb()
        try {
            assertFalse(ftsManager(driver).exists())
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTableMakesExistsTrue() {
        val (driver, _) = inMemoryDb()
        try {
            val manager = ftsManager(driver)
            manager.createTable()
            assertTrue(manager.exists())
        } finally {
            driver.close()
        }
    }

    @Test
    fun ensureIndexedIsIdempotentAndKeepsDataSearchable() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Hello World", "some body text")

            val manager = ftsManager(driver)
            manager.ensureIndexed() // table absent -> creates + backfills
            assertTrue(manager.exists())

            val search = FtsSearch(driver)
            assertEquals(listOf("a1"), search.search("Hello").map { it.id })

            // Calling again backfills nothing (already indexed) and must not wipe or duplicate data.
            manager.ensureIndexed()
            assertTrue(manager.exists())
            assertEquals(listOf("a1"), search.search("Hello").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun rebuildIndexMakesInsertedArticlesSearchable() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")
            db.insertArticle("a2", "f1", "Unrelated", "nothing to do with it")

            val manager = ftsManager(driver)
            // rebuildIndex() no longer creates the table (the live table is never dropped, so it
            // always exists after startup ensureIndexed()); create it first, as startup would.
            manager.createTable()
            manager.rebuildIndex()

            val ids = FtsSearch(driver).search("Kotlin").map { it.id }
            assertEquals(listOf("a1"), ids)
        } finally {
            driver.close()
        }
    }

    @Test
    fun indexMissingAddsNewRowsWithoutWipingExisting() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin One", "first body")
            val manager = ftsManager(driver)
            manager.ensureIndexed() // indexes a1
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })

            // A newly-arrived article is picked up incrementally, and the already-indexed row is not
            // wiped (a concurrent search would never regress to zero hits).
            db.insertArticle("a2", "f1", "Kotlin Two", "second body")
            manager.indexMissing()

            assertEquals(setOf("a1", "a2"), FtsSearch(driver).search("Kotlin").map { it.id }.toSet())
            assertEquals(2L, driver.countOf("SELECT COUNT(*) FROM articles_fts_docsize"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun ensureIndexedBackfillsAndPersistsOnFileDb() {
        val (file, driver, db) = fileDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")

            // The articles were inserted with no FTS index yet. ensureIndexed() must create the
            // table and index the existing rows — and it must PERSIST on a real file-backed DB
            // (JdbcSqliteDriver opens a fresh connection per statement for file DBs), not only for
            // the in-memory driver every other FTS test uses. This is the gap that hid the bug.
            ftsManager(driver).ensureIndexed()

            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })
        } finally {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun ensureIndexedRepopulatesEmptyButPresentTable() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val manager = ftsManager(driver)
            // Reproduce the reported bug: the FTS table exists but the article was inserted after
            // it (external-content FTS5 has no triggers, so the row is never auto-indexed), leaving
            // the index empty. The old ensureExists() no-opped here because the table was present.
            manager.createTable()
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")
            assertTrue(FtsSearch(driver).search("Kotlin").isEmpty())

            manager.ensureIndexed()

            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun ensureIndexedTwiceDoesNotDuplicateIndexedRows() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin", "body one")
            db.insertArticle("a2", "f1", "Serialization", "body two")

            val manager = ftsManager(driver)
            manager.ensureIndexed()
            manager.ensureIndexed()

            // Exactly one index document per article — the second call backfills nothing.
            assertEquals(2L, driver.countOf("SELECT COUNT(*) FROM articles_fts_docsize"))
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })
            assertEquals(listOf("a2"), FtsSearch(driver).search("Serialization").map { it.id })
        } finally {
            driver.close()
        }
    }
}

/** Reads a single-row COUNT(*) query straight off the driver. */
private fun SqlDriver.countOf(sql: String): Long {
    var count = 0L
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            count = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
            QueryResult.Value(count)
        },
        parameters = 0,
    )
    return count
}

package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Inserts an article directly (bypassing the repository) for FTS tests. */
private fun KeryxDatabase.insertArticle(id: String, feedId: String, title: String, content: String?) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = null,
        thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
        cached_at = 0L, search_text = content ?: "", updated_at = 0L, created_at = 0L,
    )
}

/**
 * Holds `indexMissing`'s statement open on a latch and records whether the `'rebuild'` statement is
 * reached while it is still in flight, so the writers' mutual exclusion can be checked without
 * depending on timing.
 */
private class GatedFtsDriver(private val delegate: SqlDriver) : SqlDriver {
    val indexMissingEntered = CountDownLatch(1)
    val releaseIndexMissing = CountDownLatch(1)
    private val indexMissingInFlight = AtomicBoolean(false)
    val rebuildOverlappedIndexMissing = AtomicBoolean(false)

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.contains("INSERT INTO articles_fts(rowid")) {
            indexMissingInFlight.set(true)
            indexMissingEntered.countDown()
            releaseIndexMissing.await()
            return delegate.execute(identifier, sql, parameters, binders)
                .also { indexMissingInFlight.set(false) }
        }
        if (sql.contains("VALUES('rebuild')") && indexMissingInFlight.get()) {
            rebuildOverlappedIndexMissing.set(true)
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ) = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun newTransaction() = delegate.newTransaction()
    override fun currentTransaction() = delegate.currentTransaction()
    override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)
    override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)
    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(queryKeys = queryKeys)
    override fun close() = delegate.close()
}

class FtsManagerTest {

    /**
     * The daily idle `rebuildIndex` and a refresh's `indexMissing` must never run at once: the idle
     * gate in `main.kt` is a lock-free `ActivityCenter` check, so a refresh starting just after it
     * passes is not seen. Overlapping is always wasted work (a rebuild subsumes an incremental
     * insert), and on a corpus whose rebuild outlasts `busy_timeout` the loser throws a raw
     * SQLiteException that no caller catches.
     */
    @Test
    fun indexMissingAndRebuildIndexDoNotOverlap() {
        val (raw, db) = inMemoryDb()
        val driver = GatedFtsDriver(raw)
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Hello World", "some body text")
            val manager = FtsManager(driver)
            manager.createTable()

            val indexer = Thread { runBlocking { manager.indexMissing() } }.apply { start() }
            assertTrue(driver.indexMissingEntered.await(5, TimeUnit.SECONDS), "indexMissing never started")

            // Start the rebuild while indexMissing is provably mid-statement, and give it time to
            // reach the driver if nothing holds it back.
            val rebuilder = Thread { runBlocking { manager.rebuildIndex() } }.apply { start() }
            Thread.sleep(300)
            driver.releaseIndexMissing.countDown()
            indexer.join(5_000)
            rebuilder.join(5_000)

            assertFalse(
                driver.rebuildOverlappedIndexMissing.get(),
                "rebuildIndex ran its statement while indexMissing was still in flight",
            )
        } finally {
            driver.releaseIndexMissing.countDown()
            raw.close()
        }
    }

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
            runBlocking { manager.ensureIndexed() } // table absent -> creates + backfills
            assertTrue(manager.exists())

            val search = FtsSearch(driver)
            assertEquals(listOf("a1"), search.search("Hello").map { it.id })

            // Calling again backfills nothing (already indexed) and must not wipe or duplicate data.
            runBlocking { manager.ensureIndexed() }
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
            runBlocking { manager.rebuildIndex() }

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
            runBlocking { manager.ensureIndexed() } // indexes a1
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })

            // A newly-arrived article is picked up incrementally, and the already-indexed row is not
            // wiped (a concurrent search would never regress to zero hits).
            db.insertArticle("a2", "f1", "Kotlin Two", "second body")
            runBlocking { manager.indexMissing() }

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
            ftsManagerIndexed(driver)

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

            runBlocking { manager.ensureIndexed() }

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
            runBlocking { manager.ensureIndexed() }
            runBlocking { manager.ensureIndexed() }

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

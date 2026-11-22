package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.HtmlText
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.awaitConditionBlocking
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
 * Inserts an article the way a sync merge leaves a new one: `search_text = ''` and the row listed in
 * `ks_search_pending`.
 */
private fun KeryxDatabase.insertMergedArticle(id: String, feedId: String, title: String, content: String?, summary: String? = null) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = summary, content = content, author = null, published_at = null,
        thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
        cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
    )
}

private fun SqlDriver.registerPending(articleId: String) {
    execute(null, "INSERT INTO ks_search_pending (rowid) SELECT rowid FROM articles WHERE id = ?;", 1) {
        bindString(0, articleId)
    }
}

private fun SqlDriver.searchTextOf(articleId: String): String? {
    var text: String? = null
    executeQuery(null, "SELECT search_text FROM articles WHERE id = ?;", { cursor ->
        if (cursor.next().value) text = cursor.getString(0)
        QueryResult.Unit
    }, 1) { bindString(0, articleId) }
    return text
}

private fun SqlDriver.isPending(articleId: String): Boolean =
    countOf("SELECT COUNT(*) FROM ks_search_pending p JOIN articles a ON a.rowid = p.rowid WHERE a.id = '$articleId'") > 0

private fun SqlDriver.isIndexed(articleId: String): Boolean =
    countOf("SELECT COUNT(*) FROM articles_fts_docsize d JOIN articles a ON a.rowid = d.id WHERE a.id = '$articleId'") > 0

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

            // Start the rebuild while indexMissing is provably mid-statement, and wait until it has
            // either parked on the write mutex (WAITING / TIMED_WAITING, since runBlocking parks with
            // parkNanos: the lock is doing its job) or run its statement to completion
            // (TERMINATED: it got past the lock). rebuildIndex takes the mutex with no suspension
            // before it, so it reaches one of the two without further help.
            val rebuilder = Thread { runBlocking { manager.rebuildIndex() } }.apply { start() }
            awaitConditionBlocking {
                rebuilder.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.TERMINATED)
            }
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
            assertEquals(listOf("a1"), search.search("Hello", ArticleFilter.All).map { it.id })

            // Calling again backfills nothing (already indexed) and must not wipe or duplicate data.
            runBlocking { manager.ensureIndexed() }
            assertTrue(manager.exists())
            assertEquals(listOf("a1"), search.search("Hello", ArticleFilter.All).map { it.id })
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

            val ids = FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id }
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
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id })

            // A newly-arrived article is picked up incrementally, and the already-indexed row is not
            // wiped (a concurrent search would never regress to zero hits).
            db.insertArticle("a2", "f1", "Kotlin Two", "second body")
            runBlocking { manager.indexMissing() }

            assertEquals(setOf("a1", "a2"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id }.toSet())
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

            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id })
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
            assertTrue(FtsSearch(driver).search("Kotlin", ArticleFilter.All).isEmpty())

            runBlocking { manager.ensureIndexed() }

            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun ensureIndexedIfTableAbsentCreatesAndBackfillsOnFirstCall() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")

            val manager = ftsManager(driver)
            assertFalse(manager.exists())

            runBlocking { manager.ensureIndexedIfTableAbsent() }

            assertTrue(manager.exists())
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    /**
     * The whole point of [FtsManager.ensureIndexedIfTableAbsent] over [FtsManager.ensureIndexed] —
     * it must skip [FtsManager.indexMissing]'s scan once the table already exists, even if that
     * means an article inserted after table creation stays unindexed until the next hot-path
     * [FtsManager.indexMissing] call (feed refresh / sync) or the daily [FtsManager.rebuildIndex]
     * heal. This is the accepted trade-off documented on the function's own KDoc — a call site that
     * runs on every process start must not pay indexMissing's O(articles) scan every time.
     */
    @Test
    fun ensureIndexedIfTableAbsentSkipsTheBackfillWhenTheTableAlreadyExists() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val manager = ftsManager(driver)
            manager.createTable()
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")
            assertTrue(FtsSearch(driver).search("Kotlin", ArticleFilter.All).isEmpty())

            runBlocking { manager.ensureIndexedIfTableAbsent() }

            assertTrue(FtsSearch(driver).search("Kotlin", ArticleFilter.All).isEmpty(), "must not have backfilled once the table already existed")
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
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin", ArticleFilter.All).map { it.id })
            assertEquals(listOf("a2"), FtsSearch(driver).search("Serialization", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun indexMissingRecomputesPendingSearchTextBeforeIndexing() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertMergedArticle("a1", "f1", "First", "<p>Hello <b>zephyrine</b></p>")
            db.insertMergedArticle("a2", "f1", "Second", content = null, summary = "<i>quokkasaurus</i>")
            db.insertMergedArticle("a3", "f1", "Third", content = null)
            listOf("a1", "a2", "a3").forEach(driver::registerPending)
            val manager = ftsManager(driver)
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertEquals("Hello zephyrine", driver.searchTextOf("a1"))
            assertEquals("quokkasaurus", driver.searchTextOf("a2"))
            assertEquals("", driver.searchTextOf("a3"))
            assertEquals(0L, driver.countOf("SELECT COUNT(*) FROM ks_search_pending"))
            assertEquals(3L, driver.countOf("SELECT COUNT(*) FROM articles_fts_docsize"))
            val search = FtsSearch(driver)
            assertEquals(listOf("a1"), search.search("zephyrine", ArticleFilter.All).map { it.id })
            assertEquals(listOf("a2"), search.search("quokkasaurus", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun aRowWhoseConversionFailsGetsEmptyTextAndTheOthersStillProceed() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertMergedArticle("a1", "f1", "Broken article", "<p>boom</p>")
            db.insertMergedArticle("a2", "f1", "Fine article", "<p>fine wombatish</p>")
            listOf("a1", "a2").forEach(driver::registerPending)
            val manager = FtsManager(
                driver,
                beforePendingWrite = {},
                toPlainText = { html -> if ("boom" in html) error("conversion failure") else HtmlText.toPlainText(html) },
            )
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertEquals("", driver.searchTextOf("a1"))
            assertFalse(driver.isPending("a1"))
            assertTrue(driver.isIndexed("a1"), "a row whose conversion failed is still indexed (by its title)")
            assertEquals("fine wombatish", driver.searchTextOf("a2"))
            assertFalse(driver.isPending("a2"))
            assertEquals(listOf("a2"), FtsSearch(driver).search("wombatish", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    /**
     * A body changed by another writer between the batch's read and its write must not be
     * overwritten with text computed from the old body, nor leave the pending list: the row stays
     * pending, is not indexed (it would be indexed with its `''`), and the next call processes it.
     * Runs on a file DB, so the concurrent write goes through a connection of its own.
     */
    @Test
    fun aBodyChangedBetweenReadAndWriteStaysPendingAndIsNotIndexedUntilTheNextCall() {
        val (file, driver, db) = fileDb()
        try {
            db.insertFeed("f1")
            db.insertMergedArticle("a1", "f1", "Changing", "<p>older ocelot</p>")
            db.insertMergedArticle("a2", "f1", "Steady", "<p>steady stoat</p>")
            listOf("a1", "a2").forEach(driver::registerPending)
            var interleaved = false
            val manager = FtsManager(
                driver,
                beforePendingWrite = {
                    if (!interleaved) {
                        interleaved = true
                        driver.execute(null, "UPDATE articles SET content = '<p>newer narwhal</p>' WHERE id = 'a1';", 0)
                    }
                },
                toPlainText = HtmlText::toPlainText,
            )
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertTrue(interleaved)
            assertEquals("", driver.searchTextOf("a1"), "stale text must not be written over a newer body")
            assertTrue(driver.isPending("a1"))
            assertFalse(driver.isIndexed("a1"), "a row still pending must not be indexed with ''")
            assertEquals("steady stoat", driver.searchTextOf("a2"))
            assertTrue(driver.isIndexed("a2"))

            runBlocking { manager.indexMissing() }

            assertEquals("newer narwhal", driver.searchTextOf("a1"))
            assertFalse(driver.isPending("a1"))
            assertEquals(listOf("a1"), FtsSearch(driver).search("narwhal", ArticleFilter.All).map { it.id })
            assertTrue(FtsSearch(driver).search("ocelot", ArticleFilter.All).isEmpty())
        } finally {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun recomputingSearchTextRecordsNothingInTheOutbox() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertMergedArticle("a1", "f1", "Captured", "<p>outbox octopus</p>")
            driver.registerPending("a1")
            db.sync_bookkeepingQueries.setCaptureEnabled(1L)
            val manager = ftsManager(driver)
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertEquals("outbox octopus", driver.searchTextOf("a1"))
            assertTrue(db.sync_bookkeepingQueries.outboxAll().executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aPendingRowWithNoArticleIsDropped() {
        val (driver, _) = inMemoryDb()
        try {
            driver.execute(null, "INSERT INTO ks_search_pending (rowid) VALUES (9999);", 0)
            val manager = ftsManager(driver)
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertEquals(0L, driver.countOf("SELECT COUNT(*) FROM ks_search_pending"))
        } finally {
            driver.close()
        }
    }

    /** A body longer than one inline read is read in chunks, and the chunks join back exactly. */
    @Test
    fun aLongBodyIsReadInChunksAndRecomputedExactly() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // Over 300K characters, with supplementary-plane characters across the chunk boundaries.
            val content = "<p>" + "ab \uD83D\uDE00 ".repeat(60_000) + "xylophonics</p>"
            db.insertMergedArticle("a1", "f1", "Long", content)
            driver.registerPending("a1")
            val manager = ftsManager(driver)
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertFalse(driver.isPending("a1"))
            assertEquals(HtmlText.toPlainText(content), driver.searchTextOf("a1"))
            assertEquals(listOf("a1"), FtsSearch(driver).search("xylophonics", ArticleFilter.All).map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun morePendingRowsThanOneBatchAreAllProcessedInOneCall() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val ids = (1..450).map { "a$it" }
            ids.forEach { id ->
                db.insertMergedArticle(id, "f1", "Title $id", "<p>body batchling $id</p>")
                driver.registerPending(id)
            }
            val manager = ftsManager(driver)
            manager.createTable()

            runBlocking { manager.indexMissing() }

            assertEquals(0L, driver.countOf("SELECT COUNT(*) FROM ks_search_pending"))
            assertEquals(450L, driver.countOf("SELECT COUNT(*) FROM articles_fts_docsize"))
            assertEquals(450, FtsSearch(driver).search("batchling", ArticleFilter.All).size)
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

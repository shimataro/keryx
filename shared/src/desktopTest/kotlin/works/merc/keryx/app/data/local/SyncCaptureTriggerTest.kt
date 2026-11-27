package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.local.db.Sync_outbox
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.putValidators
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertGlobalSetting
import works.merc.keryx.app.insertTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the capture triggers (`sync_bookkeeping.sq` / `2.sqm`): which local writes to the synced
 * tables append to `sync_outbox`, and with which `heavy` flag.
 */
class SyncCaptureTriggerTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() = driver.close()

    private val bookkeeping get() = db.sync_bookkeepingQueries

    private fun enableCapture() = bookkeeping.setCaptureEnabled(1)

    private fun outbox(): List<Sync_outbox> = bookkeeping.outboxAll().executeAsList()

    /** The outbox as (table, pk, pk2, heavy), seq order. */
    private fun recorded(): List<List<Any?>> = outbox().map { listOf(it.tbl, it.pk, it.pk2, it.heavy) }

    private fun insertArticle(id: String, cachedAt: Long = DAY, content: String? = "body") {
        db.articlesQueries.insert(
            id = id, feed_id = "f1", guid = id, url = "https://a/$id", title = "T", summary = null,
            content = content, author = null, published_at = null, thumbnail_url = null, is_read = 0,
            read_at = null, is_starred = 0, starred_at = null, cached_at = cachedAt, search_text = "body",
            updated_at = 0, created_at = 0,
        )
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    @Test
    fun nothingIsRecordedWhileCaptureIsDisabled() {
        db.insertFeed("f1")
        insertArticle("a1")
        db.insertTag("t1", "T")
        db.insertFolder("d1", "D")
        db.insertFeedTag("f1", "t1")
        db.insertGlobalSetting("k", "v")
        exec("UPDATE articles SET is_read = 1, read_at = 5 WHERE id = 'a1'")
        exec("UPDATE feeds SET title = 'New' WHERE id = 'f1'")

        assertEquals(emptyList(), outbox())
    }

    @Test
    fun insertsOfEverySyncedTableAreRecorded() {
        enableCapture()
        db.insertFeed("f1")
        insertArticle("a1")
        db.insertTag("t1", "T")
        db.insertFolder("d1", "D")
        db.insertFeedTag("f1", "t1")
        db.insertGlobalSetting("k", "v")

        assertEquals(
            listOf(
                listOf<Any?>("feeds", "f1", null, 0L),
                listOf<Any?>("articles", "a1", null, 1L),
                listOf<Any?>("tags", "t1", null, 0L),
                listOf<Any?>("folders", "d1", null, 0L),
                listOf<Any?>("feed_tags", "f1", "t1", 0L),
                listOf<Any?>("global_settings", "k", null, 0L),
            ),
            recorded(),
        )
        // seq is strictly increasing in write order.
        val seqs = outbox().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test
    fun anUpdateThatChangesNothingIsNotRecorded() {
        db.insertFeed("f1")
        insertArticle("a1")
        db.insertTag("t1", "T")
        db.insertFolder("d1", "D")
        db.insertFeedTag("f1", "t1", now = 10)
        db.insertGlobalSetting("k", "v", now = 10)
        enableCapture()

        exec("UPDATE feeds SET title = title WHERE id = 'f1'")
        exec("UPDATE articles SET content = content, is_read = is_read WHERE id = 'a1'")
        exec("UPDATE tags SET name = name WHERE id = 't1'")
        exec("UPDATE folders SET sort_order = sort_order WHERE id = 'd1'")
        exec("UPDATE feed_tags SET updated_at = updated_at WHERE feed_id = 'f1'")
        exec("UPDATE global_settings SET value = value WHERE key = 'k'")

        assertEquals(emptyList(), outbox())
    }

    @Test
    fun aFeedUpdateIsRecordedOnlyWhenASyncedColumnOtherThanUpdatedAtChanges() {
        db.insertFeed("f1", now = 100)
        enableCapture()

        // Only updated_at moves (no query writes that alone, so raw SQL): nothing to record.
        driver.execute(null, "UPDATE feeds SET updated_at = 200 WHERE id = 'f1'", 0)
        assertEquals(emptyList(), outbox())

        db.feedsQueries.updateContent(site_url = null, title = "Renamed upstream", description = null, updated_at = 300, id = "f1")
        db.feedsQueries.updateCustomTitle("Mine", 400, 400, "f1")
        assertEquals(listOf(listOf<Any?>("feeds", "f1", null, 0L), listOf<Any?>("feeds", "f1", null, 0L)), recorded())
    }

    @Test
    fun aFetchStateWriteIsNeverRecorded() {
        db.insertFeed("f1")
        enableCapture()

        db.putValidators("f1", "\"e\"", null)
        db.feed_fetch_stateQueries.incrementErrorCount("f1", "boom")
        db.feed_fetch_stateQueries.resetErrorCount("f1")

        assertEquals(emptyList(), outbox())
    }

    @Test
    fun anArticleBodyChangeIsHeavyAndAReadStarOrDeletionChangeIsLight() {
        db.insertFeed("f1")
        insertArticle("a1")
        enableCapture()

        exec("UPDATE articles SET is_read = 1, read_at = 5 WHERE id = 'a1'")
        exec("UPDATE articles SET is_starred = 1, starred_at = 6 WHERE id = 'a1'")
        exec("UPDATE articles SET deleted_at = 7, deleted_updated_at = 7 WHERE id = 'a1'")
        exec("UPDATE articles SET content = 'new body' WHERE id = 'a1'")
        exec("UPDATE articles SET title = 'New title', is_read = 0, read_at = 8 WHERE id = 'a1'")
        exec("UPDATE articles SET thumbnail_url = 'https://img' WHERE id = 'a1'")
        // The body's stamp is part of the body group, so it travels with the body.
        exec("UPDATE articles SET body_updated_at = 9 WHERE id = 'a1'")

        assertEquals(listOf(0L, 0L, 0L, 1L, 1L, 1L, 1L), outbox().map { it.heavy })
    }

    @Test
    fun anArticleUpdatedAtOrSearchTextChangeAloneIsNotRecorded() {
        db.insertFeed("f1")
        insertArticle("a1")
        enableCapture()

        exec("UPDATE articles SET updated_at = 99, search_text = 'recomputed' WHERE id = 'a1'")

        assertEquals(emptyList(), outbox())
    }

    @Test
    fun cachedAtIsRecordedOnlyWhenItsDayChanges() {
        db.insertFeed("f1")
        insertArticle("a1", cachedAt = DAY * 10 + 1_000)
        enableCapture()

        // Later the same day: a refresh still seeing the article. Not recorded.
        exec("UPDATE articles SET cached_at = ${DAY * 10 + DAY - 1} WHERE id = 'a1'")
        assertEquals(emptyList(), outbox())

        // The next day: recorded, as a light row.
        exec("UPDATE articles SET cached_at = ${DAY * 11} WHERE id = 'a1'")
        assertEquals(listOf(listOf<Any?>("articles", "a1", null, 0L)), recorded())
    }

    @Test
    fun nothingIsRecordedWhileASyncMergingRowExists() {
        db.insertFeed("f1")
        insertArticle("a1")
        enableCapture()

        bookkeeping.beginMerging()
        insertArticle("a2")
        exec("UPDATE articles SET is_read = 1, read_at = 5 WHERE id = 'a1'")
        db.insertTag("t1", "T")
        db.insertGlobalSetting("k", "v")
        assertEquals(emptyList(), outbox())

        bookkeeping.endMerging()
        exec("UPDATE articles SET is_starred = 1, starred_at = 6 WHERE id = 'a1'")
        assertEquals(listOf(listOf<Any?>("articles", "a1", null, 0L)), recorded())
    }

    @Test
    fun tagFolderFeedTagAndSettingUpdatesAreRecordedForAnyColumn() {
        db.insertFeed("f1")
        db.insertTag("t1", "T", now = 1)
        db.insertFolder("d1", "D", now = 1)
        db.insertFeedTag("f1", "t1", now = 1)
        db.insertGlobalSetting("k", "v", now = 1)
        enableCapture()

        db.tagsQueries.softDelete(5, 5, "t1")
        exec("UPDATE folders SET sort_order = 3 WHERE id = 'd1'")
        db.feed_tagsQueries.upsert(feed_id = "f1", tag_id = "t1", deleted_at = 5, updated_at = 5)
        db.global_settingsQueries.upsert(key = "k", value_ = "v", updated_at = 5) // a stamp-only change

        assertEquals(
            listOf(
                listOf<Any?>("tags", "t1", null, 0L),
                listOf<Any?>("folders", "d1", null, 0L),
                listOf<Any?>("feed_tags", "f1", "t1", 0L),
                listOf<Any?>("global_settings", "k", null, 0L),
            ),
            recorded(),
        )
    }

    /**
     * A feed's folder is sent as its raw assignment (ks_feed_folder_raw), so a local move — which
     * writes the raw row — is recorded once as the feed's own entry, while a re-resolution caused by a
     * folder's deletion or revival records only the folder: nothing a send carries for the feed
     * changed.
     */
    @Test
    fun aMoveIsRecordedAsTheFeedAndAReResolutionAloneIsNot() {
        db.insertFolder("d1", "D", now = 1)
        db.insertFeed("f1", now = 1)
        enableCapture()

        db.feedsQueries.updateFolder(folderId = "d1", updatedAt = 10, folderUpdatedAt = 10, id = "f1")
        assertEquals(listOf(listOf<Any?>("feeds", "f1", null, 0L)), recorded())

        bookkeeping.clearOutbox()
        db.foldersQueries.softDelete(20, 20, "d1")
        assertEquals(null, db.feedsQueries.getById("f1").executeAsOne().folder_id)
        exec("UPDATE folders SET deleted_at = NULL, updated_at = 30 WHERE id = 'd1'")
        assertEquals("d1", db.feedsQueries.getById("f1").executeAsOne().folder_id)
        assertEquals(listOf(listOf<Any?>("folders", "d1", null, 0L), listOf<Any?>("folders", "d1", null, 0L)), recorded())
    }

    /** A raw change written while merging (a transport merge) is not recorded; one outside it is. */
    @Test
    fun aRawFolderChangeIsRecordedOnlyOutsideAMerge() {
        db.insertFolder("d1", "D", now = 1)
        db.insertFeed("f1", now = 1)
        enableCapture()

        bookkeeping.beginMerging()
        exec("UPDATE ks_feed_folder_raw SET folder_id = 'd1', folder_updated_at = 5 WHERE feed_id = 'f1'")
        bookkeeping.endMerging()
        assertEquals(emptyList(), outbox())

        exec("UPDATE ks_feed_folder_raw SET folder_updated_at = 6 WHERE feed_id = 'f1'")
        assertEquals(listOf(listOf<Any?>("feeds", "f1", null, 0L)), recorded())
    }

    private companion object {
        const val DAY = 86_400_000L
    }

    // ---- Feed identity edges (ks_feed_edge) ----

    @Test
    fun aFeedEdgeInsertIsRecordedWhileCaptureIsEnabled() {
        enableCapture()
        exec("INSERT INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")

        assertEquals(listOf(listOf<Any?>("feed_edges", "fa", "fb", 0L)), recorded())
    }

    @Test
    fun aFeedEdgeInsertInsideAMergeIsRecordedToo() {
        enableCapture()
        bookkeeping.beginMerging()
        exec("INSERT INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")
        bookkeeping.endMerging()

        assertEquals(listOf(listOf<Any?>("feed_edges", "fa", "fb", 0L)), recorded())
    }

    @Test
    fun aFeedEdgeInsertIsNotRecordedWhileCaptureIsDisabled() {
        exec("INSERT INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")
        bookkeeping.beginMerging()
        exec("INSERT INTO ks_feed_edge (a, b) VALUES ('fa', 'fc')")
        bookkeeping.endMerging()

        assertEquals(emptyList(), outbox())
        assertEquals(2L, driver.rowCount("ks_feed_edge"))
    }

    @Test
    fun insertingAKnownFeedEdgeAgainRecordsNothing() {
        enableCapture()
        exec("INSERT OR IGNORE INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")
        exec("INSERT OR IGNORE INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")
        bookkeeping.beginMerging()
        exec("INSERT OR IGNORE INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')")
        bookkeeping.endMerging()

        assertEquals(listOf(listOf<Any?>("feed_edges", "fa", "fb", 0L)), recorded())
        assertEquals(1L, driver.rowCount("ks_feed_edge"))
    }

    private fun SqlDriver.rowCount(table: String): Long =
        executeQuery(null, "SELECT COUNT(*) FROM $table", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null)
        }, 0).value!!
}

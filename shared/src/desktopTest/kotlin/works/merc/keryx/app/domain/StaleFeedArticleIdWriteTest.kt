package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every write that takes a feed id or an article id resolves it through `ks_id_remap` (kinds `'feed'`
 * and `'article'`) inside the statement itself, as tag and folder writes do: a write issued with the
 * id held before a re-key lands on the canonical row instead of matching nothing or failing a
 * FOREIGN KEY. The re-key is simulated by hand: the canonical row exists, the old row is absent, and
 * the move is recorded in `ks_id_remap`. Foreign keys are enforced, as in production.
 */
class StaleFeedArticleIdWriteTest {

    private fun SqlDriver.remap(kind: String, oldId: String, newId: String) {
        execute(null, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES (?, ?, ?)", 3) {
            bindString(0, kind)
            bindString(1, oldId)
            bindString(2, newId)
        }
    }

    private fun KeryxDatabase.addArticle(id: String, feedId: String, guid: String = id, isRead: Long = 0L) {
        articlesQueries.insert(
            id = id, feed_id = feedId, guid = guid, url = "https://article/$id", title = "Title $id",
            summary = null, content = null, author = null, published_at = null,
            thumbnail_url = null, is_read = isRead, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
        )
    }

    private inline fun withDb(block: (SqlDriver, KeryxDatabase) -> Unit) {
        val (driver, db) = inMemoryDb()
        try {
            block(driver, db)
        } finally {
            driver.close()
        }
    }

    /** A canonical feed `f-new` whose old id `f-old` has no row and is remapped onto it. */
    private fun staleFeed(driver: SqlDriver, db: KeryxDatabase) {
        db.insertFeed("f-new", sortOrder = 5L)
        driver.remap("feed", "f-old", "f-new")
    }

    @Test
    fun getByIdResolvedReadsTheRowAStaleFeedIdBecameInOneStatement() = withDb { driver, db ->
        staleFeed(driver, db)

        assertEquals("f-new", db.feedsQueries.getByIdResolved("f-old").executeAsOne().id)
        assertEquals("f-new", db.feedsQueries.getByIdResolved("f-new").executeAsOne().id)
        assertNull(db.feedsQueries.getByIdResolved("f-unknown").executeAsOneOrNull())
    }

    @Test
    fun softDeleteByStaleFeedIdDeletesTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.softDelete(500L, 500L, 500L, "f-old")
        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals(500L, row.deleted_at)
        assertEquals(500L, row.deleted_updated_at)
        assertNull(db.feedsQueries.getById("f-old").executeAsOneOrNull())
    }

    @Test
    fun stampResubscribedByStaleFeedIdStampsTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.stampResubscribed(700L, "f-old")
        assertEquals(700L, db.feedsQueries.getById("f-new").executeAsOne().deleted_updated_at)
    }

    @Test
    fun updateCustomTitleByStaleFeedIdRenamesTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.updateCustomTitle("Mine", 600L, 600L, "f-old")
        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals("Mine", row.custom_title)
        assertEquals(600L, row.custom_title_updated_at)
    }

    @Test
    fun updateFaviconUrlIfMissingByStaleFeedIdFillsTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.updateFaviconUrlIfMissing("https://fav/i.ico", 600L, "f-old")
        assertEquals("https://fav/i.ico", db.feedsQueries.getById("f-new").executeAsOne().favicon_url)
    }

    @Test
    fun updateContentByStaleFeedIdUpdatesTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.updateContent("https://site", "New title", "Desc", 600L, "f-old")
        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals("https://site", row.site_url)
        assertEquals("New title", row.title)
        assertEquals("Desc", row.description)
    }

    @Test
    fun updateUrlByStaleFeedIdUpdatesTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.updateUrl("https://moved/feed", 600L, "f-old")
        assertEquals("https://moved/feed", db.feedsQueries.getById("f-new").executeAsOne().url)
    }

    @Test
    fun updateSortOrderByStaleFeedIdReordersTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.feedsQueries.updateSortOrder(9L, 600L, 600L, "f-old")
        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals(9L, row.sort_order)
        assertEquals(600L, row.sort_order_updated_at)
    }

    /** Both statements of the move resolve the feed: the feeds row and the raw assignment row. */
    @Test
    fun updateFolderByStaleFeedIdWritesBothTheFeedsRowAndTheRawRowOfTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.insertFolder("d1", "Work")

        db.feedsQueries.updateFolder(folderId = "d1", updatedAt = 600L, folderUpdatedAt = 600L, id = "f-old")

        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals("d1", row.folder_id)
        assertEquals(600L, row.folder_updated_at)
        val raw = driver.rawFolder("f-new")
        assertEquals("d1" to 600L, raw)
        assertNull(driver.rawFolder("f-old"))
    }

    @Test
    fun updateFolderAndSortOrderByStaleFeedIdWritesBothRowsOfTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.insertFolder("d1", "Work")

        db.feedsQueries.updateFolderAndSortOrder(
            folderId = "d1", sortOrder = 7L, updatedAt = 600L, sortOrderUpdatedAt = 600L, folderUpdatedAt = 600L, id = "f-old",
        )

        val row = db.feedsQueries.getById("f-new").executeAsOne()
        assertEquals("d1", row.folder_id)
        assertEquals(7L, row.sort_order)
        assertEquals("d1" to 600L, driver.rawFolder("f-new"))
    }

    private fun SqlDriver.rawFolder(feedId: String): Pair<String?, Long?>? =
        executeQuery(
            null, "SELECT folder_id, folder_updated_at FROM ks_feed_folder_raw WHERE feed_id = ?", { c ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (c.next().value) c.getString(0) to c.getLong(1) else null,
                )
            }, 1,
        ) { bindString(0, feedId) }.value

    @Test
    fun fetchStateWritesByStaleFeedIdLandOnTheCanonicalFeedWithoutFailingTheForeignKey() = withDb { driver, db ->
        staleFeed(driver, db)
        val q = db.feed_fetch_stateQueries

        q.upsertValidators("f-old", "etag-1", "lm-1")
        q.incrementErrorCount("f-old", "boom")
        q.incrementErrorCount("f-old", "boom2")
        val state = q.getByFeedId("f-new").executeAsOne()
        assertEquals("etag-1", state.etag)
        assertEquals(2L, state.error_count)
        assertNull(q.getByFeedId("f-old").executeAsOneOrNull())

        q.resetErrorCount("f-old")
        val reset = q.getByFeedId("f-new").executeAsOne()
        assertEquals(0L, reset.error_count)
        assertNull(reset.last_error)

        q.markGone("f-old", "gone")
        assertEquals("gone", q.getByFeedId("f-new").executeAsOne().last_error)
    }

    @Test
    fun feedTagUpsertByStaleFeedIdLinksTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)
        db.insertTag("t1", "Tag")

        db.insertFeedTag("f-old", "t1")

        val links = db.feed_tagsQueries.watchAllActive().executeAsList().map { it.feed_id to it.tag_id }
        assertEquals(listOf("f-new" to "t1"), links)
    }

    @Test
    fun articleInsertByStaleFeedIdLandsUnderTheCanonicalFeed() = withDb { driver, db ->
        staleFeed(driver, db)

        db.addArticle("a1", "f-old")

        assertEquals("f-new", db.articlesQueries.getById("a1").executeAsOne().feed_id)
    }

    @Test
    fun insertAndInsertIfAbsentUnderStaleFeedIdMergeIntoTheCanonicalFeedsExistingGuidRow() = withDb { driver, db ->
        staleFeed(driver, db)
        db.addArticle("a-canonical", "f-new", guid = "g1")

        // A refresh that fetched under the old id writes the same (feed, guid) again, under a fresh article id.
        db.articlesQueries.insert(
            id = "a-other", feed_id = "f-old", guid = "g1", url = "https://x", title = "Updated",
            summary = null, content = null, author = null, published_at = null,
            thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 50L, created_at = 0L,
        )
        db.articlesQueries.insertIfAbsent(
            id = "a-other2", feed_id = "f-old", guid = "g1", url = "https://y", title = "Ignored",
            summary = null, content = null, author = null, published_at = null,
            thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 60L, created_at = 0L,
        )

        val all = db.articlesQueries.getByFeedAndGuid("f-new", "g1").executeAsList()
        assertEquals(1, all.size, "no duplicate (feed, guid) row")
        assertEquals("a-canonical", all.single().id)
        assertEquals("Updated", all.single().title, "insert updated the existing row, insertIfAbsent left it")
        assertNull(db.articlesQueries.getById("a-other").executeAsOneOrNull())
        assertNull(db.articlesQueries.getById("a-other2").executeAsOneOrNull())
        assertTrue(db.articlesQueries.getByFeedAndGuid("f-old", "g1").executeAsList().isEmpty())
    }

    @Test
    fun updateReadStatusByStaleArticleIdMarksTheCanonicalArticle() = withDb { driver, db ->
        db.insertFeed("f1")
        db.addArticle("a-new", "f1")
        driver.remap("article", "a-old", "a-new")

        db.articlesQueries.updateReadStatus(1L, 500L, 500L, "a-old")

        val row = db.articlesQueries.getById("a-new").executeAsOne()
        assertEquals(1L, row.is_read)
        assertEquals(500L, row.read_at)
    }

    @Test
    fun updateStarStatusByStaleArticleIdStarsTheCanonicalArticle() = withDb { driver, db ->
        db.insertFeed("f1")
        db.addArticle("a-new", "f1")
        driver.remap("article", "a-old", "a-new")

        db.articlesQueries.updateStarStatus(1L, 500L, 500L, "a-old")

        val row = db.articlesQueries.getById("a-new").executeAsOne()
        assertEquals(1L, row.is_starred)
        assertEquals(500L, row.starred_at)
    }

    @Test
    fun updateReadStatusByIdsWithAMixOfStaleAndLiveIdsMarksAllOfThem() = withDb { driver, db ->
        db.insertFeed("f1")
        db.addArticle("a-live", "f1")
        db.addArticle("a-new", "f1")
        db.addArticle("a-untouched", "f1")
        driver.remap("article", "a-old", "a-new")

        db.articlesQueries.updateReadStatusByIds(500L, 500L, listOf("a-live", "a-old"))

        assertEquals(1L, db.articlesQueries.getById("a-live").executeAsOne().is_read)
        assertEquals(1L, db.articlesQueries.getById("a-new").executeAsOne().is_read)
        assertEquals(0L, db.articlesQueries.getById("a-untouched").executeAsOne().is_read)
    }

    @Test
    fun markAllReadByFeedWithAStaleFeedIdMarksTheCanonicalFeedsArticles() = withDb { driver, db ->
        staleFeed(driver, db)
        db.insertFeed("f-other")
        db.addArticle("a1", "f-new")
        db.addArticle("a2", "f-other")

        db.articlesQueries.markAllReadByFeed(500L, 500L, "f-old")

        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_read)
    }

    @Test
    fun watchByFeedUnderAStaleFeedIdListsTheCanonicalFeedsArticles() = withDb { driver, db ->
        staleFeed(driver, db)
        db.insertFeed("f-other")
        db.addArticle("a1", "f-new")
        db.addArticle("a2", "f-other")

        assertEquals(listOf("a1"), db.articlesQueries.watchByFeed("f-old").executeAsList().map { it.id })
    }

    @Test
    fun withNoRemapEntryAnExistingIdIsUsedAsIs() = withDb { _, db ->
        db.insertFeed("f1")
        db.addArticle("a1", "f1")

        db.feedsQueries.updateSortOrder(4L, 600L, 600L, "f1")
        db.feedsQueries.updateCustomTitle("T", 600L, 600L, "f1")
        db.articlesQueries.updateReadStatus(1L, 500L, 500L, "a1")
        db.feed_fetch_stateQueries.upsertValidators("f1", "e", "m")

        assertEquals(4L, db.feedsQueries.getById("f1").executeAsOne().sort_order)
        assertEquals("T", db.feedsQueries.getById("f1").executeAsOne().custom_title)
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals("e", db.feed_fetch_stateQueries.getByFeedId("f1").executeAsOne().etag)
        assertEquals(listOf("a1"), db.articlesQueries.watchByFeed("f1").executeAsList().map { it.id })
        assertFalse(db.feedsQueries.isIdTaken("unused").executeAsOne())
        assertTrue(db.feedsQueries.isIdTaken("f1").executeAsOne())
    }

    /** No row ever carries an old_id in practice; if one does, the live row wins and resolves to itself. */
    @Test
    fun anIdThatExistsAndIsAlsoInTheRemapResolvesToItself() = withDb { driver, db ->
        db.insertFeed("f1")
        db.insertFeed("f2")
        db.addArticle("a1", "f1")
        db.addArticle("a2", "f1")
        driver.remap("feed", "f1", "f2")
        driver.remap("article", "a1", "a2")

        db.feedsQueries.updateSortOrder(8L, 600L, 600L, "f1")
        db.articlesQueries.updateReadStatus(1L, 500L, 500L, "a1")

        assertEquals(8L, db.feedsQueries.getById("f1").executeAsOne().sort_order)
        assertEquals(0L, db.feedsQueries.getById("f2").executeAsOne().sort_order)
        assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
        assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_read)
    }

    @Test
    fun isIdTakenIsTrueForAnIdTheRemapMapsAway() = withDb { driver, db ->
        staleFeed(driver, db)
        assertTrue(db.feedsQueries.isIdTaken("f-old").executeAsOne())
        assertTrue(db.feedsQueries.isIdTaken("f-new").executeAsOne())
    }
}

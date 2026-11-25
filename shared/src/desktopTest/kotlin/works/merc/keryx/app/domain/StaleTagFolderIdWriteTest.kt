package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.rekeyFolderForTest
import works.merc.keryx.app.rekeyTagForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Every repository write that takes a tag or folder id resolves it through `ks_id_remap` in the write
 * statement itself: a write issued with the id a tag or folder had before a merge re-keyed it (an
 * open rename field, a queued feed-list write, a drag) lands on the row it became — it neither
 * matches nothing nor fails a FOREIGN KEY. The re-key itself is simulated as the merge will do it
 * ([rekeyTagForTest] / [rekeyFolderForTest]). Foreign keys are enforced, as in production.
 */
class StaleTagFolderIdWriteTest {

    private class Repos(val tags: TagRepository, val folders: FolderRepository, val feeds: FeedRepository, val articles: ArticleRepository)

    private fun failingClient(): HttpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }

    private fun repos(db: KeryxDatabase, driver: SqlDriver, clock: Clock = Clock { 1_000L }): Repos {
        val articles = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feeds = FeedRepository(
            db, FeedFetcher(failingClient()), FaviconResolver(failingClient()), articles,
            ftsManagerIndexed(driver), SyncScheduler {}, NotificationCenter(), clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
        )
        return Repos(
            TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined),
            FolderRepository(db, feeds, SyncScheduler {}, clock, Dispatchers.Unconfined),
            feeds,
            articles,
        )
    }

    @Test
    fun renamingATagByItsStaleIdRenamesTheRowItBecame() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-old", "Work", sortOrder = 3L, color = "#111111")
            driver.rekeyTagForTest("t-old", "t-new")

            repos(db, driver).tags.updateTag("t-old", "Office", "#222222")

            val row = db.tagsQueries.getById("t-new").executeAsOne()
            assertEquals("Office", row.name)
            assertEquals("#222222", row.color)
            assertEquals(3L, row.sort_order, "a rename leaves the sort order alone")
            assertEquals(1_000L, row.name_updated_at)
            assertNull(db.tagsQueries.getById("t-old").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun deletingATagByItsStaleIdDeletesTheRowItBecame() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-old", "Work")
            driver.rekeyTagForTest("t-old", "t-new")

            repos(db, driver).tags.deleteTag("t-old")

            assertEquals(1_000L, db.tagsQueries.getById("t-new").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    /** Without the resolution this would fail `feed_tags.tag_id`'s FOREIGN KEY (a local write failure). */
    @Test
    fun attachingAndDetachingATagByItsStaleIdAffectsTheRowItBecame() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t-old", "Work")
            db.insertFeedTag("f2", "t-old")
            driver.rekeyTagForTest("t-old", "t-new")
            val repo = repos(db, driver).tags

            repo.setFeedTag("f1", "t-old", attached = true)
            repo.setFeedTag("f2", "t-old", attached = false)

            assertEquals(mapOf("f1" to setOf("t-new")), repo.getFeedTagMap())
        } finally {
            driver.close()
        }
    }

    @Test
    fun renamingAndDeletingAFolderByItsStaleIdAffectTheRowItBecame() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d-old", "Work")
            db.insertFeed("f1", folderId = "d-old")
            driver.rekeyFolderForTest("d-old", "d-new")
            val repo = repos(db, driver).folders

            repo.updateFolder("d-old", "Office")
            assertEquals("Office", db.foldersQueries.getById("d-new").executeAsOne().name)

            repo.deleteFolder("d-old")
            assertEquals(1_000L, db.foldersQueries.getById("d-new").executeAsOne().deleted_at)
            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id, "the folder's feeds moved out with it")
        } finally {
            driver.close()
        }
    }

    @Test
    fun reorderingFoldersByStaleIdsMovesTheRowsTheyBecame() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("a", "A", sortOrder = 0L)
            db.insertFolder("b-old", "B", sortOrder = 1L)
            db.insertFolder("c-old", "C", sortOrder = 2L)
            driver.rekeyFolderForTest("b-old", "b-new")
            driver.rekeyFolderForTest("c-old", "c-new")

            // Drag C (stale id) before B (stale id).
            repos(db, driver).folders.reorderFolders("c-old", "b-old")

            assertEquals(listOf("a", "c-new", "b-new"), db.foldersQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    /** Without the resolution this would fail `feeds.folder_id`'s FOREIGN KEY (a local write failure). */
    @Test
    fun movingAFeedIntoAFolderByItsStaleIdFilesItInTheRowItBecame() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d-old", "Work")
            db.insertFeed("f1", folderId = "d-old", sortOrder = 0L)
            db.insertFeed("f2")
            driver.rekeyFolderForTest("d-old", "d-new")

            // Placed before f1, which a stale group read would not have found.
            repos(db, driver).feeds.moveFeed("f2", "d-old", targetFeedId = "f1")

            assertEquals("d-new", db.feedsQueries.getById("f2").executeAsOne().folder_id)
            assertEquals(
                listOf("d-new"),
                db.sync_bookkeepingQueries.feedFolderRawAll().executeAsList().filter { it.feed_id == "f2" }.map { it.folder_id },
            )
            assertEquals(listOf("f2", "f1"), db.feedsQueries.getByFolder("d-new").executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun markingATagOrFolderReadByItsStaleIdMarksTheListItBecame() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-old", "Tagged")
            db.insertFolder("d-old", "Filed")
            db.insertFeed("f1")
            db.insertFeed("f2", folderId = "d-old")
            db.insertFeedTag("f1", "t-old")
            val articles = repos(db, driver).articles
            insertUnreadArticle(db, "f1", "a1")
            insertUnreadArticle(db, "f2", "a2")
            driver.rekeyTagForTest("t-old", "t-new")
            driver.rekeyFolderForTest("d-old", "d-new")

            articles.markAllAsRead(ArticleFilter.Tag("t-old"))
            articles.markAllAsRead(ArticleFilter.Folder("d-old"))

            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        } finally {
            driver.close()
        }
    }

    /**
     * A new tag or folder never takes an id `ks_id_remap` maps elsewhere — even its own name-derived
     * one — so a write resolving a stale id can never be diverted onto a newly created row.
     */
    @Test
    fun creationNeverReusesARemappedId() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val derivedTag = IdGenerator.tagId("Work")
            val derivedFolder = IdGenerator.folderId("Work")
            db.insertTag(derivedTag, "Work")
            db.insertFolder(derivedFolder, "Work")
            driver.rekeyTagForTest(derivedTag, "t-other")
            driver.rekeyFolderForTest(derivedFolder, "d-other")
            // Renamed away, so "Work" is free to be created again.
            val r = repos(db, driver)
            r.tags.updateTag("t-other", "Elsewhere", null)
            r.folders.updateFolder("d-other", "Elsewhere")

            val tagId = r.tags.createTag("Work")
            val folderId = r.folders.createFolder("Work")

            assertNotEquals(derivedTag, tagId)
            assertNotEquals(derivedFolder, folderId)
            assertEquals("Work", db.tagsQueries.getById(tagId).executeAsOne().name)
            assertEquals("Elsewhere", db.tagsQueries.getById("t-other").executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    /** With nothing re-keyed, an id resolves to itself and an unknown id still matches nothing. */
    @Test
    fun withoutAReKeyWritesActOnTheIdAsGiven() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Work")
            val repo = repos(db, driver).tags

            repo.updateTag("t1", "Office", null)
            repo.updateTag("missing", "Nothing", null)

            assertEquals(listOf("Office"), db.tagsQueries.watchAll().executeAsList().map { it.name })
            assertNull(db.tagsQueries.getById("missing").executeAsOneOrNull())
            assertEquals("t1", db.tagsQueries.resolveId("t1").executeAsOne())
            assertEquals("missing", db.tagsQueries.resolveId("missing").executeAsOne())
        } finally {
            driver.close()
        }
    }

    private fun insertUnreadArticle(db: KeryxDatabase, feedId: String, id: String) {
        db.articlesQueries.insert(
            id = id, feed_id = feedId, guid = id, url = "https://a/$id", title = "T", summary = null,
            content = "body", author = null, published_at = null, thumbnail_url = null, is_read = 0,
            read_at = null, is_starred = 0, starred_at = null, cached_at = 0L, search_text = "body",
            updated_at = 0, created_at = 0,
        )
    }
}

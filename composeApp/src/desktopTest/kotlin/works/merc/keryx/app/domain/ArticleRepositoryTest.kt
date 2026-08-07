package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.stampArticleDeleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [SyncScheduler] fake that counts invocations. */
private class CountingSyncScheduler : SyncScheduler {
    var callCount = 0
        private set

    override fun scheduleSync() {
        callCount++
    }
}

/** Inserts an article directly (bypassing the repository) for repository query tests. */
private fun KeryxDatabase.insertArticle(
    id: String,
    feedId: String,
    title: String = "Title $id",
    content: String? = null,
    isRead: Long = 0L,
    isStarred: Long = 0L,
    publishedAt: Long? = null,
    cachedAt: Long = 0L,
) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = publishedAt,
        thumbnail_url = null, is_read = isRead, read_at = null, is_starred = isStarred, starred_at = null,
        cached_at = cachedAt, search_text = content ?: "", updated_at = 0L, created_at = 0L,
    )
}

class ArticleRepositoryTest {
    private fun newRepo(
        db: KeryxDatabase,
        driver: app.cash.sqldelight.db.SqlDriver,
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 0L },
    ) = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)

    /**
     * The five list queries are mapped positionally with `::ArticleListRow`, so a reordered SELECT
     * column list still compiles — `title` and `url` are both String, and so are `id`/`feed_id`.
     * Every projected column therefore gets a distinguishable value here, checked field by field.
     * This is the only guard against a silent column swap in `articles.sq`.
     */
    @Test
    fun articleListRowMapsEveryProjectedColumnToItsOwnField() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("feed-id-1")
            db.articlesQueries.insert(
                id = "article-id-1", feed_id = "feed-id-1", guid = "guid-1",
                url = "https://example.com/url-1", title = "Title 1",
                summary = "summary", content = "content", author = "author",
                published_at = 111L, thumbnail_url = null, is_read = 1L, read_at = 222L,
                is_starred = 1L, starred_at = 333L, cached_at = 444L, search_text = "search",
                updated_at = 555L, created_at = 666L,
            )

            val row = newRepo(db, driver).watchArticles(ArticleFilter.All).first().single()

            assertEquals("article-id-1", row.id)
            assertEquals("feed-id-1", row.feed_id)
            assertEquals("Title 1", row.title)
            assertEquals("https://example.com/url-1", row.url)
            assertEquals(111L, row.published_at)
            assertEquals(666L, row.created_at)
            assertEquals(1L, row.is_read)
            assertEquals(1L, row.is_starred)
        } finally {
            driver.close()
        }
    }

    /**
     * The list projection deliberately omits the body columns; the reader loads them per selected
     * article instead. Pins the split so neither side drifts back.
     */
    @Test
    fun theListRowCarriesNoBodyButGetArticleByIdStillDoes() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", content = "<p>the whole article body</p>")
            val repo = newRepo(db, driver)

            val row = repo.watchArticles(ArticleFilter.All).first().single()
            val full = repo.getArticleById(row.id)

            assertEquals("<p>the whole article body</p>", full?.content)
            assertEquals("<p>the whole article body</p>", full?.search_text)
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchArticlesAllReturnsArticlesFromNonDeletedFeedsOnly() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2", deletedAt = 5L)
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")

            val repo = newRepo(db, driver)
            val result = repo.watchArticles(ArticleFilter.All).first()

            assertEquals(listOf("a1"), result.map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchArticlesStarredReturnsOnlyStarred() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", isStarred = 1L)
            db.insertArticle("a2", "f1", isStarred = 0L)

            val repo = newRepo(db, driver)
            val result = repo.watchArticles(ArticleFilter.Starred).first()

            assertEquals(listOf("a1"), result.map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchArticlesFeedReturnsOnlyThatFeed() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")

            val repo = newRepo(db, driver)
            val result = repo.watchArticles(ArticleFilter.Feed("f1")).first()

            assertEquals(listOf("a1"), result.map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchArticlesTagReturnsOnlyArticlesFromTaggedFeeds() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t1", "Tag1")
            db.insertFeedTag("f1", "t1")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")

            val repo = newRepo(db, driver)
            val result = repo.watchArticles(ArticleFilter.Tag("t1")).first()

            assertEquals(listOf("a1"), result.map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchUnreadAndStarredCountsReflectStateChanges() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f1")

            val repo = newRepo(db, driver)
            assertEquals(2L, repo.watchUnreadCount().first())
            assertEquals(0L, repo.watchStarredUnreadCount().first())

            repo.markAsRead("a1")
            assertEquals(1L, repo.watchUnreadCount().first())
            assertEquals(0L, repo.watchStarredUnreadCount().first())

            repo.setStarred("a2", true)
            assertEquals(1L, repo.watchStarredUnreadCount().first())
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchUnreadCountsByFeedGroupsAcrossFeeds() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertArticle("a1", "f1", isRead = 0L)
            db.insertArticle("a2", "f1", isRead = 0L)
            db.insertArticle("a3", "f2", isRead = 0L)
            db.insertArticle("a4", "f2", isRead = 1L)

            val repo = newRepo(db, driver)
            val counts = repo.watchUnreadCountsByFeed().first()

            assertEquals(mapOf("f1" to 2L, "f2" to 1L), counts)
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchUnreadCountsByTagGroupsAcrossTags() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t1", "Tag1")
            db.insertTag("t2", "Tag2")
            db.insertFeedTag("f1", "t1")
            db.insertFeedTag("f2", "t2")
            db.insertArticle("a1", "f1", isRead = 0L)
            db.insertArticle("a2", "f1", isRead = 0L)
            db.insertArticle("a3", "f2", isRead = 0L)

            val repo = newRepo(db, driver)
            val counts = repo.watchUnreadCountsByTag().first()

            assertEquals(mapOf("t1" to 2L, "t2" to 1L), counts)
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchUnreadCountsByFolderGroupsAcrossFolders() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Folder1")
            db.insertFolder("d2", "Folder2")
            db.insertFeed("f1", folderId = "d1")
            db.insertFeed("f2", folderId = "d2")
            db.insertArticle("a1", "f1", isRead = 0L)
            db.insertArticle("a2", "f1", isRead = 0L)
            db.insertArticle("a3", "f2", isRead = 0L)

            val repo = newRepo(db, driver)
            val counts = repo.watchUnreadCountsByFolder().first()

            assertEquals(mapOf("d1" to 2L, "d2" to 1L), counts)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getArticleByIdFoundAndNotFound() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1")

            val repo = newRepo(db, driver)
            assertEquals("a1", repo.getArticleById("a1")?.id)
            assertNull(repo.getArticleById("missing"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun markAsUnreadRoundTripsAndSchedulesSync() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", isRead = 1L)
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markAsUnread("a1")

            assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun setStarredRoundTripsAndSchedulesSync() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1")
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.setStarred("a1", true)
            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_starred)
            assertEquals(1, scheduler.callCount)

            repo.setStarred("a1", false)
            assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_starred)
            assertEquals(2, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markAllAsReadAllMarksEveryUnreadArticle() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markAllAsRead(ArticleFilter.All)

            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markAllAsReadFeedOnlyMarksThatFeed() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")
            val repo = newRepo(db, driver)

            repo.markAllAsRead(ArticleFilter.Feed("f1"))

            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markAllAsReadTagOnlyMarksTaggedFeeds() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t1", "Tag1")
            db.insertFeedTag("f1", "t1")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f2")
            val repo = newRepo(db, driver)

            repo.markAllAsRead(ArticleFilter.Tag("t1"))

            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(0L, db.articlesQueries.getById("a2").executeAsOne().is_read)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markAllAsReadStarredIsNoOp() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", isRead = 0L)
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markAllAsRead(ArticleFilter.Starred)

            assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(0, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markArticlesAsReadMarksOnlyGivenIdsAndSchedulesSyncOnce() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1")
            db.insertArticle("a2", "f1")
            db.insertArticle("a3", "f1")
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markArticlesAsRead(listOf("a1", "a2"))

            assertEquals(1L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a2").executeAsOne().is_read)
            assertEquals(0L, db.articlesQueries.getById("a3").executeAsOne().is_read)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markArticlesAsReadEmptyListDoesNothing() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1")
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markArticlesAsRead(emptyList())

            assertEquals(0L, db.articlesQueries.getById("a1").executeAsOne().is_read)
            assertEquals(0, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun markArticlesAsReadMarksEveryIdAcrossTheChunkBoundary() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val n = 1000 // exceeds the 900 id-chunk size, so the batched update spans two chunks
            db.transaction { for (i in 0 until n) db.insertArticle("a$i", "f1") }
            db.insertArticle("keepUnread", "f1")
            val scheduler = CountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.markArticlesAsRead((0 until n).map { "a$it" })

            // Every requested id is read, including ids on both sides of the chunk boundary...
            assertEquals(1L, db.articlesQueries.getById("a0").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a899").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a900").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.getById("a999").executeAsOne().is_read)
            // ...and only those: the unlisted article stays unread, sync scheduled once for the batch.
            assertEquals(0L, db.articlesQueries.getById("keepUnread").executeAsOne().is_read)
            assertEquals(1L, db.articlesQueries.watchUnreadCount().executeAsOne())
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun searchReturnsMatchingArticlesAfterRebuild() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", title = "Kotlin Multiplatform", content = "cross platform apps")
            db.insertArticle("a2", "f1", title = "Unrelated", content = "some other content")
            ftsManagerIndexed(driver)

            val repo = newRepo(db, driver)
            val results = repo.search("Kotlin")

            assertEquals(listOf("a1"), results.map { it.article.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun searchResultCarriesHighlightMarkupForTheMatchedArticle() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", title = "Kotlin Multiplatform", content = "cross platform apps")
            ftsManagerIndexed(driver)

            val repo = newRepo(db, driver)
            val result = repo.search("Kotlin").single()

            assertEquals("a1", result.article.id)
            // Markup wraps the match; stripping the sentinels restores the article's real title.
            assertEquals(
                "Kotlin Multiplatform",
                result.titleMarked.filter { it != FtsSearch.MARK_START && it != FtsSearch.MARK_END },
            )
            val marked = result.titleMarked.substringAfter(FtsSearch.MARK_START).substringBefore(FtsSearch.MARK_END)
            assertTrue(marked.lowercase().contains("kotlin"), "titleMarked=${result.titleMarked}")
        } finally {
            driver.close()
        }
    }

    @Test
    fun searchPreservesFtsRankOrderAndMarkupFromFtsSearch() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", title = "Kotlin basics", content = "kotlin kotlin kotlin coroutines")
            db.insertArticle("a2", "f1", title = "Kotlin advanced", content = "kotlin flows")
            db.insertArticle("a3", "f1", title = "Kotlin Multiplatform", content = "kotlin desktop and mobile kotlin")
            db.insertArticle("a4", "f1", title = "Unrelated", content = "nothing here")
            ftsManagerIndexed(driver)

            val repo = newRepo(db, driver)
            val fromFts = FtsSearch(driver).search("kotlin")
            val results = repo.search("kotlin")

            // The repository must return exactly FtsSearch's hits, in the same rank order, with the
            // same title markup — only the full row is additionally attached (batched fetch must not
            // change order or drop the markup).
            assertEquals(fromFts.map { it.id }, results.map { it.article.id })
            assertEquals(fromFts.map { it.titleMarked }, results.map { it.titleMarked })
        } finally {
            driver.close()
        }
    }

    @Test
    fun searchReturnsAllHitsAcrossTheIdChunkBoundary() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // More hits than the id-fetch chunk size (900), so the batched by-id fetch spans several
            // chunks; every hit must still come back, in rank order.
            db.transaction {
                for (i in 0 until 1000) {
                    db.insertArticle("a$i", "f1", content = "kotlin document $i")
                }
            }
            ftsManagerIndexed(driver)

            val repo = newRepo(db, driver)
            val fromFts = FtsSearch(driver).search("kotlin")
            val results = repo.search("kotlin")

            assertEquals(1000, results.size)
            assertEquals(fromFts.map { it.id }, results.map { it.article.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteExpiredArticlesNullRetentionKeepsEverything() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", cachedAt = 0L)
            val repo = newRepo(db, driver, clock = Clock { 1_000_000_000L })

            repo.deleteExpiredArticles(null)

            assertEquals(1, db.articlesQueries.getById("a1").executeAsOneOrNull()?.let { 1 } ?: 0)
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteExpiredArticlesBoundaryIsStrictlyLessThanCutoff() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val oneDayMs = 24 * 60 * 60 * 1000L
            val now = 10 * oneDayMs
            val cutoff = now - 1 * oneDayMs // retentionDays = 1

            // 10 "protected" recent articles (kept regardless of cached_at, since the query
            // always retains the latest 10 per feed by published/created/id order).
            for (i in 0 until 10) {
                db.insertArticle("recent$i", "f1", publishedAt = 1000L + i, cachedAt = now)
            }
            // Outside the protected top-10 window (older published_at).
            db.insertArticle("atCutoff", "f1", publishedAt = 1L, cachedAt = cutoff)
            db.insertArticle("beforeCutoff", "f1", publishedAt = 0L, cachedAt = cutoff - 1)

            val repo = newRepo(db, driver, clock = Clock { now })
            repo.deleteExpiredArticles(retentionDays = 1)

            // cached_at == cutoff survives (comparison is strictly `<`) and stays visible.
            assertNull(db.articlesQueries.getById("atCutoff").executeAsOne().deleted_at)
            // cached_at < cutoff is soft-deleted: still physically present (so the deletion can
            // propagate via sync) but tombstoned and hidden from the list. All three cleanup
            // timestamps are stamped to `now` — deleted_updated_at in particular drives deletion
            // last-write-wins ordering in the sync merge, so assert it, not just visibility.
            val deleted = db.articlesQueries.getById("beforeCutoff").executeAsOne()
            assertEquals(now, deleted.deleted_at)
            assertEquals(now, deleted.deleted_updated_at)
            assertEquals(now, deleted.updated_at)
            val visibleIds = db.articlesQueries.watchAll().executeAsList().map { it.id }
            assertTrue("beforeCutoff" !in visibleIds)
            assertTrue("atCutoff" in visibleIds)
            for (i in 0 until 10) {
                assertNull(db.articlesQueries.getById("recent$i").executeAsOne().deleted_at)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteExpiredArticlesNeverDeletesStarredArticles() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val oneDayMs = 24 * 60 * 60 * 1000L
            val now = 10 * oneDayMs
            val cutoff = now - 1 * oneDayMs

            for (i in 0 until 10) {
                db.insertArticle("recent$i", "f1", publishedAt = 1000L + i, cachedAt = now)
            }
            db.insertArticle("starredOld", "f1", publishedAt = 0L, cachedAt = cutoff - 1, isStarred = 1L)

            val repo = newRepo(db, driver, clock = Clock { now })
            repo.deleteExpiredArticles(retentionDays = 1)

            // Starred articles are never soft-deleted (still present and not tombstoned).
            assertNull(db.articlesQueries.getById("starredOld").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteExpiredArticlesProtectsLatest10AliveNotTombstones() {
        // The "keep latest 10 per feed" protection must count only alive rows. Tombstones now
        // persist indefinitely (soft delete), so if the protection subquery ranked them too, a
        // tombstone with a recent published_at would occupy a protected slot and push a truly-alive
        // article below the top-10, dropping live retention under 10.
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val oneDayMs = 24 * 60 * 60 * 1000L
            val now = 10 * oneDayMs
            val cutoff = now - 1 * oneDayMs // retentionDays = 1

            // 2 tombstoned articles with the newest published_at (e.g. deletions propagated via
            // sync from another device). They must NOT consume protected slots.
            for (i in 0 until 2) {
                db.insertArticle("dead$i", "f1", publishedAt = 2000L + i, cachedAt = now)
                driver.stampArticleDeleted("dead$i", deletedAt = 100L)
            }
            // 10 alive, expired-cached_at articles with older published_at — all should be protected.
            for (i in 0 until 10) {
                db.insertArticle("alive$i", "f1", publishedAt = 1000L + i, cachedAt = cutoff - 1)
            }

            val repo = newRepo(db, driver, clock = Clock { now })
            repo.deleteExpiredArticles(retentionDays = 1)

            // All 10 alive articles survive. With the old (tombstone-blind) subquery, the 2
            // tombstones would occupy top-10 slots and the 2 oldest alive rows would be deleted.
            for (i in 0 until 10) {
                assertNull(db.articlesQueries.getById("alive$i").executeAsOne().deleted_at)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun upsertDoesNotReviveDeletedArticle() {
        // A feed refresh re-inserts the same (feed_id, guid) via `insert ... ON CONFLICT`. That path
        // must preserve an existing tombstone, otherwise refresh would resurrect a deleted article.
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", content = "old")
            driver.stampArticleDeleted("a1", deletedAt = 100)

            // Re-fetch the same article (same feed_id + guid = "a1"), as a refresh would.
            db.articlesQueries.insert(
                id = "a1", feed_id = "f1", guid = "a1", url = "https://article/a1", title = "T",
                summary = null, content = "new", author = null, published_at = null, thumbnail_url = null,
                is_read = 0, read_at = null, is_starred = 0, starred_at = null, cached_at = 500,
                search_text = "new", updated_at = 500, created_at = 0,
            )

            val row = db.articlesQueries.getByFeedAndGuid("f1", "a1").executeAsOne()
            assertEquals(100L, row.deleted_at)
            assertEquals("new", row.content) // content did refresh...
            assertTrue(db.articlesQueries.watchAll().executeAsList().isEmpty()) // ...but it stays hidden
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchArticleChangesReflectsArticleCount() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")

            val repo = newRepo(db, driver)
            assertEquals(0L, repo.watchArticleChanges().first())

            db.insertArticle("a1", "f1")
            assertEquals(1L, repo.watchArticleChanges().first())

            db.insertArticle("a2", "f1")
            assertEquals(2L, repo.watchArticleChanges().first())
        } finally {
            driver.close()
        }
    }
}

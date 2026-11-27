package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.stampArticleDeleted
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An article is visible while it is not deleted **or** is starred ([articleVisibleSql]): the star and the
 * deletion are two independent merge groups, so a row can hold both, and the star keeps it. Every
 * `articles.sq` query that lists, counts or acts on articles as the user sees them must agree, or a list,
 * its unread counts and its mark-all-read would disagree about the same article.
 */
class ArticleVisibilityQueriesTest {

    /**
     * Feed f1 (in folder d1, tagged t1) with three unread articles: `starredDeleted` (starred, and deleted
     * by a cleanup elsewhere), `deleted` (deleted, not starred) and `alive`.
     */
    private fun setup(): Pair<SqlDriver, KeryxDatabase> {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder")
        db.insertTag("t1", "Tag")
        db.insertFeed("f1", folderId = "d1")
        db.insertFeedTag("f1", "t1")
        insert(db, "starredDeleted", starred = true, publishedAt = 3)
        insert(db, "deleted", starred = false, publishedAt = 2)
        insert(db, "alive", starred = false, publishedAt = 1)
        driver.stampArticleDeleted("starredDeleted", deletedAt = 100)
        driver.stampArticleDeleted("deleted", deletedAt = 100)
        return driver to db
    }

    private fun insert(db: KeryxDatabase, id: String, starred: Boolean, publishedAt: Long, cachedAt: Long = 0L) {
        db.articlesQueries.insert(
            id = id, feed_id = "f1", guid = id, url = "https://article/$id", title = "Title $id",
            summary = null, content = null, author = null, published_at = publishedAt,
            thumbnail_url = null, is_read = 0, read_at = null,
            is_starred = if (starred) 1L else 0L, starred_at = if (starred) 50L else null,
            cached_at = cachedAt, search_text = "", updated_at = 0L, created_at = 0L,
        )
    }

    private fun readIds(db: KeryxDatabase): Set<String> =
        listOf("starredDeleted", "deleted", "alive").filter { db.articlesQueries.getById(it).executeAsOne().is_read == 1L }.toSet()

    @Test
    fun aStarredDeletedArticleIsListedInEveryListAndTheUnstarredOneIsNot() {
        val (driver, db) = setup()
        try {
            val q = db.articlesQueries
            val visible = listOf("starredDeleted", "alive")
            assertEquals(visible, q.watchAll().executeAsList().map { it.id })
            assertEquals(listOf("starredDeleted"), q.watchStarred().executeAsList().map { it.id })
            assertEquals(visible, q.watchByFeed("f1").executeAsList().map { it.id })
            assertEquals(visible, q.watchByTag("t1").executeAsList().map { it.id })
            assertEquals(visible, q.watchByFolder("d1").executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun aStarredDeletedArticleIsCountedInEveryUnreadCount() {
        val (driver, db) = setup()
        try {
            val q = db.articlesQueries
            assertEquals(2L, q.watchUnreadCount().executeAsOne())
            assertEquals(1L, q.watchStarredUnreadCount().executeAsOne())
            assertEquals(listOf("f1" to 2L), q.watchUnreadCountsByFeed().executeAsList().map { it.feed_id to it.cnt })
            assertEquals(listOf("t1" to 2L), q.watchUnreadCountsByTag().executeAsList().map { it.tag_id to it.cnt })
            assertEquals(listOf("d1" to 2L), q.watchUnreadCountsByFolder().executeAsList().map { it.folder_id to it.cnt })
        } finally {
            driver.close()
        }
    }

    @Test
    fun aliveFlagsInAndIdsByFeedsReturnTheStarredDeletedArticle() {
        val (driver, db) = setup()
        try {
            val q = db.articlesQueries
            assertEquals(
                setOf("starredDeleted", "alive"),
                q.aliveFlagsIn(listOf("starredDeleted", "deleted", "alive")).executeAsList().map { it.id }.toSet(),
            )
            assertEquals(setOf("starredDeleted", "alive"), q.idsByFeeds(listOf("f1")).executeAsList().toSet())
        } finally {
            driver.close()
        }
    }

    @Test
    fun everyMarkAllReadAndTheByIdsMarkReadReachTheStarredDeletedArticleOnly() {
        val writes: List<Pair<String, (KeryxDatabase) -> Unit>> = listOf(
            "markAllReadAll" to { db -> db.articlesQueries.markAllReadAll(10L, 10L) },
            "markAllReadByFeed" to { db -> db.articlesQueries.markAllReadByFeed(10L, 10L, "f1") },
            "markAllReadByTag" to { db -> db.articlesQueries.markAllReadByTag(10L, 10L, "t1") },
            "markAllReadByFolder" to { db -> db.articlesQueries.markAllReadByFolder(10L, 10L, "d1") },
            "updateReadStatusByIds" to { db ->
                db.articlesQueries.updateReadStatusByIds(10L, 10L, listOf("starredDeleted", "deleted", "alive"))
            },
        )
        for ((name, write) in writes) {
            val (driver, db) = setup()
            try {
                write(db)
                assertEquals(setOf("starredDeleted", "alive"), readIds(db), name)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun theKotlinPredicateMatchesTheSqlOne() {
        val (driver, db) = setup()
        try {
            val q = db.articlesQueries
            assertTrue(q.getById("starredDeleted").executeAsOne().isVisible)
            assertFalse(q.getById("deleted").executeAsOne().isVisible)
            assertTrue(q.getById("alive").executeAsOne().isVisible)
            assertEquals("(a.deleted_at IS NULL OR a.is_starred = 1)", articleVisibleSql("a"))
            assertEquals("(deleted_at IS NULL OR is_starred = 1)", articleVisibleSql())
        } finally {
            driver.close()
        }
    }

    @Test
    fun anUnstarredDeletedArticleStaysVisibleUntilTheNextCacheCleanupDeletesItAgain() {
        val day = 24 * 60 * 60 * 1000L
        val now = 10 * day
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // Ten newer articles, so the old one is not among the latest ten the cleanup keeps.
            for (i in 0 until 10) insert(db, "recent$i", starred = false, publishedAt = 1000L + i, cachedAt = now)
            insert(db, "old", starred = true, publishedAt = 0L, cachedAt = 0L)
            driver.stampArticleDeleted("old", deletedAt = 100)
            val repo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { now }, Dispatchers.Unconfined)
            assertTrue("old" in db.articlesQueries.watchAll().executeAsList().map { it.id })

            repo.setStarred("old", false)
            val unstarred = db.articlesQueries.getById("old").executeAsOne()
            assertEquals(0L, unstarred.is_starred)
            assertNull(unstarred.deleted_at, "an unstar of a deleted row stamps an undelete")
            assertEquals(now, unstarred.deleted_updated_at)
            assertTrue("old" in db.articlesQueries.watchAll().executeAsList().map { it.id })

            repo.deleteExpiredArticles(retentionDays = 1)
            assertNotNull(db.articlesQueries.getById("old").executeAsOne().deleted_at)
            assertFalse("old" in db.articlesQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    /**
     * Regression guard: a query in `articles.sq` that filters articles on `deleted_at IS NULL` alone would
     * hide a starred article another device's cleanup deleted. Every such filter must be the visibility
     * predicate, except the statements listed in [ALLOWED] (each with the count of bare filters it may keep).
     */
    @Test
    fun articlesSqHasNoArticleFilterOnDeletedAtAloneOutsideTheAllowList() {
        val statements = articlesSqStatements()
        val bare = Regex("""(?<![\w.])(?:a\.)?deleted_at IS NULL""")
        val visibility = Regex("""\((?:a\.)?deleted_at IS NULL OR (?:a\.)?is_starred = 1\)""")
        val offenders = statements.mapNotNull { (name, body) ->
            val count = bare.findAll(visibility.replace(body, "")).count()
            if (count == (ALLOWED[name] ?: 0)) null else "$name: $count bare `deleted_at IS NULL` (allowed ${ALLOWED[name] ?: 0})"
        }
        assertEquals(emptyList(), offenders)
        for (name in REQUIRED) {
            val body = assertNotNull(statements[name], "articles.sq has no statement $name")
            assertTrue(visibility.containsMatchIn(body), "$name does not use the visibility predicate")
        }
    }

    /** `articles.sq`'s labelled statements, comments stripped, by label. */
    private fun articlesSqStatements(): Map<String, String> {
        val path = "src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/articles.sq"
        val file = listOf(File(path), File("shared/$path")).first { it.exists() }
        val result = linkedMapOf<String, String>()
        var name: String? = null
        val body = StringBuilder()
        for (raw in file.readLines()) {
            val line = raw.substringBefore("--")
            val label = Regex("""^(\w+):\s*$""").find(line)
            if (label != null) {
                name?.let { result[it] = body.toString() }
                name = label.groupValues[1]
                body.clear()
            } else if (name != null) {
                body.append(line).append('\n')
            }
        }
        name?.let { result[it] = body.toString() }
        return result
    }

    private companion object {
        /** Statements that may filter on `deleted_at IS NULL` alone, with how many times, and why. */
        val ALLOWED = mapOf(
            // Its `deleted_at IS NULL` is the feeds subquery's (unaliased `feeds.deleted_at`).
            "markAllReadByFolder" to 1,
            // The idempotence guard (never re-stamp a deleted row) and the latest-ten subquery, which
            // counts only rows not deleted — a deletion, not visibility, is what they are about.
            "softDeleteExpired" to 2,
        )

        /** Statements that must use the visibility predicate. */
        val REQUIRED = listOf(
            "watchAll", "watchStarred", "watchByFeed", "watchByTag", "watchByFolder",
            "aliveFlagsIn", "idsByFeeds",
            "watchUnreadCount", "watchStarredUnreadCount", "watchUnreadCountsByFeed", "watchUnreadCountsByTag",
            "watchUnreadCountsByFolder",
            "updateReadStatusByIds", "markAllReadAll", "markAllReadByFeed", "markAllReadByTag", "markAllReadByFolder",
        )
    }
}

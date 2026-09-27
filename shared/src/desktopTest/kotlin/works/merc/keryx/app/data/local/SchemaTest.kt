package works.merc.keryx.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.stampArticleDeleted
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaTest {

    private fun articleColumns(dbPath: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(articles)").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("name")) }
                }
            }
        }

    @Test
    fun schemaVersionIsTwo() {
        // Bumped to 2 by 1.sqm (adds articles.deleted_at / deleted_updated_at). SQLDelight derives
        // the version from the highest migration file (+1).
        assertEquals(2L, KeryxDatabase.Schema.version)
    }

    @Test
    fun freshSchemaHasArticleSoftDeleteColumns() {
        val (file, driver, _) = fileDb()
        driver.close()
        val cols = articleColumns(file.absolutePath)
        assertTrue("deleted_at" in cols)
        assertTrue("deleted_updated_at" in cols)
    }

    @Test
    fun migrationOneToTwoAddsArticleSoftDeleteColumns() {
        val file = File.createTempFile("keryx-migrate-", ".db").apply { deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            // A minimal v1 `articles` table (no soft-delete columns), marked user_version = 1.
            driver.execute(
                null,
                """
                CREATE TABLE articles (
                    id TEXT NOT NULL PRIMARY KEY, feed_id TEXT NOT NULL, guid TEXT NOT NULL,
                    url TEXT NOT NULL, title TEXT NOT NULL, summary TEXT, content TEXT, author TEXT,
                    published_at INTEGER, thumbnail_url TEXT, is_read INTEGER NOT NULL DEFAULT 0,
                    read_at INTEGER, is_starred INTEGER NOT NULL DEFAULT 0, starred_at INTEGER,
                    cached_at INTEGER NOT NULL, search_text TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
                    UNIQUE (feed_id, guid)
                );
                """.trimIndent(),
                0,
            )
            driver.execute(null, "PRAGMA user_version=1;", 0)

            KeryxDatabase.Schema.migrate(driver, 1, 2)

            val cols = articleColumns(file.absolutePath)
            assertTrue("deleted_at" in cols)
            assertTrue("deleted_updated_at" in cols)
        } finally {
            driver.close()
        }
    }

    @Test
    fun schemaSupportsBasicCrud() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            assertEquals(1, db.feedsQueries.watchAll().executeAsList().size)

            db.articlesQueries.insert(
                id = "a1", feed_id = "f1", guid = "g1", url = "u", title = "T",
                summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
                is_read = 0, read_at = null, is_starred = 0, starred_at = null, cached_at = 0,
                search_text = "", updated_at = 0, created_at = 0,
            )
            assertEquals(1L, db.articlesQueries.watchUnreadCount().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun softDeletedArticleIsExcludedFromWatchAll() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.articlesQueries.insert(
                id = "a1", feed_id = "f1", guid = "g1", url = "u", title = "T",
                summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
                is_read = 0, read_at = null, is_starred = 0, starred_at = null, cached_at = 0,
                search_text = "", updated_at = 0, created_at = 0,
            )
            driver.stampArticleDeleted("a1", deletedAt = 100)
            assertTrue(db.articlesQueries.watchAll().executeAsList().isEmpty())
            // Still physically present (soft delete), retrievable by id for internal use.
            assertEquals(100L, db.articlesQueries.getById("a1").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun feedsSortOrderColumnDefaultsToZero() {
        // The base schema defines `feeds.sort_order INTEGER NOT NULL DEFAULT 0`, so a freshly
        // created/inserted row must have sort_order = 0 rather than failing or being NULL.
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            assertEquals(0L, db.feedsQueries.getById("f1").executeAsOne().sort_order)
        } finally {
            driver.close()
        }
    }

    @Test
    fun uniqueFeedGuidUpsertsInsteadOfDuplicating() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            repeat(2) {
                db.articlesQueries.insert(
                    id = "a$it", feed_id = "f1", guid = "same", url = "u", title = "T$it",
                    summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
                    is_read = 0, read_at = null, is_starred = 0, starred_at = null, cached_at = 0,
                    search_text = "", updated_at = 0, created_at = 0,
                )
            }
            assertEquals(1, db.articlesQueries.watchByFeed("f1").executeAsList().size)
        } finally {
            driver.close()
        }
    }
}

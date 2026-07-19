package works.merc.keryx.app.data.local

import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {

    @Test
    fun schemaVersionIsOne() {
        // This is a guard against accidental `.sqm` re-introduction.
        // If migrations are ever added, remove this test (the version
        // will then be pinned by the migration files themselves).
        assertEquals(1L, KeryxDatabase.Schema.version)
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

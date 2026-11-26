package works.merc.keryx.app.domain

import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A feed's `updated_at` is the content group's last-write-wins stamp, and the capture trigger does not
 * watch it: a write that moves it without changing a watched column records nothing to send, so this
 * device would hold a stamp no peer has and a peer's later content change would lose here and win
 * there. `feeds.upsert` and `feeds.updateUrl` therefore move it only together with a real change.
 */
class FeedContentStampTest {

    private fun upsert(db: works.merc.keryx.app.data.local.db.KeryxDatabase, title: String, updatedAt: Long, url: String = "https://ex.com/feed") =
        db.feedsQueries.upsert(
            id = "f1", url = url, site_url = null, title = title, description = null, favicon_url = null,
            custom_title = null, deleted_at = null, updated_at = updatedAt, created_at = 100, sort_order = 0,
        )

    private fun stamp(db: works.merc.keryx.app.data.local.db.KeryxDatabase) =
        db.feedsQueries.getById("f1").executeAsOne().updated_at

    @Test
    fun upsertingAnUnchangedLiveFeedKeepsItsContentStamp() {
        val (driver, db) = inMemoryDb()
        try {
            upsert(db, title = "Feed", updatedAt = 100)
            upsert(db, title = "Feed", updatedAt = 500)
            assertEquals(100L, stamp(db))
        } finally {
            driver.close()
        }
    }

    @Test
    fun upsertingAChangedFeedMovesItsContentStamp() {
        val (driver, db) = inMemoryDb()
        try {
            upsert(db, title = "Feed", updatedAt = 100)
            upsert(db, title = "Renamed by the server", updatedAt = 500)
            assertEquals(500L, stamp(db))
        } finally {
            driver.close()
        }
    }

    @Test
    fun updatingAFeedToTheUrlItAlreadyHasWritesNothing() {
        val (driver, db) = inMemoryDb()
        try {
            upsert(db, title = "Feed", updatedAt = 100)
            db.feedsQueries.updateUrl(url = "https://ex.com/feed", updated_at = 500, id = "f1")
            assertEquals(100L, stamp(db))
            db.feedsQueries.updateUrl(url = "https://ex.com/moved", updated_at = 600, id = "f1")
            assertEquals(600L, stamp(db))
            assertEquals("https://ex.com/moved", db.feedsQueries.getById("f1").executeAsOne().url)
        } finally {
            driver.close()
        }
    }
}

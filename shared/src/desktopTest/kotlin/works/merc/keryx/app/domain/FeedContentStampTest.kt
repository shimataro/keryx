package works.merc.keryx.app.domain

import works.merc.keryx.app.fileDb
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A feed's `updated_at` is the content group's last-write-wins stamp, and the capture trigger does not
 * watch it: a write that moves it without changing a watched column records nothing to send, so this
 * device would hold a stamp no peer has and a peer's later content change would lose here and win
 * there. `feeds.upsert` and `feeds.updateUrl` therefore move it only together with a real change. Every
 * local write also stamps it as MAX(now, previous + 1): a write stamped at or behind a stamp a merge
 * brought in — this device's clock behind the sender's, or a write in the same millisecond — would
 * lose to the merged content on the next merge, and devices that merged the same files would disagree.
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

    @Test
    fun aContentWriteStampedBehindAMergedStampStillReachesThePeer() {
        val (file, driver, db) = fileDb()
        try {
            db.feedsQueries.upsert(
                id = "f1", url = "https://ex.com/feed", site_url = null, title = "Old", description = null,
                favicon_url = null, custom_title = null, deleted_at = null, updated_at = 100, created_at = 100,
                sort_order = 0,
            )
            // A device whose clock runs ahead changed the title at 5_000; this device merged it in.
            val fromPeer = TransportFixtures.transportFile(
                TransportFixtures.deltaName(TransportFixtures.DEV_B, 1),
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "B", updatedAt = 5_000),
            )
            TransportFixtures.merge(file, fromPeer)
            assertEquals(5_000L, stamp(db))
            // This device's refresh sees the title change again at its own, slower clock 4_900.
            db.feedsQueries.updateContent(site_url = null, title = "A2", description = null, updated_at = 4_900, id = "f1")
            val sent = stamp(db)
            assertEquals(5_001L, sent) // MAX(4_900, 5_000 + 1), not the clock's 4_900
            // The peer still holds (title B, 5_000); the delta carries the row as this device now holds it.
            val toPeer = TransportFixtures.transportFile(
                TransportFixtures.deltaName(TransportFixtures.DEV_A, 1),
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "A2", updatedAt = sent),
            )
            val peer = TransportFixtures.localDb(
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "B", updatedAt = 5_000),
            )
            TransportFixtures.merge(peer, toPeer)
            assertEquals("A2", TransportFixtures.rows(peer, "SELECT title FROM feeds WHERE id = 'f1'").single())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aReorderStampedBehindAMergedStampDoesNotDragTheNextContentWriteBelowIt() {
        val (file, driver, db) = fileDb()
        try {
            db.feedsQueries.upsert(
                id = "f1", url = "https://ex.com/feed", site_url = null, title = "B", description = null,
                favicon_url = null, custom_title = null, deleted_at = null, updated_at = 5_000, created_at = 100,
                sort_order = 0,
            )
            // This device's clock runs behind the device the row's stamp came from: a reorder at 4_900
            // must not drag updated_at below 5_000, or the next content write would lose to the peer.
            db.feedsQueries.updateSortOrder(sort_order = 3, updated_at = 4_900, sort_order_updated_at = 4_900, id = "f1")
            assertEquals(5_001L, stamp(db)) // MAX(4_900, 5_000 + 1)
            db.feedsQueries.updateContent(site_url = null, title = "A2", description = null, updated_at = 4_901, id = "f1")
            val sent = stamp(db)
            assertEquals(5_002L, sent)
            val toPeer = TransportFixtures.transportFile(
                TransportFixtures.deltaName(TransportFixtures.DEV_A, 1),
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "A2", updatedAt = sent),
            )
            val peer = TransportFixtures.localDb(
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "B", updatedAt = 5_000),
            )
            TransportFixtures.merge(peer, toPeer)
            assertEquals("A2", TransportFixtures.rows(peer, "SELECT title FROM feeds WHERE id = 'f1'").single())
        } finally {
            driver.close()
        }
    }

    @Test
    fun twoContentWritesWithinOneMillisecondStillOrderStrictly() {
        val (file, driver, db) = fileDb()
        try {
            db.feedsQueries.upsert(
                id = "f1", url = "https://ex.com/feed", site_url = null, title = "One", description = null,
                favicon_url = null, custom_title = null, deleted_at = null, updated_at = 1_000, created_at = 100,
                sort_order = 0,
            )
            db.feedsQueries.updateContent(site_url = null, title = "Two", description = null, updated_at = 1_000, id = "f1")
            assertEquals(1_001L, stamp(db)) // MAX(1_000, 1_000 + 1)
            db.feedsQueries.updateContent(site_url = null, title = "Three", description = null, updated_at = 1_000, id = "f1")
            assertEquals(1_002L, stamp(db))
            // The peer merged the same first write; the second one's strictly greater stamp wins there too,
            // where an equal stamp would fall to the tie rule and could keep the peer's older title.
            val toPeer = TransportFixtures.transportFile(
                TransportFixtures.deltaName(TransportFixtures.DEV_A, 1),
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "Three", updatedAt = 1_002),
            )
            val peer = TransportFixtures.localDb(
                TransportFixtures.feed(id = "f1", url = "https://ex.com/feed", title = "One", updatedAt = 1_000),
            )
            TransportFixtures.merge(peer, toPeer)
            assertEquals("Three", TransportFixtures.rows(peer, "SELECT title FROM feeds WHERE id = 'f1'").single())
        } finally {
            driver.close()
        }
    }
}

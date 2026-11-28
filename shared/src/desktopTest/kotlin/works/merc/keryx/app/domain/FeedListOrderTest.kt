package works.merc.keryx.app.domain

import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The feed list's order is decided by synced values alone — sort_order, then the title (NOCASE, then
 * byte-wise), then the id. Two devices can hold the same feeds in different rowid orders (two feeds
 * subscribed offline in one group take the same sort_order, and identical titles are common), so
 * without the trailing tiebreakers the display order — and the placement math that reads it — would
 * follow each device's own rowid order and diverge.
 */
class FeedListOrderTest {

    private fun KeryxDatabase.insertFeed(id: String, title: String, sortOrder: Long, folderId: String? = null) {
        feedsQueries.upsert(
            id = id, url = "https://feed/$id", site_url = null, title = title, description = null,
            favicon_url = null, custom_title = null, deleted_at = null, updated_at = 100, created_at = 100,
            sort_order = sortOrder,
        )
        if (folderId != null) {
            feedsQueries.updateFolder(folderId = folderId, updatedAt = 100, folderUpdatedAt = 100, id = id)
        }
    }

    @Test
    fun identicalTiedFeedsListIdenticallyWhateverOrderTheirRowsWereInsertedIn() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            // The same two feeds — same title, same sort_order (two offline subscriptions in one
            // group) — inserted in opposite orders, so their rowids differ.
            dbA.insertFeed("f-1", title = "Blog", sortOrder = 0L)
            dbA.insertFeed("f-2", title = "Blog", sortOrder = 0L)
            dbB.insertFeed("f-2", title = "Blog", sortOrder = 0L)
            dbB.insertFeed("f-1", title = "Blog", sortOrder = 0L)

            val orderA = dbA.feedsQueries.watchAll().executeAsList().map { it.id }
            val orderB = dbB.feedsQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("f-1", "f-2"), orderA)
            assertEquals(orderA, orderB)
        } finally {
            driverA.close()
            driverB.close()
        }
    }

    @Test
    fun titlesThatOnlyDifferByCaseStillOrderIdenticallyOnEveryDevice() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            // NOCASE ties "BLOG" and "Blog"; the byte-wise title then orders them the same everywhere.
            dbA.insertFeed("f-upper", title = "BLOG", sortOrder = 0L)
            dbA.insertFeed("f-mixed", title = "Blog", sortOrder = 0L)
            dbB.insertFeed("f-mixed", title = "Blog", sortOrder = 0L)
            dbB.insertFeed("f-upper", title = "BLOG", sortOrder = 0L)

            val orderA = dbA.feedsQueries.watchAll().executeAsList().map { it.id }
            val orderB = dbB.feedsQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("f-upper", "f-mixed"), orderA)
            assertEquals(orderA, orderB)
        } finally {
            driverA.close()
            driverB.close()
        }
    }

    @Test
    fun aFolderGroupAndItsPlacementReadListIdenticallyWhateverOrderTheirRowsWereInsertedIn() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            dbA.insertFolder("d1", "Kotlin", now = 0L)
            dbB.insertFolder("d1", "Kotlin", now = 0L)
            dbA.insertFeed("f-1", title = "Blog", sortOrder = 0L, folderId = "d1")
            dbA.insertFeed("f-2", title = "Blog", sortOrder = 0L, folderId = "d1")
            dbB.insertFeed("f-2", title = "Blog", sortOrder = 0L, folderId = "d1")
            dbB.insertFeed("f-1", title = "Blog", sortOrder = 0L, folderId = "d1")

            val groupA = dbA.feedsQueries.getByFolder("d1").executeAsList().map { it.id }
            val groupB = dbB.feedsQueries.getByFolder("d1").executeAsList().map { it.id }
            assertEquals(listOf("f-1", "f-2"), groupA)
            assertEquals(groupA, groupB)

            // moveFeed reads this same order to compute the new sort orders.
            val placementA = dbA.feedsQueries.groupForMove(folderId = "d1", moved = "f-1", target = null).executeAsList().map { it.id }
            val placementB = dbB.feedsQueries.groupForMove(folderId = "d1", moved = "f-1", target = null).executeAsList().map { it.id }
            assertEquals(listOf("f-1", "f-2"), placementA)
            assertEquals(placementA, placementB)
        } finally {
            driverA.close()
            driverB.close()
        }
    }
}

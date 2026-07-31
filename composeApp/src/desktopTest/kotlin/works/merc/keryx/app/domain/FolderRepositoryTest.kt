package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.ftsManagerIndexed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [SyncScheduler] fake that counts invocations. */
private class FolderCountingSyncScheduler : SyncScheduler {
    var callCount = 0
        private set

    override fun scheduleSync() {
        callCount++
    }
}

/** A [NotificationMessages] fake returning canned, recognizable strings. */
private class FolderRepositoryTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

class FolderRepositoryTest {
    /** An HTTP client whose calls always fail, for [FeedFetcher]/[FaviconResolver] instances that this test never exercises. */
    private fun failingClient(): HttpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }

    private fun newRepo(
        db: works.merc.keryx.app.data.local.db.KeryxDatabase,
        driver: SqlDriver,
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 0L },
    ): FolderRepository {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so indexMissing() (unused here) works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, FeedFetcher(failingClient()), FaviconResolver(failingClient()),
            articleRepository, ftsManager, syncScheduler, NotificationCenter(), FolderRepositoryTestNotificationMessages(),
            clock, Dispatchers.Unconfined,
        )
        return FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
    }

    @Test
    fun createFolderCreatesNewFolderAndSchedulesSync() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            val row = db.foldersQueries.getById(id).executeAsOne()
            assertEquals("Kotlin", row.name)
            assertNull(row.deleted_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderReactivatesSoftDeletedFolderWithSameNameInsteadOfDuplicating() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L, deletedAt = 20L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d1", id)
            val row = db.foldersQueries.getById("d1").executeAsOne()
            assertNull(row.deleted_at)
            assertEquals(1, db.foldersQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderAppendsNewFolderToTheEndOfTheList() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Zebra", now = 10L)
            db.insertFolder("d2", "Apple", now = 10L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("New")

            // "New" must land after both existing folders (sort_order 2), not before "Apple" just
            // because it sorts alphabetically first.
            val ordered = db.foldersQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("d2", "d1", id), ordered)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderReactivatingSoftDeletedFolderAlsoAppendsToEndRatherThanKeepingOldPosition() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L, deletedAt = 20L)
            db.insertFolder("d2", "Active", now = 10L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d1", id)
            val ordered = db.foldersQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("d2", "d1"), ordered)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reorderFoldersMovesDraggedFolderBeforeTargetAndPersists() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "A", now = 0L)
            db.insertFolder("d2", "B", now = 0L)
            db.insertFolder("d3", "C", now = 0L)
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 500L })

            repo.reorderFolders(draggedFolderId = "d3", targetFolderId = "d1")

            val ordered = db.foldersQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("d3", "d1", "d2"), ordered)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reorderFoldersToTheEndWhenTargetIsNull() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "A", now = 0L)
            db.insertFolder("d2", "B", now = 0L)
            val repo = newRepo(db, driver, clock = Clock { 500L })

            repo.reorderFolders(draggedFolderId = "d1", targetFolderId = null)

            val ordered = db.foldersQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("d2", "d1"), ordered)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reorderFoldersOnlyWritesRowsWhoseSortOrderActuallyChanged() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "A", now = 0L)
            db.insertFolder("d2", "B", now = 0L)
            db.insertFolder("d3", "C", now = 0L)
            val repo = newRepo(db, driver, clock = Clock { 999L })

            // Moving the last folder to be right before itself's successor (i.e. no-op position)
            // must not touch "d1" or "d2" at all, since their relative order/sort_order is unchanged.
            repo.reorderFolders(draggedFolderId = "d2", targetFolderId = "d3")

            assertEquals(0L, db.foldersQueries.getById("d1").executeAsOne().updated_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateFolderUpdatesNameAndPersists() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Old", now = 10L)
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 200L })

            repo.updateFolder("d1", "New")

            val row = db.foldersQueries.getById("d1").executeAsOne()
            assertEquals("New", row.name)
            assertEquals(200L, row.updated_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateFolderOnMissingFolderIsNoOp() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler)

            repo.updateFolder("missing", "New")

            assertEquals(0, scheduler.callCount)
            assertNull(db.foldersQueries.getById("missing").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteFolderSoftDeletesWithoutRemovingRow() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 300L })

            repo.deleteFolder("d1")

            val row = db.foldersQueries.getById("d1").executeAsOne()
            assertNotNull(row.deleted_at)
            assertEquals(300L, row.deleted_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteFolderClearsFolderIdOnAllReferencingFeedsAndSoftDeletesFolder() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            db.insertFeed("f1", now = 10L, folderId = "d1")
            db.insertFeed("f2", now = 10L, folderId = "d1")
            db.insertFeed("f3", now = 10L) // unrelated feed, should be untouched
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 400L })

            repo.deleteFolder("d1")

            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertNull(db.feedsQueries.getById("f2").executeAsOne().folder_id)
            assertNull(db.feedsQueries.getById("f3").executeAsOne().folder_id)
            val folder = db.foldersQueries.getById("d1").executeAsOne()
            assertNotNull(folder.deleted_at)
            assertEquals(400L, folder.deleted_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteFolderMovesFeedsToNoFolderGroupAppendingToEndInRelativeOrder() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            // "existing" is already in the "no folder" group, occupying sort_order 0.
            db.insertFeed("existing", now = 10L, sortOrder = 0L)
            // f2 then f1 within "d1", so relative order f2, f1 must be preserved on migration.
            db.insertFeed("f2", now = 10L, folderId = "d1", sortOrder = 0L)
            db.insertFeed("f1", now = 10L, folderId = "d1", sortOrder = 1L)
            val repo = newRepo(db, driver, clock = Clock { 400L })

            repo.deleteFolder("d1")

            val noFolderOrdered = db.feedsQueries.getByFolder(null).executeAsList()
            assertEquals(listOf("existing", "f2", "f1"), noFolderOrdered.map { it.id })
            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertNull(db.feedsQueries.getById("f2").executeAsOne().folder_id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getFolderByIdReturnsExistingFolder() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin")
            val repo = newRepo(db, driver)

            val folder = repo.getFolderById("d1")

            assertEquals("d1", folder?.id)
            assertEquals("Kotlin", folder?.name)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getFolderByIdReturnsSoftDeletedFolderWithDeletedAtSet() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", deletedAt = 20L)
            val repo = newRepo(db, driver)

            val folder = repo.getFolderById("d1")

            assertNotNull(folder)
            assertNotNull(folder.deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getFolderByIdReturnsNullForMissingFolder() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, driver)

            assertNull(repo.getFolderById("missing"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun getAllFoldersReturnsLiveFoldersInDisplayOrderExcludingSoftDeletedOnes() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Second", sortOrder = 1L)
            db.insertFolder("d2", "First", sortOrder = 0L)
            db.insertFolder("d3", "Deleted", sortOrder = 2L, deletedAt = 1L)
            val repo = newRepo(db, driver)

            val folders = repo.getAllFolders()

            assertEquals(listOf("First", "Second"), folders.map { it.name })
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchAllFoldersExcludesSoftDeletedFolders() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Active")
            db.insertFolder("d2", "Deleted", deletedAt = 1L)

            val repo = newRepo(db, driver)
            val names = repo.watchAllFolders().first().map { it.name }

            assertEquals(listOf("Active"), names)
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchAllFoldersOrdersBySortOrderThenName() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Zebra")
            db.insertFolder("d2", "alpha")

            val repo = newRepo(db, driver)
            val names = repo.watchAllFolders().first().map { it.name }

            assertEquals(listOf("alpha", "Zebra"), names)
            assertTrue(names.isNotEmpty())
        } finally {
            driver.close()
        }
    }
}

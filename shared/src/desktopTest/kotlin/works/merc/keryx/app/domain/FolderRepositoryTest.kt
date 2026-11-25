package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.SameNameInsertingDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.ftsManagerIndexed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        placementMutex: Mutex = Mutex(),
    ): FolderRepository {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so indexMissing() (unused here) works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, FeedFetcher(failingClient()), FaviconResolver(failingClient()),
            articleRepository, ftsManager, syncScheduler, NotificationCenter(),
            clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
            feedPlacementMutex = placementMutex,
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

    /** A new folder's id is the one its name derives from, so devices converge on the same row. */
    @Test
    fun createFolderAssignsTheNameDerivedId() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals(IdGenerator.folderId("Kotlin"), id)
            assertEquals(listOf(id), db.foldersQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    /**
     * When the derived id is already held — a row renamed off the name keeps its id — the creation
     * falls back to the id source instead of colliding with that row.
     */
    @Test
    fun createFolderFallsBackToTheIdSourceWhenTheDerivedIdIsTaken() {
        val (driver, db) = inMemoryDb()
        try {
            // A row renamed off the name keeps its id: it holds the id "Kotlin" would derive.
            db.foldersQueries.upsert(
                id = IdGenerator.folderId("Kotlin"), name = "Renamed away", sort_order = 0L,
                deleted_at = null, updated_at = 20L, created_at = 10L,
            )
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertNotEquals(IdGenerator.folderId("Kotlin"), id, "the derived id is held by the renamed row")
            assertEquals("Kotlin", db.foldersQueries.getById(id).executeAsOne().name)
            assertEquals("Renamed away", db.foldersQueries.getById(IdGenerator.folderId("Kotlin")).executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    /**
     * A create, a delete and a recreation within the same millisecond still stamp strictly increasing
     * updated_at, so neither the list value nor the sync merge's strict last-write-wins can mistake the
     * recreated folder for the one before the delete.
     */
    @Test
    fun deleteAndRecreateInTheSameMillisecondStampStrictlyIncreasingTimes() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, driver, clock = Clock { 100L })
            val id = repo.createFolder("Kotlin")
            val created = db.foldersQueries.getById(id).executeAsOne().updated_at

            repo.deleteFolder(id)
            val deleted = db.foldersQueries.getById(id).executeAsOne().updated_at
            repo.createFolder("Kotlin")
            val recreated = db.foldersQueries.getById(id).executeAsOne().updated_at

            assertEquals(100L, created)
            assertTrue(deleted > created)
            assertTrue(recreated > deleted)
        } finally {
            driver.close()
        }
    }

    /**
     * A rename right after a same-millisecond recreation — which moved the stamp ahead of the clock —
     * is still stamped newest, so a device that already has the recreated row takes the rename.
     */
    @Test
    fun aRenameRightAfterASameMillisecondRecreationIsStampedNewest() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, driver, clock = Clock { now })
            val id = repo.createFolder("Kotlin")
            repo.deleteFolder(id)
            repo.createFolder("Kotlin")
            val recreated = db.foldersQueries.getById(id).executeAsOne().updated_at
            now = 101L

            repo.updateFolder(id, "Renamed")

            val row = db.foldersQueries.getById(id).executeAsOne()
            assertEquals("Renamed", row.name)
            assertTrue(row.updated_at > recreated)
        } finally {
            driver.close()
        }
    }

    /** As [aRenameRightAfterASameMillisecondRecreationIsStampedNewest], for a reorder. */
    @Test
    fun aReorderRightAfterASameMillisecondRecreationIsStampedNewest() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, driver, clock = Clock { now })
            val other = repo.createFolder("Other")
            val id = repo.createFolder("Kotlin")
            repo.deleteFolder(id)
            repo.createFolder("Kotlin")
            val recreated = db.foldersQueries.getById(id).executeAsOne().updated_at
            now = 101L

            repo.reorderFolders(id, other)

            val row = db.foldersQueries.getById(id).executeAsOne()
            assertEquals(0L, row.sort_order)
            assertTrue(row.updated_at > recreated)
        } finally {
            driver.close()
        }
    }

    /** With the clock well past the row's stamp, a rename is stamped with the current time as before. */
    @Test
    fun aRenameWithTheClockAheadOfTheRowIsStampedWithTheCurrentTime() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, driver, clock = Clock { now })
            val id = repo.createFolder("Kotlin")
            now = 5000L

            repo.updateFolder(id, "Renamed")

            assertEquals(5000L, db.foldersQueries.getById(id).executeAsOne().updated_at)
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
    fun createFolderReactivatingSoftDeletedFolderPreservesOriginalCreatedAt() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 5L, deletedAt = 20L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            repo.createFolder("Kotlin")

            val row = db.foldersQueries.getById("d1").executeAsOne()
            assertEquals(5L, row.created_at)
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

            runBlocking { repo.deleteFolder("d1") }

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

            runBlocking { repo.deleteFolder("d1") }

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

    /**
     * Deleting a folder appends its feeds to the no-folder group, reading that group's next
     * sort_order first — so it must hold the feed placement lock a concurrent subscribe into that
     * group holds, or the two can allocate the same sort_order.
     */
    @Test
    fun deleteFolderWaitsForTheFeedPlacementLock(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            db.insertFeed("f1", now = 10L, folderId = "d1")
            val placement = Mutex()
            val repo = newRepo(db, driver, clock = Clock { 400L }, placementMutex = placement)
            placement.lock()

            val delete = launch(start = CoroutineStart.UNDISPATCHED) { repo.deleteFolder("d1") }

            assertEquals("d1", db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertNull(db.foldersQueries.getById("d1").executeAsOne().deleted_at)

            placement.unlock()
            delete.join()

            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertEquals(400L, db.foldersQueries.getById("d1").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    /**
     * The delete's time is taken once it holds the placement lock: a move that files a feed into the
     * folder while the delete waits commits a newer `folder_updated_at`, and the delete's
     * unassignment must not be stamped older than it, or a last-write-wins merge keeps that move.
     */
    @Test
    fun deleteFolderStampsTheTimeItGotThePlacementLock(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            db.insertFeed("f1", now = 10L)
            var now = 400L
            val placement = Mutex()
            val repo = newRepo(db, driver, clock = Clock { now }, placementMutex = placement)
            placement.lock()

            val delete = launch(start = CoroutineStart.UNDISPATCHED) { repo.deleteFolder("d1") }
            // A move commits while the delete waits for the lock.
            db.feedsQueries.updateFolder(folderId = "d1", updatedAt = 600L, folderUpdatedAt = 600L, id = "f1")
            now = 900L
            placement.unlock()
            delete.join()

            val feed = db.feedsQueries.getById("f1").executeAsOne()
            assertNull(feed.folder_id)
            assertEquals(900L, feed.folder_updated_at)
            assertEquals(900L, db.foldersQueries.getById("d1").executeAsOne().deleted_at)
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

            runBlocking { repo.deleteFolder("d1") }

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

    @Test
    fun createFolderOfAnActiveFolderNameMovesThatFolderToTheEnd() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", sortOrder = 0L)
            db.insertFolder("d2", "Swift", sortOrder = 1L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d1", id)
            val row = db.foldersQueries.getById("d1").executeAsOne()
            assertEquals(2L, row.sort_order)
            assertEquals(100L, row.updated_at)
            assertEquals(2, db.foldersQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    // --- Names are not unique: the canonical row is reused, and a rename may cross any name ---

    @Test
    fun createFolderReusesTheEarliestCreatedAliveRowOfTheName() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d-late", "Kotlin", now = 20L)
            db.insertFolder("d-early", "Kotlin", now = 10L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d-early", id)
            assertEquals(2, db.foldersQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderPrefersAnAliveSameNamedRowOverAnEarlierDeletedOne() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d-deleted", "Kotlin", now = 5L, deletedAt = 6L)
            db.insertFolder("d-alive", "Kotlin", now = 10L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d-alive", id)
            // The earlier deleted twin stays deleted; no row was created.
            assertNotNull(db.foldersQueries.getById("d-deleted").executeAsOne().deleted_at)
            assertEquals(listOf("d-alive"), db.foldersQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderOnOnlyDeletedSameNamedRowsReactivatesTheEarliest() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d-late", "Kotlin", now = 20L, deletedAt = 21L)
            db.insertFolder("d-early", "Kotlin", now = 10L, deletedAt = 11L)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertEquals("d-early", id)
            assertNull(db.foldersQueries.getById("d-early").executeAsOneOrNull()?.deleted_at)
            // The later twin stays deleted.
            assertNotNull(db.foldersQueries.getById("d-late").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateFolderMayRenameOntoANameAnAliveRowHolds() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Old", now = 10L)
            db.insertFolder("d2", "New", now = 10L)
            val repo = newRepo(db, driver, clock = Clock { 200L })

            repo.updateFolder("d1", "New")

            // No UNIQUE(name) blocks it: both rows keep their ids under the shared name, and the UI
            // numbers them.
            assertEquals("New", db.foldersQueries.getById("d1").executeAsOne().name)
            assertEquals("New", db.foldersQueries.getById("d2").executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateFolderMayRenameOntoANameADeletedRowHolds() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Old", now = 10L)
            db.insertFolder("d2", "New", now = 10L, deletedAt = 20L)
            val repo = newRepo(db, driver, clock = Clock { 200L })

            repo.updateFolder("d1", "New")

            assertEquals("New", db.foldersQueries.getById("d1").executeAsOne().name)
            assertNotNull(db.foldersQueries.getById("d2").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    // --- A same-named folder another writer inserts mid-call (a sync merge, a concurrent create) ---

    @Test
    fun createFolderReusesASameNamedFolderInsertedByAnotherWriterInsteadOfFailing() {
        val (base, _) = inMemoryDb()
        try {
            val driver = SameNameInsertingDriver(base, table = "folders", name = "Kotlin", otherId = "cloud-id")
            val db = KeryxDatabase(driver)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolder("Kotlin")

            assertTrue(driver.fired)
            val rows = db.foldersQueries.watchAll().executeAsList()
            assertEquals(listOf(id), rows.map { it.id })
            assertEquals("Kotlin", rows.single().name)
        } finally {
            base.close()
        }
    }

    @Test
    fun createFolderAndMoveFeedReusesASameNamedFolderInsertedByAnotherWriterInsteadOfFailing() = runTest {
        val (base, baseDb) = inMemoryDb()
        try {
            baseDb.insertFeed("f1")
            val driver = SameNameInsertingDriver(base, table = "folders", name = "Kotlin", otherId = "cloud-id")
            val db = KeryxDatabase(driver)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolderAndMoveFeed("Kotlin", "f1")

            assertTrue(driver.fired)
            assertEquals(listOf(id), db.foldersQueries.watchAll().executeAsList().map { it.id })
            assertEquals(id, db.feedsQueries.getById("f1").executeAsOne().folder_id)
        } finally {
            base.close()
        }
    }

    // --- createFolderAndMoveFeed ---

    @Test
    fun createFolderAndMoveFeedPlacesTheFeedFirstInTheNewFolderAndSchedulesOneSync() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1", sortOrder = 4L)
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 100L })

            val id = repo.createFolderAndMoveFeed("Kotlin", "f1")

            assertEquals("Kotlin", db.foldersQueries.getById(id).executeAsOne().name)
            val feed = db.feedsQueries.getById("f1").executeAsOne()
            assertEquals(id, feed.folder_id)
            assertEquals(0L, feed.sort_order)
            assertEquals(100L, feed.folder_updated_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderAndMoveFeedAppendsAfterFeedsAReactivatedFolderStillHolds() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L, deletedAt = 20L)
            // Left pointing at the deleted folder, as a sync from another device can.
            db.insertFeed("f0", folderId = "d1", sortOrder = 0L)
            db.insertFeed("f1")
            val repo = newRepo(db, driver, clock = Clock { 100L })

            val id = repo.createFolderAndMoveFeed("Kotlin", "f1")

            assertEquals("d1", id)
            // The reactivation re-resolved f0 from its raw assignment, so f1 is appended after it.
            assertEquals("d1", db.feedsQueries.getById("f0").executeAsOne().folder_id)
            assertEquals(1L, db.feedsQueries.getById("f1").executeAsOne().sort_order)
        } finally {
            driver.close()
        }
    }

    /**
     * A feed whose raw assignment still names a folder that is deleted here (a sync from another
     * device can leave it so) shows as "no folder"; recreating the folder by name reactivates it, and
     * the feed is back in it — in the same transaction, with its raw assignment and stamp unchanged.
     */
    @Test
    fun reactivatingAFolderRestoresTheFeedsStillAssignedToIt() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            db.insertFeed("f1", now = 50L, folderId = "d1")
            db.foldersQueries.softDelete(20L, 20L, "d1")
            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            val repo = newRepo(db, driver, clock = Clock { 100L })

            assertEquals("d1", repo.createFolder("Kotlin"))

            val feed = db.feedsQueries.getById("f1").executeAsOne()
            assertEquals("d1", feed.folder_id)
            assertEquals(50L, feed.folder_updated_at)
            val raw = db.sync_bookkeepingQueries.feedFolderRawAll().executeAsList().single()
            assertEquals("d1", raw.folder_id)
            assertEquals(50L, raw.folder_updated_at)
        } finally {
            driver.close()
        }
    }

    /** Deleting a folder moves its feeds out: their raw assignment becomes "no folder", stamped by the delete. */
    @Test
    fun deleteFolderWritesTheMovedFeedsRawAssignment() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            db.insertFeed("f1", now = 50L, folderId = "d1")
            val repo = newRepo(db, driver, clock = Clock { 100L })

            repo.deleteFolder("d1")

            val raw = db.sync_bookkeepingQueries.feedFolderRawAll().executeAsList().single()
            assertNull(raw.folder_id)
            assertEquals(100L, raw.folder_updated_at)
            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertEquals(100L, db.feedsQueries.getById("f1").executeAsOne().folder_updated_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createFolderAndMoveFeedLeavesNoFolderBehindWhenTheMoveFails() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            driver.execute(
                null,
                "CREATE TRIGGER fail_move BEFORE UPDATE OF folder_id ON feeds BEGIN SELECT RAISE(ABORT, 'move failed'); END",
                0,
            )
            val scheduler = FolderCountingSyncScheduler()
            val repo = newRepo(db, driver, syncScheduler = scheduler, clock = Clock { 100L })

            kotlin.test.assertFails { repo.createFolderAndMoveFeed("Kotlin", "f1") }

            assertTrue(db.foldersQueries.watchAll().executeAsList().isEmpty())
            assertNull(db.foldersQueries.getByName("Kotlin").executeAsOneOrNull())
            assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
            assertEquals(0, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    // --- per-field stamps (name_updated_at / sort_order_updated_at) ---

    @Test
    fun aNewFolderStampsBothFieldsWithItsCreationTime() {
        val (driver, db) = inMemoryDb()
        try {
            val id = newRepo(db, driver, clock = Clock { 100L }).createFolder("Kotlin")

            val row = db.foldersQueries.getById(id).executeAsOne()
            assertEquals(listOf<Long?>(100L, 100L), listOf(row.name_updated_at, row.sort_order_updated_at))
        } finally {
            driver.close()
        }
    }

    @Test
    fun aRenameMovesOnlyTheNameStampAndAReorderOnlyTheMovedFoldersSortStamps() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, driver, clock = Clock { now })
            val a = repo.createFolder("A")
            val b = repo.createFolder("B")
            val c = repo.createFolder("C")

            now = 200L
            repo.updateFolder(a, "A2")
            val renamed = db.foldersQueries.getById(a).executeAsOne()
            assertEquals(listOf<Long?>(200L, 100L), listOf(renamed.name_updated_at, renamed.sort_order_updated_at))

            now = 300L
            repo.reorderFolders(c, b) // A, C, B: C and B change position, A stays
            fun stamps(id: String) = db.foldersQueries.getById(id).executeAsOne().let { listOf(it.name_updated_at, it.sort_order_updated_at) }
            assertEquals(listOf<Long?>(200L, 100L), stamps(a))
            assertEquals(listOf<Long?>(100L, 300L), stamps(b))
            assertEquals(listOf<Long?>(100L, 300L), stamps(c))
        } finally {
            driver.close()
        }
    }

    @Test
    fun reactivatingAFolderStampsTheSortOrderItReappends() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, driver, clock = Clock { now })
            val id = repo.createFolder("Kotlin") // sort_order 0
            repo.createFolder("Other") // sort_order 1
            now = 200L
            repo.deleteFolder(id)

            now = 300L
            repo.createFolder("Kotlin") // reappended after "Other"

            val row = db.foldersQueries.getById(id).executeAsOne()
            assertEquals(2L, row.sort_order)
            assertEquals(300L, row.sort_order_updated_at)
            assertEquals(100L, row.name_updated_at)
        } finally {
            driver.close()
        }
    }
}

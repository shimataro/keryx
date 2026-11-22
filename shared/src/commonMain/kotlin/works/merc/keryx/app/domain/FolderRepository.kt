package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.KeryxDatabase

/** Folders for organizing feeds (1 feed = at most 1 folder). Soft-deleted (deleted_at). */
class FolderRepository(
    private val db: KeryxDatabase,
    private val feedRepository: FeedRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Issues the id of a newly created row (a test passes a deterministic source). */
    private val newId: () -> String = IdGenerator::newId,
) {
    private val folders get() = db.foldersQueries

    /**
     * Observes all folders and emits the current folder list whenever it changes.
     *
     * @return A flow containing the current list of folders.
     */
    fun watchAllFolders(): Flow<List<Folders>> = folders.watchAll().asFlow().mapToList(dispatcher)

    /** The folder with [id], or `null` if none exists. */
    fun getFolderById(id: String): Folders? = folders.getById(id).executeAsOneOrNull()

    /**
     * Retrieves all active folders in display order.
     *
     * @return The active folders in display order.
     */
    fun getAllFolders(): List<Folders> = folders.watchAll().executeAsList()

    /**
     * Creates a folder or reactivates an existing folder with the same name.
     *
     * Both new folders and reactivated (previously soft-deleted) folders are appended to the end of
     * the folder list — a soft-deleted folder's old position isn't meaningful anymore since other
     * folders may have been reordered while it was gone.
     *
     * @param name The folder name.
     * @return The folder ID.
     */
    fun createFolder(name: String): String {
        val now = clock.nowMillis()
        val id = db.transactionWithResult { upsertFolderByName(name, now) }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates or reactivates a folder ([createFolder]) and moves [feedId] into it in one transaction,
     * so a failure leaves neither behind — a feed row's "New folder…".
     *
     * Under the feed placement lock, like every other feed placement. The feed is appended after the
     * feeds the folder already holds — none for a new folder, so it takes sort order 0; a reactivated
     * folder may hold feeds a sync left pointing at it. Every read follows the transaction's first
     * write, so the transaction never reads before it writes (the lock upgrade
     * `docs/known-issues.md` describes).
     *
     * @param name The folder name.
     * @param feedId The feed to move into the folder.
     * @return The folder ID.
     */
    suspend fun createFolderAndMoveFeed(name: String, feedId: String): String {
        val id = feedRepository.withFeedPlacementLock {
            // Taken under the lock, as in deleteFolder: a placement committed while this call waited
            // must not end up newer than the one stamped here.
            val now = clock.nowMillis()
            db.transactionWithResult {
                val id = upsertFolderByName(name, now)
                val feedSortOrder = db.feedsQueries.nextSortOrderInGroup(id).executeAsOne()
                db.feedsQueries.updateFolderAndSortOrder(id, feedSortOrder, now, now, now, feedId)
                id
            }
        }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates the folder named [name], or reactivates the existing one in place, appended to the end
     * of the folder list, and returns its id — to be called inside a transaction. The statement itself
     * resolves a name conflict (`upsertByName`), so a same-named row another writer (a sync merge, a
     * concurrent create) inserted just before is reused rather than failing `UNIQUE(name)`. The id is
     * read only after that write, so the transaction never reads before it writes.
     */
    private fun upsertFolderByName(name: String, now: Long): String {
        folders.upsertByName(id = newId(), name = name, now = now)
        return folders.getByName(name).executeAsOne().id
    }

    fun updateFolder(id: String, name: String) {
        val existing = folders.getById(id).executeAsOneOrNull() ?: return
        folders.upsert(id, name, existing.sort_order, existing.deleted_at, clock.nowMillis(), existing.created_at)
        syncScheduler.scheduleSync()
    }

    /**
     * Moves a folder directly before the target folder, or to the end when no target is provided.
     *
     * @param draggedFolderId The ID of the folder to move.
     * @param targetFolderId The ID of the folder to place the dragged folder before, or `null` to place it at the end.
     */
    fun reorderFolders(draggedFolderId: String, targetFolderId: String?) {
        val current = folders.watchAll().executeAsList()
        val newOrder = reorderIds(current.map { it.id }, draggedFolderId, targetFolderId)
        val now = clock.nowMillis()
        db.transaction {
            newOrder.forEachIndexed { index, id ->
                val existing = current.first { it.id == id }
                if (existing.sort_order != index.toLong()) folders.updateSortOrder(index.toLong(), now, id)
            }
        }
        syncScheduler.scheduleSync()
    }

    /**
     * Soft-deletes the folder and moves its feeds to the no-folder group.
     *
     * @param id The identifier of the folder to delete.
     */
    suspend fun deleteFolder(id: String) {
        // Under the feed placement lock: moving the feeds out appends them to the no-folder group,
        // whose next sort_order a concurrent subscribe or move may be allocating too.
        feedRepository.withFeedPlacementLock {
            // Taken under the lock, not before waiting for it: a move committed while this call
            // waited must not end up newer than the unassignment stamped here, or a last-write-wins
            // merge would keep that feed in the deleted folder.
            val now = clock.nowMillis()
            db.transaction {
                feedRepository.moveFeedsOutOfFolderLocked(id, now)
                folders.softDelete(now, now, id)
            }
        }
        syncScheduler.scheduleSync()
    }
}

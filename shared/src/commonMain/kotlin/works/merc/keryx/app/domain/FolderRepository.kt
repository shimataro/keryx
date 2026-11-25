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
    /** Issues the id of a newly created row whose name-derived id is already held (a test passes a deterministic source). */
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
                db.feedsQueries.updateFolderAndSortOrder(folderId = id, sortOrder = feedSortOrder, updatedAt = now, folderUpdatedAt = now, sortOrderUpdatedAt = now, id = feedId)
                id
            }
        }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates the folder named [name], or reactivates the canonical one of that name in place,
     * appended to the end of the folder list, and returns its id — to be called inside a
     * transaction. The two statements (`reactivateByName`, then `insertIfNameAbsent`) resolve the
     * name themselves, so a same-named row another writer (a sync merge, a concurrent create)
     * inserted just before is reused rather than doubled; names are not unique, so the canonical row
     * (an alive one over a deleted one, then the earliest `created_at`, then the smallest id — the
     * row `getByName` returns) is the one reused. A new row's id is the one its name derives from
     * ([IdGenerator.folderId]), so two devices that create the same name independently converge on
     * one row in the sync merge; when that id is already held (a row renamed off the name keeps its
     * id), `insertIfNameAbsent` falls back to a random one from [newId]. The id is read only after
     * that write, so the transaction never reads before it writes.
     */
    private fun upsertFolderByName(name: String, now: Long): String {
        folders.reactivateByName(now = now, name = name)
        folders.insertIfNameAbsent(
            nameDerivedId = IdGenerator.folderId(name),
            randomId = newId(),
            name = name,
            now = now,
        )
        return folders.getByName(name).executeAsOne().id
    }

    /**
     * Renames the folder [id]; a folder that no longer exists is left alone. One statement
     * (`updateName`), which resolves [id] itself: an id a merge has re-keyed since the caller read it
     * (an open rename field, a queued feed-list write) names the row it became, so the rename lands
     * there rather than matching nothing (`ks_id_remap`, see folders.sq). Being the one write, it
     * also never writes back a sort order or deletion read before a concurrent merge.
     */
    fun updateFolder(id: String, name: String) {
        val updated = folders.updateName(name = name, now = clock.nowMillis(), id = id).value
        if (updated > 0L) syncScheduler.scheduleSync()
    }

    /**
     * Moves a folder directly before the target folder, or to the end when no target is provided.
     *
     * Both ids are resolved (`ks_id_remap`, see folders.sq), so a drag that started before a merge
     * re-keyed either folder still moves the folder it became, before the one it became. The order
     * and both resolutions come from one statement (`orderForReorder`), so they always describe the
     * same folder state — a re-key can never leave the dragged or target id resolved against one
     * state and missing from the order read from another. That read stays outside the transaction,
     * so the transaction starts with a write (the lock upgrade `docs/known-issues.md` describes); a
     * re-key landing between the read and the transaction is still applied correctly, since each
     * `updateSortOrder` resolves its id again. A dragged folder that is not live (deleted, or gone)
     * is not moved, and a target that is not live places the dragged folder at the end.
     *
     * @param draggedFolderId The ID of the folder to move.
     * @param targetFolderId The ID of the folder to place the dragged folder before, or `null` to place it at the end.
     */
    fun reorderFolders(draggedFolderId: String, targetFolderId: String?) {
        val current = folders.orderForReorder(dragged = draggedFolderId, target = targetFolderId).executeAsList()
        val dragged = current.firstOrNull { it.is_dragged }?.id ?: draggedFolderId
        val target = current.firstOrNull { it.is_target != 0L }?.id
        val sortOrders = current.associate { it.id to it.sort_order }
        val newOrder = reorderIds(current.map { it.id }, dragged, target)
        val now = clock.nowMillis()
        db.transaction {
            var index = 0L
            for (id in newOrder) {
                // An id the order does not hold (a dragged folder that is not live) is skipped.
                val existing = sortOrders[id] ?: continue
                if (existing != index) folders.updateSortOrder(index, now, id)
                index++
            }
        }
        syncScheduler.scheduleSync()
    }

    /**
     * Soft-deletes the folder and moves its feeds to the no-folder group. [id] is resolved by every
     * statement that takes it (`getByFolder`, `softDelete`; see folders.sq), so a folder a merge has
     * re-keyed since the caller read [id] is the one deleted and emptied.
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

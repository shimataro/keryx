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
     * @param name The folder name.
     * @return The folder ID.
     */
    fun createFolder(name: String): String {
        val now = clock.nowMillis()
        val existing = folders.getByName(name).executeAsOneOrNull()
        // Both new folders and reactivated (previously soft-deleted) folders are appended to the
        // end of the folder list — a soft-deleted folder's old position isn't meaningful anymore
        // since other folders may have been reordered while it was gone.
        val nextSortOrder = folders.nextSortOrder().executeAsOne()
        val id = if (existing != null) {
            folders.upsert(existing.id, name, nextSortOrder, null, now, existing.created_at)
            existing.id
        } else {
            val newId = IdGenerator.newId()
            folders.upsert(newId, name, nextSortOrder, null, now, now)
            newId
        }
        syncScheduler.scheduleSync()
        return id
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
    fun deleteFolder(id: String) {
        val now = clock.nowMillis()
        db.transaction {
            feedRepository.moveFeedsOutOfFolder(id, now)
            folders.softDelete(now, now, id)
        }
        syncScheduler.scheduleSync()
    }
}

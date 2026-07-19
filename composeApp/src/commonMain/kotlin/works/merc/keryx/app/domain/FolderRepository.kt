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
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val folders get() = db.foldersQueries
    private val feeds get() = db.feedsQueries

    fun watchAllFolders(): Flow<List<Folders>> = folders.watchAll().asFlow().mapToList(dispatcher)

    fun getFolderById(id: String): Folders? = folders.getById(id).executeAsOneOrNull()

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
     * Reorders folders: moves [draggedFolderId] directly before [targetFolderId] (or to the end
     * if null). Only folders whose `sort_order` actually changes are written, to avoid bumping
     * `updated_at` on unrelated folders (see [FeedRepository.moveFeed] for the same rationale).
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
     * Soft-deletes the folder and moves its feeds into the "no folder" group, preserving their
     * relative order and appending them to the end of that group (all rows here genuinely change
     * `folder_id`, so writing every one of them is not the "unrelated updated_at bump" this
     * feature otherwise avoids).
     */
    fun deleteFolder(id: String) {
        val now = clock.nowMillis()
        db.transaction {
            var next = feeds.nextSortOrderInGroup(null).executeAsOne()
            for (feed in feeds.getByFolder(id).executeAsList()) {
                feeds.updateFolderAndSortOrder(null, next, now, now, now, feed.id)
                next++
            }
            folders.softDelete(now, now, id)
        }
        syncScheduler.scheduleSync()
    }
}

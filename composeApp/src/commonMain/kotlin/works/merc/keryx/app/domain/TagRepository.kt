package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.local.db.Tags

/** Tags and feed↔tag assignments. Tag attach/detach is a soft-delete. */
class TagRepository(
    private val db: KeryxDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val tags get() = db.tagsQueries
    private val feedTags get() = db.feed_tagsQueries

    fun watchAllTags(): Flow<List<Tags>> = tags.watchAll().asFlow().mapToList(dispatcher)

    /** feedId -> set of attached tagIds. */
    fun watchFeedTagMap(): Flow<Map<String, Set<String>>> =
        feedTags.watchAllActive().asFlow().mapToList(dispatcher).map { rows ->
            rows.groupBy({ it.feed_id }, { it.tag_id }).mapValues { it.value.toSet() }
        }

    /**
         * Watches the active tag IDs assigned to a feed.
         *
         * @param feedId The ID of the feed.
         * @return A flow emitting the feed's active tag IDs.
         */
        fun watchTagIdsForFeed(feedId: String): Flow<List<String>> =
        feedTags.watchTagIdsForFeed(feedId).asFlow().mapToList(dispatcher)

    /** The tag with [id], or `null` if none exists. */
    fun getTagById(id: String): Tags? = tags.getById(id).executeAsOneOrNull()

    /**
 * Retrieves all active tags in display order.
 *
 * @return The active tags.
 */
    fun getAllTags(): List<Tags> = tags.watchAll().executeAsList()

    /**
         * Retrieves active tag assignments grouped by feed.
         *
         * @return A map from feed IDs to their associated tag IDs.
         */
    fun getFeedTagMap(): Map<String, Set<String>> =
        feedTags.watchAllActive().executeAsList().groupBy({ it.feed_id }, { it.tag_id }).mapValues { it.value.toSet() }

    /**
     * Creates or reactivates a tag with the specified name and color.
     *
     * @param name The tag name.
     * @param color The tag color, or `null` to preserve an existing color or leave a new tag uncolored.
     * @return The tag ID.
     */
    fun createTag(name: String, color: String? = null): String {
        val now = clock.nowMillis()
        val existing = tags.getByName(name).executeAsOneOrNull()
        val id = if (existing != null) {
            // Reactivate / update an existing (possibly soft-deleted) tag of the same name.
            tags.upsert(existing.id, name, color ?: existing.color, existing.sort_order, null, now, existing.created_at)
            existing.id
        } else {
            val newId = IdGenerator.newId()
            tags.upsert(newId, name, color, 0, null, now, now)
            newId
        }
        syncScheduler.scheduleSync()
        return id
    }

    fun updateTag(id: String, name: String, color: String?) {
        val existing = tags.getById(id).executeAsOneOrNull() ?: return
        tags.upsert(id, name, color, existing.sort_order, existing.deleted_at, clock.nowMillis(), existing.created_at)
        syncScheduler.scheduleSync()
    }

    fun deleteTag(id: String) {
        tags.softDelete(clock.nowMillis(), clock.nowMillis(), id)
        syncScheduler.scheduleSync()
    }

    fun setFeedTag(feedId: String, tagId: String, attached: Boolean) {
        val now = clock.nowMillis()
        feedTags.upsert(feedId, tagId, if (attached) null else now, now)
        syncScheduler.scheduleSync()
    }
}

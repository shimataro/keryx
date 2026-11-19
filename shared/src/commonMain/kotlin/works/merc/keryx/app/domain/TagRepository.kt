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
import works.merc.keryx.app.data.local.db.WatchAllActive

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

    /**
     * Observes active feed-to-tag assignments grouped by feed.
     *
     * @return A flow emitting a map from feed IDs to their attached tag IDs.
     */
    fun watchFeedTagMap(): Flow<Map<String, Set<String>>> =
        feedTags.watchAllActive().asFlow().mapToList(dispatcher).map(::groupTagIdsByFeed)

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
        groupTagIdsByFeed(feedTags.watchAllActive().executeAsList())

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
        val id = createOrReactivateId(existing?.id) { id ->
            // Reactivates an existing (possibly soft-deleted) tag of the same name in place.
            tags.upsert(id, name, color ?: existing?.color, existing?.sort_order ?: 0, null, now, existing?.created_at ?: now)
        }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates or reactivates a tag ([createTag]) and attaches it to [feedId] ([setFeedTag]) in one
     * transaction, so a failure leaves neither behind — a feed row's "New tag…". The existing tag of
     * that name is looked up before the transaction, not inside it: a deferred transaction that reads
     * and then writes has to upgrade its lock, which SQLite can refuse with an immediate
     * `SQLITE_BUSY` (see `docs/known-issues.md`).
     *
     * @param name The tag name.
     * @param color The tag color, or `null` to preserve an existing color or leave a new tag uncolored.
     * @param feedId The feed to attach the tag to.
     * @return The tag ID.
     */
    fun createTagAndAttach(name: String, color: String?, feedId: String): String {
        val now = clock.nowMillis()
        val existing = tags.getByName(name).executeAsOneOrNull()
        val id = db.transactionWithResult {
            createOrReactivateId(existing?.id) { id ->
                tags.upsert(id, name, color ?: existing?.color, existing?.sort_order ?: 0, null, now, existing?.created_at ?: now)
            }.also { id -> feedTags.upsert(feedId, id, null, now) }
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

/** Active feed-to-tag assignment rows grouped into a map from feed id to its tag ids. */
private fun groupTagIdsByFeed(rows: List<WatchAllActive>): Map<String, Set<String>> =
    rows.groupBy({ it.feed_id }, { it.tag_id }).mapValues { it.value.toSet() }

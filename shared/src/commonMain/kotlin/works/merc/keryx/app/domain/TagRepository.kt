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
    /** Issues the id of a newly created row (a test passes a deterministic source). */
    private val newId: () -> String = IdGenerator::newId,
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
        val id = db.transactionWithResult { upsertTagByName(name, color, now) }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates or reactivates a tag ([createTag]) and attaches it to [feedId] ([setFeedTag]) in one
     * transaction, so a failure leaves neither behind — a feed row's "New tag…".
     *
     * @param name The tag name.
     * @param color The tag color, or `null` to preserve an existing color or leave a new tag uncolored.
     * @param feedId The feed to attach the tag to.
     * @return The tag ID.
     */
    fun createTagAndAttach(name: String, color: String?, feedId: String): String {
        val now = clock.nowMillis()
        val id = db.transactionWithResult {
            upsertTagByName(name, color, now).also { id -> feedTags.upsert(feedId, id, null, now) }
        }
        syncScheduler.scheduleSync()
        return id
    }

    /**
     * Creates the tag named [name], or reactivates the canonical one of that name in place, and
     * returns its id — to be called inside a transaction. The two statements (`reactivateByName`,
     * then `insertIfNameAbsent`) resolve the name themselves, so a same-named row another writer (a
     * sync merge, a concurrent create) inserted just before is reused rather than doubled; names are
     * not unique, so the canonical row (an alive one over a deleted one, then the earliest
     * `created_at`, then the smallest id — the row `getByName` returns) is the one reused. The id is
     * read only after that write, so the transaction never reads before it writes (the lock upgrade
     * `docs/known-issues.md` describes).
     */
    private fun upsertTagByName(name: String, color: String?, now: Long): String {
        tags.reactivateByName(now = now, color = color, name = name)
        tags.insertIfNameAbsent(id = newId(), name = name, color = color, now = now)
        return tags.getByName(name).executeAsOne().id
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

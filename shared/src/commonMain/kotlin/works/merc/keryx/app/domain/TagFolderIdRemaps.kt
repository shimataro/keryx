package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.data.local.db.KeryxDatabase

/**
 * The whole of `ks_id_remap` as one value: every tag and folder id a re-key moved, mapped to the id
 * its row has now. Chains are folded as they are recorded ([IdRemapSql]), so one lookup suffices, and
 * no live row carries an id mapped here (`insertIfNameAbsent` treats a mapped id as held) — so
 * applying the map to an id is idempotent and never moves an id that still names a row.
 */
data class TagFolderIdRemap(
    val tagIds: Map<String, String>,
    val folderIds: Map<String, String>,
) {
    /** The id the tag [id] has now: [id] itself unless a re-key moved it. */
    fun tag(id: String): String = tagIds[id] ?: id

    /** The id the folder [id] has now: [id] itself unless a re-key moved it. */
    fun folder(id: String): String = folderIds[id] ?: id

    /** [ids] with each tag id brought up to date, in the same order. */
    fun tags(ids: Set<String>): Set<String> = ids.mapTo(LinkedHashSet()) { tag(it) }

    /** [ids] with each folder id brought up to date, in the same order. */
    fun folders(ids: Set<String>): Set<String> = ids.mapTo(LinkedHashSet()) { folder(it) }

    /** [filter] with its tag or folder id brought up to date; any other filter unchanged. */
    fun filter(filter: ArticleFilter): ArticleFilter = when (filter) {
        is ArticleFilter.Tag -> tag(filter.tagId).let { if (it == filter.tagId) filter else ArticleFilter.Tag(it) }
        is ArticleFilter.Folder -> folder(filter.folderId).let { if (it == filter.folderId) filter else ArticleFilter.Folder(it) }
        ArticleFilter.All, ArticleFilter.Starred, is ArticleFilter.Feed -> filter
    }

    /** Whether nothing was ever re-keyed. */
    val isEmpty: Boolean get() = tagIds.isEmpty() && folderIds.isEmpty()

    companion object {
        /** No re-key. */
        val EMPTY = TagFolderIdRemap(emptyMap(), emptyMap())
    }
}

/**
 * Publishes the tag/folder id re-keys (`ks_id_remap`) to whatever holds such ids outside the
 * database — `SettingsRepository` (local_settings.json) and `HomeViewModel` (the selection, the
 * feed list's expansion, pending delete fallbacks). A merge that re-keys rows calls [publishFromDb]
 * once it has committed; each holder rewrites its ids from [current].
 *
 * Why a [StateFlow] of the **whole** folded map rather than a stream of per-merge batches:
 * - nothing is lost — a holder that starts collecting late (a ViewModel created after a merge, a
 *   collector not yet running when the merge committed) receives the current value, which includes
 *   every re-key so far, and a value superseded before a slow collector saw it is contained in the
 *   newer one;
 * - nothing is applied twice in a harmful way — the map is folded (one hop) and no live id is a key,
 *   so applying a value again changes nothing; and a collector never sees an older value after a
 *   newer one ([publishFromDb] publishes its reads in order, and a [StateFlow] delivers in order);
 * - the database stays the single source of truth: the value is always read from `ks_id_remap`, so a
 *   holder that missed every emission (the process restarted) is brought up to date by the initial
 *   load from the same table.
 * The map holds only rows a re-key ever wrote (the tags and folders of the v2 era, plus each merge
 * re-key), so reading it whole stays cheap.
 *
 * **The initial load runs in the background, on [dispatcher]**, not in the constructor: the
 * constructor is reached from the UI thread during startup (a Koin single resolved by the first
 * composition), and its first query would open the database — and, on Android, run its migration —
 * there. Until it finishes, [current] is [TagFolderIdRemap.EMPTY] and [loaded] is `false`; a holder
 * that must not act on a stale id before then (`HomeViewModel` restoring the saved selection) waits
 * on [awaitLoaded], off the UI thread. A holder that only follows [current] needs no waiting: the
 * loaded map arrives as a new value, which each applies like any later publish. The initial load is
 * a publish like any other ([publishFromDb]), so a merge's publish cannot be overtaken by it.
 *
 * A Koin single in production, so every holder observes the same instance.
 */
class TagFolderIdRemaps(
    private val load: () -> TagFolderIdRemap,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {

    /** Reads the current map from [db]'s `ks_id_remap`. */
    constructor(db: KeryxDatabase, dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)) :
        this({ loadIdRemap(db) }, dispatcher)

    private val _current = MutableStateFlow(TagFolderIdRemap.EMPTY)

    /** The current folded map — every re-key so far. [TagFolderIdRemap.EMPTY] until [loaded]. */
    val current: StateFlow<TagFolderIdRemap> = _current.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** Whether the initial load has finished — successfully or not (see [awaitLoaded]). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // Serializes read-and-publish, so two publishes cannot set their reads out of order (an older
    // read landing after a newer one). The initial load takes it like any publish, so a merge's
    // publish and the initial load are ordered by who reads the table last.
    private val publishLock = Mutex()

    init {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                publishFromDb()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best effort (docs/error-design.md): the map stays EMPTY until the next publish, and every
                // write still resolves a stale id itself (tags.sq / folders.sq); throwing here would only
                // end the app on a read whose failure the holders can live without.
                Log.error(TAG, "Failed to load the tag/folder id re-keys", e)
            }
            // Not reached on cancellation (rethrown above): a cancelled load is not a finished one.
            _loaded.value = true
        }
    }

    /**
     * Suspends until the initial load has finished (successfully or not — after a failure the map
     * stays [TagFolderIdRemap.EMPTY] until the next publish). Returns at once when it already has.
     */
    suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    /**
     * Re-reads `ks_id_remap` and publishes it when it changed. Called after a merge that re-keyed rows
     * has committed (never inside its transaction: a rolled-back re-key must not reach any holder).
     * A failing read propagates to the caller — the sync that ran the merge, which logs it and carries
     * on (`DeltaSync.publishIdRemapsAfterCommit`): the holders then catch up at the next publish or
     * launch, and every write in the meantime still resolves stale ids itself (tags.sq / folders.sq).
     */
    suspend fun publishFromDb() {
        publishLock.withLock { _current.value = load() }
    }

    private companion object {
        const val TAG = "TagFolderIdRemaps"
    }
}

/** `ks_id_remap` as a [TagFolderIdRemap]. */
internal fun loadIdRemap(db: KeryxDatabase): TagFolderIdRemap {
    val rows = db.sync_bookkeepingQueries.idRemapAll().executeAsList()
    if (rows.isEmpty()) return TagFolderIdRemap.EMPTY
    return TagFolderIdRemap(
        tagIds = rows.filter { it.kind == ID_REMAP_KIND_TAG }.associate { it.old_id to it.new_id },
        folderIds = rows.filter { it.kind == ID_REMAP_KIND_FOLDER }.associate { it.old_id to it.new_id },
    )
}

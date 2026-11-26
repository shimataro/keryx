package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FAVICON_RETRY_INTERVAL_MS
import works.merc.keryx.app.core.FEED_FETCH_CONCURRENCY
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.map
import works.merc.keryx.app.core.runCatchingUnlessCancelled
import works.merc.keryx.app.core.valueOrElse
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.Feed_fetch_state
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.FeedsQueries
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.FetchedFeed

/**
 * Max feeds a refresh holds between starting their fetch and the writer committing them — the
 * fetched results queued for the single writer included. Every queued result keeps its feed's parsed
 * articles resident, so without this a writer held back (a sync merge's write lock) would let a large
 * subscription list fetch its whole contents into memory at once. Twice the fetch concurrency, so a
 * writer that keeps up never stalls a fetch slot.
 */
internal const val REFRESH_PENDING_APPLY_LIMIT = 2 * FEED_FETCH_CONCURRENCY

private const val TAG = "FeedRepository"

/**
 * Feed subscription lifecycle and refresh. Orchestrates [FeedFetcher] +
 * [FaviconResolver] + article upsert, and applies the feed-health rules
 * (error counting, 410 Gone notification, 301/308 URL update).
 */
class FeedRepository(
    private val db: KeryxDatabase,
    private val feedFetcher: FeedFetcher,
    private val faviconResolver: FaviconResolver,
    private val articleRepository: ArticleRepository,
    private val ftsManager: FtsManager,
    private val syncScheduler: SyncScheduler,
    private val notificationCenter: NotificationCenter,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val faviconMisses: FaviconMissStore,
    // Guards feed placement — see the "[feedPlacementMutex] guards" comment below. A constructor parameter only so
    // tests can wrap or hold it and observe a placement waiting on it.
    private val feedPlacementMutex: Mutex = Mutex(),
    /**
     * The driver [db] runs on: a refresh whose permanent redirect lands on a URL another feed holds
     * merges the two feeds with raw statements ([FeedIdentitySql.mergeLocally]) inside its own
     * transaction, which only the same driver joins.
     */
    private val driver: SqlDriver,
    /**
     * Republishes `ks_id_remap` to the ids held outside the database once such a merge has committed
     * (`TagFolderIdRemaps.publishFromDb`), as `DeltaSync` does after a sync merge. Best effort: a
     * failure is logged.
     */
    private val publishIdRemaps: suspend () -> Unit = {},
) {
    private val feeds get() = db.feedsQueries

    // A feed's device-local fetch state (validators, error count, last error) — see feed_fetch_state.sq.
    private val fetchStates get() = db.feed_fetch_stateQueries

    // [feedPlacementMutex] guards every read-then-write of `feeds.sort_order` within a group:
    // subscribeFeedWrite's sort-order allocation + upsert + folder assignment, moveFeed's renumbering
    // of its target group, and moveFeedsOutOfFolderLocked's append to the no-folder group (reached
    // through FolderRepository.deleteFolder, via withFeedPlacementLock). A db.transaction alone isn't
    // enough: SQLite's deferred BEGIN means the nextSortOrderInGroup / getByFolder SELECT doesn't take
    // a write lock, so two overlapping writers could still both read the same stale order before
    // either writes, leaving duplicate sort_orders. This mutex is the piece that actually serializes
    // that read-then-write within this process — OpmlImporter's own mutex only serializes import()
    // runs against each other, not against the "Add Feed" dialog (AddFeedPreviewResolver) or a feed-
    // list move, so they need this shared guard here where they converge. The sync merge runs on its
    // own connection outside it; its sort_order changes resolve by last-write-wins. (Injected — see
    // the constructor's feedPlacementMutex parameter.)

    // Per-feed reference count of in-flight refreshes. A count rather than a set membership because a
    // single-feed refresh can overlap a refresh of every feed, and the feed must stay marked until both end.
    private val _refreshingFeedCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Ids of the feeds currently being refreshed — from the moment a feed's refresh is requested
     * (including while it is queued for a fetch slot) until its articles are committed — by any route
     * ([refreshFeed], [refreshFeeds]: manual, pull-to-refresh, background, startup). Session-only,
     * never persisted.
     */
    val refreshingFeedIds: Flow<Set<String>> = _refreshingFeedCounts.map { it.keys }.distinctUntilChanged()

    fun watchAllFeeds(): Flow<List<Feeds>> = feeds.watchAll().asFlow().mapToList(dispatcher)

    /**
     * The feeds the feed list flags, by id, from their device-local fetch state: a failed last
     * fetch, or a 410 Gone. A feed with no problem (or no fetch state yet) has no entry.
     */
    fun watchFetchProblems(): Flow<Map<String, FeedFetchProblem>> =
        fetchStates.watchProblems(FEED_ERROR_REASON_GONE).asFlow().mapToList(dispatcher).map { rows ->
            buildMap {
                rows.forEach { row -> feedFetchProblem(row.error_count, row.last_error)?.let { put(row.feed_id, it) } }
            }
        }

    /** [feedId]'s device-local fetch state, or `null` when it has none yet (never fetched here). */
    fun getFetchState(feedId: String): Feed_fetch_state? = fetchStates.getByFeedId(feedId).executeAsOneOrNull()

    /**
     * Retrieves a feed by its identifier.
     *
     * @param id The feed identifier.
     * @return The matching feed, or `null` if no feed exists with the identifier.
     */
    fun getFeedById(id: String): Feeds? = feeds.getById(id).executeAsOneOrNull()

    /** All feeds currently stored in the database. */
    fun getAllFeeds(): List<Feeds> = feeds.watchAll().executeAsList()

    /**
     * The feed subscribed at exactly [url], including a soft-deleted one. Note that
     * [subscribeFeed] stores the redirect-resolved URL, so a lookup by the pre-redirect URL misses.
     */
    fun getFeedByUrl(url: String): Feeds? = feeds.getByUrl(url).executeAsOneOrNull()

    /**
     * Fetches feed data for preview without subscribing to the feed.
     *
     * @param url The feed URL to fetch.
     * @return The fetched feed data or the fetch error.
     */
    suspend fun previewFeed(url: String): Result<FetchedFeed> = feedFetcher.fetch(url)

    /**
     * Subscribes to a feed, storing its metadata and articles locally.
     *
     * @param url The feed URL to fetch and subscribe to.
     * @param folderId The folder a brand-new feed should be filed into, or `null` for no folder.
     *   Ignored when re-subscribing an existing feed, which keeps its prior folder.
     * @param afterFeedId The feed a brand-new feed should be inserted directly after, within its
     *   target group (folder or unfiled), or `null` to append it at the end of that group instead.
     *   Ignored when re-subscribing an existing feed, which keeps its prior position.
     * @param beforeFeedId The feed a brand-new feed should be inserted directly *before*, within
     *   its target group (folder or unfiled), or `null`. Only consulted when [afterFeedId] is null
     *   or names a feed that isn't in that group; a [beforeFeedId] that isn't in the group falls
     *   back to appending at the end. Ignored when re-subscribing an existing feed, which keeps its
     *   prior position.
     * @return A successful result containing the subscribed feed, or the fetch error.
     */
    suspend fun subscribeFeed(
        url: String,
        folderId: String? = null,
        afterFeedId: String? = null,
        beforeFeedId: String? = null,
    ): Result<Feeds> = subscribeFeedTracked(url, folderId, afterFeedId, beforeFeedId).result

    /** Result of [subscribeFeedTracked]: the subscribe result, plus whether it created a brand-new feed. */
    data class FeedSubscription(val result: Result<Feeds>, val wasNewlyCreated: Boolean)

    /**
     * Like [subscribeFeed], but also reports whether this call created a brand-new feed, as opposed
     * to returning an already-active or just-resubscribed one. Callers that place multiple feeds in
     * one batch (e.g. [AddFeedPreviewResolver.subscribeFeeds]) need this to avoid treating every
     * `Result.Ok` as "new" — a URL that resolves to a feed the user already has active must not be
     * repositioned or (re-)tagged as if it were.
     */
    suspend fun subscribeFeedTracked(
        url: String,
        folderId: String? = null,
        afterFeedId: String? = null,
        beforeFeedId: String? = null,
    ): FeedSubscription {
        val outcome = subscribeFeedWrite(url, folderId, afterFeedId, beforeFeedId)
        // Best effort: the feed and its articles are already stored, and the next indexMissing()
        // indexes them. Letting an index failure escape would turn a subscription that worked into a
        // crash for the caller (see docs/error-design.md's best-effort accesses).
        if (outcome.hadArticles) {
            runCatchingUnlessCancelled { ftsManager.indexMissing() }
                .onFailure { Log.warn(TAG, "Indexing a new subscription's articles failed", it) }
        }
        return FeedSubscription(outcome.result, outcome.wasNewlyCreated)
    }

    /**
     * Outcome of [subscribeFeedWrite]: the subscribe result, whether the feed had articles, and
     * whether the write created a brand-new feed (as opposed to an already-active or resubscribed one).
     */
    internal data class FeedWriteOutcome(val result: Result<Feeds>, val hadArticles: Boolean, val wasNewlyCreated: Boolean)

    /**
     * Fetches a feed and persists its metadata and articles without indexing them.
     *
     * @param url The URL of the feed to subscribe to.
     * @param folderId The folder a brand-new feed should be filed into, or `null` for no folder.
     *   Ignored when re-subscribing an existing feed, which keeps its prior folder.
     * @param afterFeedId The feed a brand-new feed should be inserted directly after, within its
     *   target group (folder or unfiled), or `null` to append it at the end of that group instead.
     *   Ignored when re-subscribing an existing feed, which keeps its prior position.
     * @param beforeFeedId The feed a brand-new feed should be inserted directly *before*, within
     *   its target group (folder or unfiled), or `null`. Only consulted when [afterFeedId] is null
     *   or names a feed that isn't in that group; a [beforeFeedId] that isn't in the group falls
     *   back to appending at the end. Ignored when re-subscribing an existing feed, which keeps its
     *   prior position.
     * @return The subscription result, including the stored feed or fetch error and whether articles were fetched.
     */
    internal suspend fun subscribeFeedWrite(
        url: String,
        folderId: String? = null,
        afterFeedId: String? = null,
        beforeFeedId: String? = null,
    ): FeedWriteOutcome = writePrefetchedSubscription(prefetchSubscription(url), folderId, afterFeedId, beforeFeedId)

    /**
     * The network phase of [subscribeFeedWrite]: the fetched feed, its redirect-resolved URL and its
     * favicon. Writes nothing to the DB, so a batch (see [OpmlImporter]) can run several of these
     * concurrently while [writePrefetchedSubscription] keeps the writes serial and in order.
     *
     * [storedBeforeFetch] is the feed values of the row stored at the requested URL, read before the
     * fetch began (`null` when there was none). At write time it tells whether something else — a
     * refresh of the same feed — changed those values while this fetch was in flight.
     * [observedBatchWrites] is how many of the batch's own writes had committed when that read was
     * taken (0 outside a batch) — see [SubscribeBatch].
     */
    internal class PrefetchedSubscription(
        val fetched: FetchedFeed,
        val effectiveUrl: String,
        val favicon: String?,
        val storedBeforeFetch: StoredFeedValues?,
        val observedBatchWrites: Int,
    )

    /**
     * The feed values a subscribe writes over an existing row (everything `feeds.upsert` takes from
     * the fetch), with the id of the row they were read from.
     */
    internal data class StoredFeedValues(val feedId: String, val values: FeedValues)

    /** The feed-level values a subscribe or a refresh writes from a fetch. */
    internal data class FeedValues(
        val siteUrl: String?,
        val title: String,
        val description: String?,
        val faviconUrl: String?,
        val etag: String?,
        val lastModified: String?,
    )

    /** These values with the favicon blanked out: the content and the validators that describe it. */
    private fun FeedValues.withoutFavicon(): FeedValues = copy(faviconUrl = null)

    /**
     * The feed values stored for the feed at [url] — its content columns and the validators in its
     * fetch state — read in one statement, or `null` when no row has that url.
     */
    private fun storedFeedValues(url: String): StoredFeedValues? =
        fetchStates.storedValuesByUrl(url).executeAsOneOrNull()?.let {
            StoredFeedValues(it.id, FeedValues(it.site_url, it.title, it.description, it.favicon_url, it.etag, it.last_modified))
        }

    /**
     * Fetches [url] and resolves its favicon, writing nothing to the DB. A fetch error is returned as
     * [Result.Err]; the favicon lookup is made only once the fetch succeeded.
     */
    internal suspend fun prefetchSubscription(url: String, batch: SubscribeBatch? = null): Result<PrefetchedSubscription> {
        // Read before the fetch starts, so a write that lands while it is in flight shows as a change.
        val readRow = { storedFeedValues(url) }
        // In a batch, read together with the count of the batch's committed writes, with no batch write
        // between the two (see SubscribeBatch); only this one read is held under the batch lock.
        val (observedBatchWrites, storedBeforeFetch) =
            if (batch != null) batch.observe(readRow).let { it.writes to it.value } else 0 to readRow()
        val fetched = feedFetcher.fetch(url).valueOrElse { return it }
        val effectiveUrl = fetched.redirectUrl ?: url
        val favicon = faviconResolver.resolve(fetched.siteUrl, effectiveUrl)
        return Result.Ok(PrefetchedSubscription(fetched, effectiveUrl, favicon, storedBeforeFetch, observedBatchWrites))
    }

    /**
     * The write phase of [subscribeFeedWrite]: persists a [prefetchSubscription] result. Everything
     * that decides the write from the DB or the clock happens here, at write time rather than at
     * fetch time, so a batch that fetched ahead still sees each earlier entry's write (a duplicate
     * URL, or two URLs redirecting to the same feed, resolve to the existing row) and stamps the time
     * of the write. (The prefetch's own read, [PrefetchedSubscription.storedBeforeFetch], is only the
     * baseline that tells whether the row's feed values changed in between.)
     * Parameters as for [subscribeFeedWrite], plus:
     *
     * @param batch What this caller's batch (an [OpmlImporter] run) has itself written so far, updated
     *   here with what this call replaces. A row this batch wrote after the prefetch read it — an
     *   earlier entry redirecting to the same feed — changed only by this batch's own hand, so that
     *   write, not the prefetch's read, is the baseline for each group it replaced: the later entry's
     *   fetched values replace it, as they would one entry at a time, while a refresh that changed the
     *   row after it is still kept — including across further entries, since a group the batch kept
     *   rather than replaced is never recorded as its own (see [SubscribeBatch]). `null` (a single
     *   subscribe) keeps no record.
     */
    internal suspend fun writePrefetchedSubscription(
        prefetched: Result<PrefetchedSubscription>,
        folderId: String? = null,
        afterFeedId: String? = null,
        beforeFeedId: String? = null,
        batch: SubscribeBatch? = null,
    ): FeedWriteOutcome {
        val subscription = prefetched.valueOrElse {
            return FeedWriteOutcome(it, hadArticles = false, wasNewlyCreated = false)
        }
        val fetched = subscription.fetched
        val effectiveUrl = subscription.effectiveUrl

        // sort_order: a brand-new feed is inserted right after afterFeedId (or, failing that, right
        // before beforeFeedId) within its target folder's group (the "no folder" group, unless the
        // caller passed a folderId to file it into), or appended at the end of that group when
        // neither anchor is given or found there;
        // a still-live feed being re-fetched keeps its existing position; a previously-unsubscribed
        // feed being re-subscribed is re-numbered to the end of the group it used to belong to (its
        // old relative position may no longer be meaningful after other feeds in that group moved
        // around while it was unsubscribed).
        // The CPU-heavy part of storing the articles (each one's HTML stripped into its search text)
        // is done here, before the lock and the transaction, for the id a new row gets; the write
        // itself goes into the feed's own transaction (see doSubscribeFeedWrite).
        val articleRowsFeedId = IdGenerator.feedId(effectiveUrl)
        val articleRows = articleRepository.prepareRows(articleRowsFeedId, fetched.articles)
        val writeAndRecord: suspend () -> SubscribeWrite = {
            val written = feedPlacementMutex.withLock {
                doSubscribeFeedWrite(
                    effectiveUrl, folderId, afterFeedId, beforeFeedId, subscription.favicon, fetched,
                    subscription.storedBeforeFetch, subscription.observedBatchWrites, batch,
                    articleRowsFeedId, articleRows,
                )
            }
            // Recorded only once the transaction has committed, so a rolled-back write leaves no
            // record — and only the groups this write replaced, not values it kept from another writer.
            batch?.record(
                written.feedId,
                content = written.values.withoutFavicon(),
                replacedContent = written.replacedContent,
                favicon = written.values.faviconUrl,
                replacedFavicon = written.replacedFavicon,
            )
            written
        }
        // In a batch, the write and its record exclude the batch's prefetch reads (see SubscribeBatch),
        // so a prefetch never reads the row and the count from either side of this write. The batch
        // lock is always taken before the placement lock, and a prefetch takes only the batch lock.
        val written = if (batch != null) batch.write(writeAndRecord) else writeAndRecord()
        val feedId = written.feedId
        val wasNewlyCreated = written.wasNewlyCreated
        syncScheduler.scheduleSync()
        return FeedWriteOutcome(
            Result.Ok(feeds.getById(feedId).executeAsOne()),
            hadArticles = fetched.articles.isNotEmpty(),
            wasNewlyCreated = wasNewlyCreated,
        )
    }

    /** Indexes any articles fetched during an OPML import loop. Called once by [OpmlImporter]. */
    internal suspend fun indexImportedArticles() = ftsManager.indexMissing()

    /**
     * Unsubscribes from a feed by marking it as deleted.
     *
     * @param id The ID of the feed to unsubscribe from.
     */
    fun unsubscribeFeed(id: String) {
        val now = clock.nowMillis()
        feeds.softDelete(now, now, now, id)
        syncScheduler.scheduleSync()
    }

    fun renameFeed(id: String, customTitle: String?) {
        val name = customTitle?.takeIf { it.isNotBlank() }
        val now = clock.nowMillis()
        feeds.updateCustomTitle(name, now, now, id)
        syncScheduler.scheduleSync()
    }

    /**
     * The `sort_order` a brand-new feed should be inserted at, within [folderId]'s group.
     * [afterFeedId] wins when it names a feed in that group (the new feed lands directly after it);
     * otherwise [beforeFeedId], when it names a feed in that group, puts the new feed directly
     * before it (how a folder selection places a new feed at the folder's start). When neither
     * anchor is given, or neither is in that group any more (stale selection, race with a
     * concurrent move), falls back to appending at the end. Otherwise renumbers the group around
     * the resolved position, writing [feeds.updateSortOrder] only for the siblings whose index
     * actually shifted (same pattern as [moveFeed]) — the new feed's own row is inserted separately
     * by the caller's `upsert`, using the returned value.
     */
    private fun insertionSortOrderForNewFeed(
        feedId: String,
        folderId: String?,
        afterFeedId: String?,
        beforeFeedId: String?,
        now: Long,
    ): Long {
        if (afterFeedId == null && beforeFeedId == null) return feeds.nextSortOrderInGroup(folderId).executeAsOne()
        val group = feeds.getByFolder(folderId).executeAsList()
        val groupIds = group.map { it.id }
        val afterIndex = afterFeedId?.let { groupIds.indexOf(it) } ?: -1
        // The feed the new one is placed *before* (reorderIds' insertion target); null = append.
        val targetFeedId = when {
            afterIndex >= 0 -> groupIds.getOrNull(afterIndex + 1)
            beforeFeedId != null && beforeFeedId in groupIds -> beforeFeedId
            else -> return feeds.nextSortOrderInGroup(folderId).executeAsOne()
        }
        val newOrder = reorderIds(groupIds, feedId, targetFeedId)
        val sortOrderOf = group.associate { it.id to it.sort_order }
        return applyReorderUpdates(newOrder, sortOrderOf, feedId, now, feeds)
    }

    /**
     * Moves a feed to a folder and positions it before the specified target feed.
     *
     * [folderId] is resolved by every statement that takes it (`getByFolder`,
     * `updateFolderAndSortOrder`; see feeds.sq / folders.sq): a folder a merge has re-keyed since the
     * caller read the id (a queued write, a drag) is the one the feed lands in, ordered among that
     * folder's feeds. Each statement resolves on its own, so the group is still read outside the
     * transaction (below) and the transaction still starts with a write.
     *
     * @param feedId The ID of the feed to move.
     * @param folderId The destination folder ID, or `null` for no folder.
     * @param targetFeedId The ID of the feed to place the moved feed before, or `null` to place it last.
     */
    suspend fun moveFeed(feedId: String, folderId: String?, targetFeedId: String? = null) {
        feedPlacementMutex.withLock {
            // The group is read *outside* the transaction on purpose: a deferred transaction that
            // reads and then writes has to upgrade its lock, which SQLite can refuse with an
            // immediate SQLITE_BUSY (see docs/known-issues.md). The mutex serializes the read and
            // the write against every other placement in this process instead.
            val current = feeds.getByFolder(folderId).executeAsList()
            val sortOrderOf = current.associate { it.id to it.sort_order }
            val newOrder = reorderIds(current.map { it.id }, feedId, targetFeedId)
            val now = clock.nowMillis()
            db.transaction {
                applyReorderUpdates(newOrder, sortOrderOf, feedId, now, feeds) { index ->
                    feeds.updateFolderAndSortOrder(folderId = folderId, sortOrder = index.toLong(), updatedAt = now, folderUpdatedAt = now, sortOrderUpdatedAt = now, id = feedId)
                }
            }
        }
        syncScheduler.scheduleSync()
    }

    /**
     * Runs [block] holding the feed placement lock (see [feedPlacementMutex]), for a caller that
     * places feeds as part of its own transaction — `FolderRepository.deleteFolder`, which calls
     * [moveFeedsOutOfFolderLocked] inside it.
     */
    internal suspend fun <T> withFeedPlacementLock(block: () -> T): T = feedPlacementMutex.withLock { block() }

    /**
     * Moves all feeds in [folderId] into the ungrouped feed list, appended after its current feeds
     * while preserving their relative order, stamping [now]. The caller must already hold the feed
     * placement lock ([withFeedPlacementLock]) — `FolderRepository.deleteFolder`, the one caller.
     */
    internal fun moveFeedsOutOfFolderLocked(folderId: String, now: Long) {
        val feedsInFolder = feeds.getByFolder(folderId).executeAsList()
        val startOrder = feeds.nextSortOrderInGroup(null).executeAsOne()
        db.transaction {
            feedsInFolder.forEachIndexed { index, feed ->
                feeds.updateFolderAndSortOrder(folderId = null, sortOrder = startOrder + index, updatedAt = now, folderUpdatedAt = now, sortOrderUpdatedAt = now, id = feed.id)
            }
        }
    }

    /**
     * The locked internals of [subscribeFeedWrite]. Reads the current feed row under the mutex,
     * decides the feed id and sort order from it, upserts the feed and its articles in one
     * transaction, and returns what it wrote.
     *
     * @param articleRowsFeedId The feed id [articleRows] were prepared for (the id a new row gets).
     * @param articleRows The fetched articles, prepared outside the lock.
     */
    private fun doSubscribeFeedWrite(
        effectiveUrl: String,
        folderId: String?,
        afterFeedId: String?,
        beforeFeedId: String?,
        favicon: String?,
        fetched: FetchedFeed,
        storedBeforeFetch: StoredFeedValues?,
        observedBatchWrites: Int,
        batch: SubscribeBatch?,
        articleRowsFeedId: String,
        articleRows: List<ArticleRepository.PreparedInsert>,
    ): SubscribeWrite {
        // Taken under the lock, not before waiting for it: a write committed while this call waited
        // (a refresh, a move) must not end up newer than the time stamped here.
        val now = clock.nowMillis()
        return db.transactionWithResult {
            // Read under the lock, and decide the id from this read only: a concurrent subscribe of
            // the same url, or a sync merge bringing it in under another device's id, may have
            // created the row while this call waited, and upserting a different id for the same url
            // would violate UNIQUE(url).
            val current = feeds.getByUrl(effectiveUrl).executeAsOneOrNull()
            // Deterministic id from the (redirect-resolved) url so two devices that subscribe the
            // same feed independently get the SAME id and the sync merge can converge them (existing
            // feeds keep their id). See IdGenerator.feedId. With no row for this url, that id can
            // still be taken: by a feed whose url a permanent redirect has since changed (upserting
            // it would rewrite that feed's url and drop its subscription), or by an id a re-key moved
            // onto another feed (ks_id_remap). A random id is used then, as a tag or folder does
            // (tags.sq's insertIfNameAbsent); it is decided from this transaction's own reads.
            val feedId = current?.id
                ?: IdGenerator.feedId(effectiveUrl).takeUnless { feeds.isIdTaken(it).executeAsOne() }
                ?: IdGenerator.newId()
            val sortOrder = when {
                current == null -> insertionSortOrderForNewFeed(feedId, folderId, afterFeedId, beforeFeedId, now)
                current.deleted_at == null -> current.sort_order
                else -> feeds.nextSortOrderInGroup(current.folder_id).executeAsOne()
            }
            // A live row whose feed values changed since the prefetch read them (a refresh of the
            // same feed, typically during an OPML re-import that fetched ahead) holds newer values
            // than [fetched]; keep them rather than roll them back. Two groups are judged on their
            // own: the content with the validators describing it (site URL, title, description,
            // ETag, Last-Modified — kept or replaced together, so a validator never ends up beside
            // content it does not describe), and the favicon, which a refresh's favicon lookup
            // writes alone (applyFavicon) — so a favicon stored meanwhile does not discard the
            // fetched content, nor the other way round. A change to anything else — a move, a
            // rename, a reorder — leaves both alone, so the fetched values are written. So are they
            // for a new row, a resubscribed one, and a row the prefetch never saw (no row yet, or a
            // redirect to another feed's URL). (error_count / last_error need no such guard:
            // upsertValidators never updates them on an existing row.) The articles follow this
            // decision, below.
            val fetchedValues = FeedValues(
                siteUrl = fetched.siteUrl,
                title = fetched.title ?: effectiveUrl,
                description = fetched.description,
                faviconUrl = favicon,
                etag = fetched.etag,
                lastModified = fetched.lastModified,
            )
            // Each group's baseline is this batch's own last write of that group to the row, when that
            // write came after the prefetch's read (see writePrefetchedSubscription's batch), else the
            // prefetch's read — only when it read this very row. No baseline means the fetched values
            // are written.
            // Read in this transaction, after `current`, so both describe the same committed state.
            val stored = current?.takeIf { it.deleted_at == null }?.let { storedFeedValues(effectiveUrl)?.values }
            val prefetchRead = storedBeforeFetch?.takeIf { current != null && it.feedId == current.id }?.values
            val contentBaseline = current?.let { batch?.contentWrittenAfter(it.id, observedBatchWrites) } ?: prefetchRead?.withoutFavicon()
            // The favicon can legitimately be null, so "a baseline exists" is tracked apart from its value.
            val ownFavicon = current?.let { batch?.faviconWrittenAfter(it.id, observedBatchWrites) }
            val hasFaviconBaseline = ownFavicon != null || prefetchRead != null
            val faviconBaseline = if (ownFavicon != null) ownFavicon.value else prefetchRead?.faviconUrl
            val keepContent = stored != null && contentBaseline != null && stored.withoutFavicon() != contentBaseline
            val keepFavicon = stored != null && hasFaviconBaseline && stored.faviconUrl != faviconBaseline
            val values = (if (keepContent) stored!! else fetchedValues)
                .copy(faviconUrl = if (keepFavicon) stored!!.faviconUrl else fetchedValues.faviconUrl)

            feeds.upsert(
                id = feedId,
                url = effectiveUrl,
                site_url = values.siteUrl,
                title = values.title,
                description = values.description,
                favicon_url = values.faviconUrl,
                custom_title = current?.custom_title,
                deleted_at = null,
                updated_at = now,
                created_at = current?.created_at ?: now,
                sort_order = sortOrder,
            )
            // The validators describing the content just written (kept or fetched, together), in the
            // feed's device-local fetch state. A new row starts with no errors; an existing row's
            // error state is left alone, as the feed row's upsert used to leave it.
            fetchStates.upsertValidators(feedId, values.etag, values.lastModified)
            // Files a brand-new feed into the folder selected in the feed list at add time. A
            // re-subscribed feed (current != null) keeps its prior folder instead — upsert never
            // touches folder_id, so there's nothing to do for that case.
            if (current == null && folderId != null) feeds.updateFolder(folderId = folderId, updatedAt = now, folderUpdatedAt = now, id = feedId)
            // Re-subscribing (feed was soft-deleted) is a subscription-state change: stamp its
            // last-wins timestamp so it propagates over another device's refresh on the next sync.
            if (current?.deleted_at != null) {
                feeds.stampResubscribed(now, feedId)
                // upsert moved it to the end of its old group; stamp that sort order too, or the merge's
                // per-field last-write-wins (mergeFeedSortOrder) would never carry the new position to
                // another device.
                feeds.updateSortOrder(sortOrder, now, now, feedId)
            }

            // The articles follow the feed values, in this same transaction: one that kept another
            // writer's newer content and validators (a refresh that landed after the prefetch) only
            // adds the articles not stored yet. Its own fetch is the older response, so overwriting
            // would put older bodies back under the kept validators — and a conditional request
            // answered 304 would never repair them. Written here rather than after the transaction,
            // so a refresh (which writes its validators and articles in one transaction too) can only
            // commit wholly before this write — and then shows in `current` above — or wholly after.
            // Rows prepared for another id (an existing row whose id predates deterministic ids) are
            // prepared again here: rare, and the only case doing that CPU work inside the transaction.
            val rows = if (feedId == articleRowsFeedId) articleRows else articleRepository.prepareRows(feedId, fetched.articles)
            articleRepository.insertPrepared(articleRepository.countPrepared(feedId, rows), overwriteExisting = !keepContent)

            SubscribeWrite(feedId, wasNewlyCreated = current == null, values, replacedContent = !keepContent, replacedFavicon = !keepFavicon)
        }
    }

    /**
     * What [doSubscribeFeedWrite] wrote: the feed's id, whether the row is new, its feed values, and
     * which of the two groups it replaced with fetched values rather than kept from another writer.
     */
    private class SubscribeWrite(
        val feedId: String,
        val wasNewlyCreated: Boolean,
        val values: FeedValues,
        val replacedContent: Boolean,
        val replacedFavicon: Boolean,
    )

    /**
     * Refreshes a feed and indexes any newly fetched articles.
     *
     * @param feed The feed to refresh.
     * @return The number of new articles, or the refresh error.
     */
    suspend fun refreshFeed(feed: Feeds): Result<Int> {
        val result = refreshFeeds(listOf(feed)).getValue(feed.id)
        // Unlike refreshFeeds (only ever run inside a RefreshCycleRunner cycle, which syncs after it),
        // a single-feed refresh has no sync of its own to ride on.
        syncScheduler.scheduleSync()
        return result
    }

    /**
     * Refreshes the feed [id] names, re-reading its row first so the conditional request carries
     * the feed's current `etag` / `last_modified` rather than whatever a caller's (possibly stale)
     * copy of the row holds.
     *
     * @return The refresh result, or `null` when no subscribed feed has that id (it was
     *   unsubscribed in the meantime), in which case nothing is fetched.
     */
    suspend fun refreshFeedById(id: String): Result<Int>? {
        val feed = getFeedById(id)?.takeIf { it.deleted_at == null } ?: return null
        return refreshFeed(feed)
    }

    /** Outcome of [applyFetch]: the article-count result, plus whether any articles were fetched. */
    private data class RefreshOutcome(val result: Result<Int>, val hadArticles: Boolean)

    /** What [refreshFeeds]' fetches hand to its single writer, in the order they happen. */
    private sealed interface RefreshStep {
        /**
         * [feed]'s fetch finished — apply it ([applyFetch]), then complete [committed]. An Ok [fetch]
         * carries the fetched articles' rows, already prepared off the writer.
         */
        class Fetched(
            val feed: Feeds,
            val fetchState: Feed_fetch_state?,
            val fetch: Result<PreparedFetch>,
            val committed: CompletableDeferred<Unit>,
        ) : RefreshStep

        /** A favicon lookup for [feedId] finished; [url] is null when it found none. */
        class FaviconResolved(val feedId: String, val url: String?) : RefreshStep
    }

    /** A successful fetch, together with its articles' [rows] as prepared off the writer. */
    private class PreparedFetch(val fetched: FetchedFeed, val rows: List<ArticleRepository.PreparedInsert>)

    /** A favicon lookup a fetched feed is due, from [siteUrl] (null when the feed names no site). */
    private class FaviconLookup(val siteUrl: String?)

    /**
     * Whether a refresh that fetched [feed] should look its favicon up: it has none yet, and no
     * lookup found none within [FAVICON_RETRY_INTERVAL_MS].
     */
    private fun needsFaviconLookup(feed: Feeds): Boolean {
        if (!feed.favicon_url.isNullOrEmpty()) return false
        val missedAt = faviconMisses.missedAt(feed.id) ?: return true
        return clock.nowMillis() - missedAt >= FAVICON_RETRY_INTERVAL_MS
    }

    /**
     * Stores a favicon resolved after [feedId]'s own refresh writes, unless the feed has been
     * unsubscribed or given a favicon meanwhile, or records that none was found. Best effort: a
     * failed write is logged and left for the next refresh rather than failing this one.
     */
    private fun applyFavicon(step: RefreshStep.FaviconResolved) {
        val url = step.url
        if (url == null) {
            faviconMisses.recordMiss(step.feedId, clock.nowMillis())
            return
        }
        faviconMisses.clearMiss(step.feedId)
        try {
            val written = feeds.updateFaviconUrlIfMissing(url, clock.nowMillis(), step.feedId).value
            if (written > 0) syncScheduler.scheduleSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn(TAG, "Storing a resolved favicon failed", e)
        }
    }

    /**
     * Applies fetched feed data, persists its articles, and emits notifications for permanent removal or URL changes.
     *
     * @param feed The feed being refreshed.
     * @param state [feed]'s fetch state as read when the refresh started (the one whose validators
     *   the fetch sent), or `null` when it had none.
     * @param fetch The feed's fetch result, with its prepared article rows when Ok.
     * @return The article update result and whether the fetch contained articles.
     */
    private suspend fun applyFetch(
        feed: Feeds,
        state: Feed_fetch_state?,
        fetch: Result<PreparedFetch>,
    ): RefreshOutcome {
        val ok = when (val r = fetch) {
            is Result.Ok -> r.value
            is Result.Err -> {
                val ex = r.exception
                if (ex !is FeedNotFoundException) {
                    fetchStates.incrementErrorCount(feed.id, ex.messageText)
                }
                if (ex is FeedNotFoundException && ex.isGone) {
                    // error_count stays untouched (410 is permanent, not a retry candidate), so record
                    // the reason in last_error instead — that's what keeps the feed list flagged after
                    // the notification is dismissed. Cleared by resetErrorCount if the feed comes back.
                    // Written only on the transition: a gone feed is still refreshed every cycle, and
                    // any write re-runs the queries watching the fetch state. The check reads the state
                    // taken before the fetch, so a marker cleared meanwhile (a single-feed Refresh that
                    // succeeded) is not rewritten this cycle; the next one rereads the state and
                    // restores it — the same assumption as the resetErrorCount guard below. Not
                    // checked in SQL instead: SQLDelight re-runs the queries even when no row changes.
                    if (state?.last_error != FEED_ERROR_REASON_GONE) {
                        fetchStates.markGone(feed.id, FEED_ERROR_REASON_GONE)
                    }
                    // Coalesced: a gone feed is still refreshed every cycle, and each one would otherwise
                    // add another copy of the same warning for as long as the session runs. The key
                    // includes the title, so a feed renamed in between gets an entry of its own.
                    notify(
                        NotificationText.FeedGone(feed.displayTitle()),
                        AppNotificationLevel.WARNING,
                        action = AppNotificationAction.ShowFeedDetail(feed.id),
                        coalesce = true,
                    )
                }
                return RefreshOutcome(r, hadArticles = false)
            }
        }
        val fetched = ok.fetched

        // The CPU-heavy half of the preparation already ran on the fetch's own coroutine (see
        // launchFetches), so it neither holds the write lock nor queues behind other feeds' writes.
        // Only the half that reads the DB runs here, outside the transaction below (the same reason
        // upsertParsed precomputes before opening its own transaction), so the new-article count
        // compares against what is stored now, not when the fetch finished.
        val prepared = articleRepository.countPrepared(feed.id, ok.rows)
        // The target itself rather than a boolean: the write below and the notification after the
        // commit both need the value, and carrying it keeps its non-nullness with it at both sites.
        // Non-null exactly when the fetch reported a permanent redirect somewhere we are not already.
        val redirectTarget = fetched.redirectUrl?.takeIf { it != feed.url }
        // One reading for the whole apply phase, taken before the transaction opens: every write
        // below stamps the same `updated_at`, and no wall-clock read happens under the write lock.
        val now = clock.nowMillis()

        // One transaction for this feed's whole apply phase. SQLDelight defers notifyQueries to the
        // outermost commit, so the feed's writes and its article upsert produce a single round of
        // listener notifications instead of one per statement — and the article-list query, which
        // joins `feeds`, is registered against both tables and would otherwise re-run for each.
        // Deliberately per feed, not around the whole refreshAll loop: a loop-wide transaction would
        // hold the write lock for the entire apply phase (starving concurrent search / mark-as-read
        // of the busy_timeout) and would break the documented incremental appearance of articles.
        //
        // Each write is additionally guarded on the value actually changing, so a refresh that
        // changed nothing writes nothing at all and notifies no one. The fetch state lives in its
        // own device-local table, so its writes never move the feed row's sync stamps.
        // A redirect's handling opens the transaction with a write (see [redirectFirstWrite]), and the
        // rest of the apply phase keeps its order after it.
        val sameHostRedirect = redirectTarget != null && isSameHost(feed.url, redirectTarget)
        val applied = db.transactionWithResult {
            if (redirectTarget != null) redirectFirstWrite(feed.id, redirectTarget, sameHostRedirect, now)

            if (state != null && (state.error_count != 0L || state.last_error != null)) {
                fetchStates.resetErrorCount(feed.id)
            }

            // A 304 carries no validators, so writing them back would erase the ones that produced it.
            if (!fetched.notModified &&
                (fetched.etag != state?.etag || fetched.lastModified != state?.last_modified)
            ) {
                fetchStates.upsertValidators(feed.id, fetched.etag, fetched.lastModified)
            }

            if (fetched.title != null || fetched.description != null) {
                // Refresh only writes the content columns it owns. It must NOT touch deleted_at /
                // sort_order / custom_title / favicon_url: refreshAll fetches from a snapshot that can be
                // stale for the whole concurrent-fetch phase, so writing those back would revert a
                // concurrent unsubscribe / reorder / rename (and resurrect a just-deleted feed). Those
                // fields are handled by their own dedicated statements or left to the user's edits.
                val siteUrl = fetched.siteUrl ?: feed.site_url
                val title = fetched.title ?: feed.title
                val description = fetched.description ?: feed.description
                if (siteUrl != feed.site_url || title != feed.title || description != feed.description) {
                    feeds.updateContent(
                        site_url = siteUrl,
                        title = title,
                        description = description,
                        updated_at = now,
                        id = feed.id,
                    )
                }
            }

            // Read only after the redirect's first write, never in a transaction without one.
            val redirect = redirectTarget?.let { completeRedirect(feed.id, it, sameHostRedirect, now, prepared) }
            AppliedFetch(
                newCount = articleRepository.insertPrepared(redirect?.merge?.recounted ?: prepared),
                mergedInto = redirect?.merge?.canonicalId,
                urlMoved = redirect?.urlMoved == true,
            )
        }
        if (applied.mergedInto != null) {
            // The merge's raw statements bypass SQLDelight's listeners (feed_tags and the fetch state are
            // not written by any generated statement here), so the lists are told after the commit. The
            // generated statements in the same transaction (the revival, the URL, the article insert)
            // already notified their listeners at that commit — before this publish — so a holder of an
            // id the merge moved must catch up from the database (ks_id_remap) before it decides the id
            // is gone. In `finally`, as after a sync merge, so a publish that throws (cancellation
            // included) still leaves the lists told.
            try {
                publishIdRemapsAfterMerge()
            } finally {
                driver.notifyListeners("feeds", "articles", "feed_tags", "feed_fetch_state")
            }
        }

        // Notifications are emitted after the commit, so a listener never observes a half-applied
        // feed. `notify` is declared `suspend` so it cannot be called from inside the transaction
        // block by mistake. A redirect whose target another feed holds on another host left the URL
        // alone, so nothing changed to announce.
        if (applied.urlMoved) {
            notify(
                NotificationText.FeedUrlChanged(feed.displayTitle()),
                AppNotificationLevel.WARNING,
                action = AppNotificationAction.ShowFeedDetail(applied.mergedInto ?: feed.id),
            )
        }
        // No sync is scheduled here: a refresh of many feeds would otherwise arm the debounce once
        // per feed and could fire a whole-DB upload mid-refresh. RefreshCycleRunner covers the batch
        // (it syncs right after, or schedules one when the cycle synced first); the single-feed
        // refreshFeed() schedules its own.
        return RefreshOutcome(Result.Ok(applied.newCount), fetched.articles.isNotEmpty())
    }

    /** What [applyFetch]'s transaction did: the new-article count, the merge's canonical id, whether the URL moved. */
    private data class AppliedFetch(val newCount: Int, val mergedInto: String?, val urlMoved: Boolean)

    /** A redirect merge's outcome: the identity's canonical id, and the fetched articles recounted against it. */
    private data class RedirectMerge(val canonicalId: String, val recounted: ArticleRepository.PreparedArticles)

    /**
     * The first statement of a redirected refresh's transaction — always a write. SQLite's deferred
     * BEGIN takes the write lock only at the first write, and a transaction whose first statement is a
     * read can then fail that upgrade with a non-retryable SQLITE_BUSY when another connection
     * committed in between, without waiting out `busy_timeout`. Opening with a write makes every read
     * after it (the holder, the merge) run under the write lock.
     *
     * - Same host: records the identity edge with the feed holding [target], if any
     *   (`recordRedirectEdge`); the merge follows once the rest of the apply phase has run.
     * - Another host: never merged — a redirect to another site is no proof the two feeds are one.
     *   The URL write is guarded on no other feed holding [target], so it leaves the URL alone then,
     *   rather than failing UNIQUE(url) on this and every later refresh.
     */
    /** Whether [from] and [to] name the same host — case-insensitive, scheme and port ignored. */
    private fun isSameHost(from: String, to: String): Boolean {
        val fromHost = runCatching { Url(from).host }.getOrNull() ?: return false
        val toHost = runCatching { Url(to).host }.getOrNull() ?: return false
        return fromHost.isNotEmpty() && fromHost.equals(toHost, ignoreCase = true)
    }

    private fun redirectFirstWrite(feedId: String, target: String, sameHost: Boolean, now: Long) {
        if (sameHost) feeds.recordRedirectEdge(own = feedId, target = target)
        // With a holder this writes nothing: on the same host the merge moves the URL; on another host
        // it stays. A no-op too when the row already has the target (a merge brought it since the snapshot).
        feeds.updateUrl(target, now, feedId)
    }

    /** A redirect's outcome: the merge, when there was one, and whether the feed now holds the target. */
    private data class RedirectOutcome(val merge: RedirectMerge?, val urlMoved: Boolean)

    /**
     * The rest of a redirect after [redirectFirstWrite] and the apply phase's other writes. A same-host
     * redirect onto another feed's URL means the two are one feed (that feed subscribed at the moved URL
     * here, or merged in from another device): they are merged ([mergeRedirectCollision]). Otherwise
     * the guarded URL write already ran; the URL has moved when the feed now holds [target] (a merge may
     * have brought it since the snapshot), and not when another feed holds it.
     */
    private fun completeRedirect(
        feedId: String,
        target: String,
        sameHost: Boolean,
        now: Long,
        prepared: ArticleRepository.PreparedArticles,
    ): RedirectOutcome {
        val ownId = feeds.resolveId(feedId).executeAsOne()
        val holder = if (sameHost) feeds.getByUrl(target).executeAsOneOrNull() else null
        if (holder != null && holder.id != ownId) {
            return RedirectOutcome(mergeRedirectCollision(ownId, holder, target, now, prepared), urlMoved = true)
        }
        return RedirectOutcome(merge = null, urlMoved = feeds.getById(ownId).executeAsOneOrNull()?.url == target)
    }

    /**
     * Merges the feed [ownId] with [holder], which holds the redirect [target], onto their identity's
     * canonical id inside the caller's transaction ([FeedIdentitySql.mergeLocally]); the edge then
     * reaches the other devices with the next send, so they merge the same rows. The fetched articles
     * land in the canonical feed (the insert resolves the feed id through ks_id_remap and conflicts on
     * (feed_id, guid)), so the new-article count read before the merge is recounted against it.
     */
    private fun mergeRedirectCollision(
        ownId: String,
        holder: Feeds,
        target: String,
        now: Long,
        prepared: ArticleRepository.PreparedArticles,
    ): RedirectMerge {
        // Read before the merge: whether the user still had either feed.
        val anyAlive = holder.deleted_at == null || feeds.getById(ownId).executeAsOneOrNull()?.deleted_at == null
        FeedIdentitySql.mergeLocally(driver, ownId, holder.id)
        val canonicalId = feeds.resolveId(ownId).executeAsOne()
        // The merge's subscription state is plain last-write-wins, so a tombstone holding the target can
        // unsubscribe the merged feed. A redirect must never silently unsubscribe a feed the user still
        // has: that case is a re-subscribe, stamped above every folded stamp so it also wins on the other
        // devices. Two tombstones stay deleted.
        if (anyAlive) feeds.reviveAfterMerge(now, canonicalId)
        // Writes only when the merged feed does not hold the target yet (its content group came from this
        // feed): updateUrl is guarded on the url changing, so it never moves updated_at — a stamp the
        // capture trigger does not watch — without a captured change.
        feeds.updateUrl(target, now, canonicalId)
        return RedirectMerge(canonicalId, articleRepository.recountPrepared(prepared, canonicalId))
    }

    /**
     * Brings the ids held outside the database onto the canonical ids a redirect's feed merge moved
     * rows to. Logged, not thrown: the merge has committed and every write resolves a stale id itself
     * (feeds.sq / articles.sq), so the next publish (a sync merge, the next launch) catches up.
     */
    private suspend fun publishIdRemapsAfterMerge() {
        try {
            publishIdRemaps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.warn(TAG, "Publishing id re-keys after a redirect merge failed; the next merge or launch catches up: ${e.message}")
        }
    }

    /**
     * Refreshes all feeds and collects the article-count result for each feed.
     *
     * Network fetching is performed concurrently with bounded concurrency, while database updates
     * are applied serially as each feed's fetch completes — see [refreshFeeds].
     *
     * @return A map from feed ID to the result containing the number of newly processed articles.
     */
    suspend fun refreshAll(): Map<String, Result<Int>> = refreshFeeds(feedsCoveredBy(ArticleFilter.All))

    /**
     * The subscribed feeds whose articles [filter]'s article list shows, read from the DB at call
     * time — the set a refresh of that list has to fetch:
     *
     * - [ArticleFilter.All] / [ArticleFilter.Starred]: every subscribed feed (starred articles can
     *   come from any feed).
     * - [ArticleFilter.Feed]: that feed, if it is still subscribed.
     * - [ArticleFilter.Folder]: the subscribed feeds filed in the folder.
     * - [ArticleFilter.Tag]: the subscribed feeds the tag is currently attached to.
     *
     * The narrow cases are the same row sets `articles.sq`'s `watchByFeed` / `watchByFolder` /
     * `watchByTag` draw their articles from, restricted to subscribed feeds (`watchByFeed` itself
     * does not join `feeds`, but an unsubscribed feed has nothing left to fetch). An empty list
     * means the selection covers no subscribed feed at all (an empty folder or tag, or an
     * unsubscribed feed). Ordered like [getAllFeeds].
     */
    fun feedsCoveredBy(filter: ArticleFilter): List<Feeds> = when (filter) {
        ArticleFilter.All, ArticleFilter.Starred -> getAllFeeds()
        is ArticleFilter.Feed -> getAllFeeds().filter { it.id == filter.feedId }
        is ArticleFilter.Folder -> feeds.getByFolder(filter.folderId).executeAsList()
        is ArticleFilter.Tag -> {
            val tagged = db.feed_tagsQueries.watchFeedIdsForTag(filter.tagId).executeAsList().toSet()
            getAllFeeds().filter { it.id in tagged }
        }
    }

    /**
     * Refreshes [feedList] (e.g. [feedsCoveredBy] the article list's current selection), fetching
     * concurrently with bounded concurrency while applying each feed's DB writes as soon as its own
     * fetch completes — serially, from this one coroutine — and finally indexing any new articles
     * for search.
     *
     * Applying in completion order rather than list order keeps one slow feed from holding back
     * every faster feed's articles (and its [refreshingFeedIds] entry) until the slowest fetch ends.
     *
     * Pass rows freshly read from the DB (as [feedsCoveredBy] returns them) rather than a
     * long-lived UI snapshot, so a stale etag / URL is never used for the fetch.
     *
     * @param feedList The feeds to refresh.
     * @return A map from feed ID to the result containing the number of newly processed articles,
     * in [feedList]'s order.
     */
    suspend fun refreshFeeds(feedList: List<Feeds>): Map<String, Result<Int>> {
        val state = RefreshApplyState(total = feedList.size)
        try {
            coroutineScope {
                // Unlimited, and sent to only after the fetch permit is released (see launchFetches):
                // a fetch that finishes while the writer is busy applying another feed must free its
                // permit for the next fetch rather than wait for the writer. How many results can
                // queue here is bounded separately, by REFRESH_PENDING_APPLY_LIMIT.
                val steps = Channel<RefreshStep>(Channel.UNLIMITED)
                // Read with [feedList] (fresh from the DB, per the KDoc above): the validators each
                // conditional request sends, and the error state applyFetch compares against.
                val fetchStatesAtStart = fetchStates.getAll().executeAsList().associateBy { it.feed_id }
                launchFetches(feedList, fetchStatesAtStart, steps)
                applySteps(steps, state)
            }
        } catch (e: Exception) {
            indexAfterFailure(e, state)
            throw e
        }
        indexIfNeeded(state)
        return state.resultsInOrder(feedList)
    }

    /**
     * Network phase of [refreshFeeds]: every feed's fetch runs concurrently (bounded by a
     * semaphore), with NO DB writes, handing its result to [steps]. A second semaphore bounds how
     * many feeds sit between starting their fetch and being committed
     * ([REFRESH_PENDING_APPLY_LIMIT]): a writer that falls that far behind pauses further fetches
     * rather than letting their parsed articles pile up in memory. Each feed stays in
     * [refreshingFeedIds] from the start, including while queued for a fetch slot, until the writer
     * has committed it — so every feed the refresh covers is marked at once and clears as its own
     * articles land. A favicon lookup
     * follows that commit under a permit of its own, so it never delays that feed's articles. [steps]
     * is closed once every fetch has finished.
     */
    private fun CoroutineScope.launchFetches(
        feedList: List<Feeds>,
        fetchStatesAtStart: Map<String, Feed_fetch_state>,
        steps: SendChannel<RefreshStep>,
    ) {
        val semaphore = Semaphore(FEED_FETCH_CONCURRENCY)
        val pendingApply = Semaphore(REFRESH_PENDING_APPLY_LIMIT)
        val fetches = feedList.map { feed ->
            val state = fetchStatesAtStart[feed.id]
            launch(dispatcher) {
                val faviconLookup = trackRefreshing(feed.id) {
                    // The pending-apply permit is taken only once this feed's turn for a fetch slot
                    // comes, and inside trackRefreshing: every feed keeps its place in the fetch queue
                    // ahead of any favicon lookup (which queues for the same slots), and is marked
                    // refreshing from the start. A writer more than REFRESH_PENDING_APPLY_LIMIT behind
                    // holds the fetch slots until it catches up — the point of the bound.
                    fetchHoldingPendingApply(
                        semaphore,
                        pendingApply,
                        fetch = { feedFetcher.fetch(feed.url, state?.etag, state?.last_modified) },
                    ) { fetch ->
                        // Holds no fetch permit while waiting, so a writer within
                        // REFRESH_PENDING_APPLY_LIMIT of the fetches never caps the fetch concurrency.
                        // The articles' rows are built here, concurrently across feeds and holding
                        // no fetch permit, rather than on the single writer, which would otherwise
                        // parse every feed's article HTML one after another. Only an Ok fetch has
                        // any; an exception propagates like any other failure of this coroutine.
                        val prepared = fetch.map { PreparedFetch(it, articleRepository.prepareRows(feed.id, it.articles)) }
                        val committed = CompletableDeferred<Unit>()
                        steps.send(RefreshStep.Fetched(feed, state, prepared, committed))
                        committed.await()
                        // Only the site URL outlives the commit: the lookup below can wait on a permit
                        // and the network for a while, and must not keep every parsed article resident
                        // meanwhile.
                        if (fetch is Result.Ok && needsFaviconLookup(feed)) {
                            FaviconLookup(fetch.value.siteUrl ?: feed.site_url)
                        } else {
                            null
                        }
                    }
                }
                if (faviconLookup != null) {
                    val url = semaphore.withPermit { faviconResolver.resolve(faviconLookup.siteUrl, feed.url) }
                    steps.send(RefreshStep.FaviconResolved(feed.id, url))
                }
            }
        }
        launch {
            fetches.joinAll()
            steps.close()
        }
    }

    /**
     * Runs [fetch] under a [fetchSlots] permit, taking a [pendingApply] permit inside it, then hands
     * the result to [untilCommitted] with only the pending-apply permit still held. That permit is
     * acquired and released here alone, on every path — a cancelled fetch and a failed writer too.
     */
    private suspend fun <T, R> fetchHoldingPendingApply(
        fetchSlots: Semaphore,
        pendingApply: Semaphore,
        fetch: suspend () -> T,
        untilCommitted: suspend (T) -> R,
    ): R {
        var holdsPendingApply = false
        try {
            val fetched = fetchSlots.withPermit {
                pendingApply.acquire()
                holdsPendingApply = true
                fetch()
            }
            return untilCommitted(fetched)
        } finally {
            if (holdsPendingApply) pendingApply.release()
        }
    }

    /**
     * Apply phase of [refreshFeeds]: this coroutine alone applies every step, in the order they
     * happen, so writes stay single-threaded while a slow feed no longer holds back every other
     * feed's articles. Each feed's upsert notifies watchAll, so articles appear as their feed lands;
     * the search index catches up once every feed has been applied, without waiting for any favicon
     * lookup still running. Runs on the caller's coroutine, never a child of its own.
     */
    private suspend fun applySteps(steps: ReceiveChannel<RefreshStep>, state: RefreshApplyState) {
        for (step in steps) {
            when (step) {
                is RefreshStep.Fetched -> {
                    val outcome = applyFetch(step.feed, step.fetchState, step.fetch)
                    val wasLast = state.recordFetched(step.feed.id, outcome.result, outcome.hadArticles)
                    step.committed.complete(Unit)
                    if (wasLast) indexIfNeeded(state)
                }
                is RefreshStep.FaviconResolved -> applyFavicon(step)
            }
        }
    }

    /** Brings the search index up to date once, if any applied feed had articles. */
    private suspend fun indexIfNeeded(state: RefreshApplyState) {
        if (state.takeIndexingDue()) ftsManager.indexMissing()
    }

    /**
     * Feeds applied before [failure] stay committed; index them so they don't stay out of search
     * until the next refresh. A cancellation (the whole refresh was abandoned) skips this
     * O(articles) scan: the next hot-path indexMissing picks the rows up. An index failure is
     * attached to [failure] rather than replacing it.
     */
    private suspend fun indexAfterFailure(failure: Exception, state: RefreshApplyState) {
        if (failure is CancellationException) return
        try {
            indexIfNeeded(state)
        } catch (indexFailure: Exception) {
            failure.addSuppressed(indexFailure)
        }
    }

    /** What [refreshFeeds]' apply phase has done so far. Touched by the applying coroutine only. */
    private class RefreshApplyState(private val total: Int) {
        private val results = HashMap<String, Result<Int>>(total)
        private var applied = 0
        private var anyHadArticles = false
        private var indexed = false

        /** Records a feed's outcome; returns whether it was the last feed to be applied. */
        fun recordFetched(feedId: String, result: Result<Int>, hadArticles: Boolean): Boolean {
            results[feedId] = result
            if (hadArticles) anyHadArticles = true
            return ++applied == total
        }

        /** Whether the search index is due — some feed had articles and it hasn't run — claiming it. */
        fun takeIndexingDue(): Boolean {
            if (!anyHadArticles || indexed) return false
            indexed = true
            return true
        }

        /** The results keyed in [feedList]'s order, as callers have always received them. */
        fun resultsInOrder(feedList: List<Feeds>): Map<String, Result<Int>> =
            feedList.associateTo(LinkedHashMap()) { it.id to results.getValue(it.id) }
    }

    /** Runs [block] with [feedId] counted in [refreshingFeedIds], releasing it however [block] ends. */
    private suspend fun <T> trackRefreshing(feedId: String, block: suspend () -> T): T {
        _refreshingFeedCounts.update { it + (feedId to (it[feedId] ?: 0) + 1) }
        try {
            return block()
        } finally {
            _refreshingFeedCounts.update {
                val remaining = (it[feedId] ?: 1) - 1
                if (remaining > 0) it + (feedId to remaining) else it - feedId
            }
        }
    }

    /**
     * Adds a notification with the specified text and severity level.
     *
     * @param text What the notification says.
     * @param level The notification severity level.
     * @param action The next action offered when the user acts on the notification.
     * @param coalesce Whether this replaces an entry already saying the same thing
     *   ([NotificationCenter.addCoalescing]) rather than adding another.
     */
    private suspend fun notify(
        text: NotificationText,
        level: AppNotificationLevel,
        action: AppNotificationAction? = null,
        coalesce: Boolean = false,
    ) {
        val notification = newNotification(
            level = level,
            text = text,
            nowMillis = clock.nowMillis(),
            action = action,
        )
        if (coalesce) notificationCenter.addCoalescing(notification) else notificationCenter.add(notification)
    }
}

/**
 * Iterates over the reordered feed-id list [newOrder] and writes the new sort order (stamped with
 * [now]) only for feeds whose order differs from [previousSortOrderOf]. [feedId], the feed being
 * moved/inserted, is skipped; the caller provides [onFeedId] for its own write (e.g. folder update).
 *
 * @return The new sort order of [feedId].
 */
private inline fun applyReorderUpdates(
    newOrder: List<String>,
    previousSortOrderOf: Map<String, Long>,
    feedId: String,
    now: Long,
    feedsQueries: FeedsQueries,
    onFeedId: (index: Int) -> Unit = {},
): Long {
    var newFeedSortOrder = 0L
    newOrder.forEachIndexed { index, id ->
        if (id == feedId) {
            newFeedSortOrder = index.toLong()
            onFeedId(index)
        } else if (previousSortOrderOf[id] != index.toLong()) {
            feedsQueries.updateSortOrder(index.toLong(), now, now, id)
        }
    }
    return newFeedSortOrder
}

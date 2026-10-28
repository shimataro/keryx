package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
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
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FAVICON_RETRY_INTERVAL_MS
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.FeedsQueries
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.FetchedFeed

/**
 * Max feeds fetched concurrently in [FeedRepository.refreshAll] / [FeedRepository.refreshFeeds]'s
 * network phase. Bounded so a large subscription list doesn't open an unbounded number of sockets
 * at once; DB writes stay serial.
 */
private const val REFRESH_FETCH_CONCURRENCY = 6

/**
 * Max feeds a refresh holds between starting their fetch and the writer committing them — the
 * fetched results queued for the single writer included. Every queued result keeps its feed's parsed
 * articles resident, so without this a writer held back (a sync merge's write lock) would let a large
 * subscription list fetch its whole contents into memory at once. Twice the fetch concurrency, so a
 * writer that keeps up never stalls a fetch slot.
 */
private const val REFRESH_PENDING_APPLY_LIMIT = 2 * REFRESH_FETCH_CONCURRENCY

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
) {
    private val feeds get() = db.feedsQueries

    // Guards subscribeFeedWrite's sort-order allocation + upsert + folder assignment. A
    // db.transaction alone isn't enough: SQLite's deferred BEGIN means the nextSortOrderInGroup
    // SELECT below doesn't take a write lock, so two overlapping transactions could still both
    // read the same stale MAX before either writes. This mutex is the piece that actually
    // serializes that read-then-write, the same way OpmlImporter's own mutex serializes concurrent
    // import() runs against each other — but that lock doesn't cover a concurrent call through
    // this class's other entry point (AddFeedPreviewResolver's "Add Feed" dialog path), so the
    // two need their own shared guard here where they converge.
    private val subscribePlacementMutex = Mutex()

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
        if (outcome.hadArticles) ftsManager.indexMissing()
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
    ): FeedWriteOutcome {
        val fetched = when (val r = feedFetcher.fetch(url)) {
            is Result.Ok -> r.value
            is Result.Err -> return FeedWriteOutcome(r, hadArticles = false, wasNewlyCreated = false)
        }
        val effectiveUrl = fetched.redirectUrl ?: url
        val existing = feeds.getByUrl(effectiveUrl).executeAsOneOrNull()
        // Deterministic id from the (redirect-resolved) url so two devices that subscribe the same
        // feed independently get the SAME id and the sync merge can converge them (existing feeds
        // keep their id). See IdGenerator.feedId.
        val feedId = existing?.id ?: IdGenerator.feedId(effectiveUrl)
        val now = clock.nowMillis()
        val favicon = faviconResolver.resolve(fetched.siteUrl, effectiveUrl)

        // sort_order: a brand-new feed is inserted right after afterFeedId (or, failing that, right
        // before beforeFeedId) within its target folder's group (the "no folder" group, unless the
        // caller passed a folderId to file it into), or appended at the end of that group when
        // neither anchor is given or found there;
        // a still-live feed being re-fetched keeps its existing position; a previously-unsubscribed
        // feed being re-subscribed is re-numbered to the end of the group it used to belong to (its
        // old relative position may no longer be meaningful after other feeds in that group moved
        // around while it was unsubscribed).
        val wasNewlyCreated = subscribePlacementMutex.withLock {
            doSubscribeFeedWrite(effectiveUrl, feedId, folderId, afterFeedId, beforeFeedId, now, favicon, fetched)
        }
        articleRepository.upsertParsed(feedId, fetched.articles)
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
     * @param feedId The ID of the feed to move.
     * @param folderId The destination folder ID, or `null` for no folder.
     * @param targetFeedId The ID of the feed to place the moved feed before, or `null` to place it last.
     */
    fun moveFeed(feedId: String, folderId: String?, targetFeedId: String? = null) {
        val current = feeds.getByFolder(folderId).executeAsList()
        val sortOrderOf = current.associate { it.id to it.sort_order }
        val newOrder = reorderIds(current.map { it.id }, feedId, targetFeedId)
        val now = clock.nowMillis()
        db.transaction {
            applyReorderUpdates(newOrder, sortOrderOf, feedId, now, feeds) { index ->
                feeds.updateFolderAndSortOrder(folderId, index.toLong(), now, now, now, feedId)
            }
        }
        syncScheduler.scheduleSync()
    }

    /**
     * Moves all feeds in a folder into the ungrouped feed list while preserving their relative order.
     *
     * @param folderId The ID of the folder whose feeds should be moved.
     * @param now The timestamp to apply to the moved feeds.
     */
    fun moveFeedsOutOfFolder(folderId: String, now: Long = clock.nowMillis()) {
        val feedsInFolder = feeds.getByFolder(folderId).executeAsList()
        val startOrder = feeds.nextSortOrderInGroup(null).executeAsOne()
        db.transaction {
            feedsInFolder.forEachIndexed { index, feed ->
                feeds.updateFolderAndSortOrder(null, startOrder + index, now, now, now, feed.id)
            }
        }
    }

    /**
     * The locked internals of [subscribeFeedWrite]. Re-reads the current feed row under the mutex
     * (the pre-lock snapshot may be stale), computes its sort order, upserts the feed, and returns
     * whether the row was newly created.
     */
    private fun doSubscribeFeedWrite(
        effectiveUrl: String,
        feedId: String,
        folderId: String?,
        afterFeedId: String?,
        beforeFeedId: String?,
        now: Long,
        favicon: String?,
        fetched: FetchedFeed,
    ): Boolean {
        return db.transactionWithResult {
            // Re-read under the lock: the snapshot taken before the fetch/favicon calls can be
            // stale, so a concurrent subscribe of the same url may already have created the row.
            val current = feeds.getByUrl(effectiveUrl).executeAsOneOrNull()
            val sortOrder = when {
                current == null -> insertionSortOrderForNewFeed(feedId, folderId, afterFeedId, beforeFeedId, now)
                current.deleted_at == null -> current.sort_order
                else -> feeds.nextSortOrderInGroup(current.folder_id).executeAsOne()
            }

            feeds.upsert(
                id = feedId,
                url = effectiveUrl,
                site_url = fetched.siteUrl,
                title = fetched.title ?: effectiveUrl,
                description = fetched.description,
                favicon_url = favicon,
                etag = fetched.etag,
                last_modified = fetched.lastModified,
                error_count = 0,
                last_error = null,
                custom_title = current?.custom_title,
                deleted_at = null,
                updated_at = now,
                created_at = current?.created_at ?: now,
                sort_order = sortOrder,
            )
            // Files a brand-new feed into the folder selected in the feed list at add time. A
            // re-subscribed feed (current != null) keeps its prior folder instead — upsert never
            // touches folder_id, so there's nothing to do for that case.
            if (current == null && folderId != null) feeds.updateFolder(folderId, now, now, feedId)
            // Re-subscribing (feed was soft-deleted) is a subscription-state change: stamp its
            // last-wins timestamp so it propagates over another device's refresh on the next sync.
            if (current?.deleted_at != null) feeds.stampResubscribed(now, feedId)

            current == null
        }
    }

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
        /** [feed]'s fetch finished — apply it ([applyFetch]), then complete [committed]. */
        class Fetched(
            val feed: Feeds,
            val fetch: Result<FetchedFeed>,
            val committed: CompletableDeferred<Unit>,
        ) : RefreshStep

        /** A favicon lookup for [feedId] finished; [url] is null when it found none. */
        class FaviconResolved(val feedId: String, val url: String?) : RefreshStep
    }

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
     * @param fetch The feed's fetch result.
     * @return The article update result and whether the fetch contained articles.
     */
    private suspend fun applyFetch(feed: Feeds, fetch: Result<FetchedFeed>): RefreshOutcome {
        val fetched = when (val r = fetch) {
            is Result.Ok -> r.value
            is Result.Err -> {
                val ex = r.exception
                if (ex !is FeedNotFoundException) {
                    feeds.incrementErrorCount(ex.messageText, clock.nowMillis(), feed.id)
                }
                if (ex is FeedNotFoundException && ex.isGone) {
                    // error_count stays untouched (410 is permanent, not a retry candidate), so record
                    // the reason in last_error instead — that's what keeps the feed list flagged after
                    // the notification is dismissed. Cleared by resetErrorCount if the feed comes back.
                    feeds.markGone(FEED_ERROR_REASON_GONE, clock.nowMillis(), feed.id)
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

        // CPU-heavy preparation stays outside the transaction below, so it doesn't hold the write
        // lock (the same reason upsertParsed precomputes before opening its own transaction).
        val prepared = articleRepository.prepareParsed(feed.id, fetched.articles)
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
        // Each `feeds` write is additionally guarded on the value actually changing, so a refresh
        // that changed nothing writes nothing at all and notifies no one.
        val newCount = db.transactionWithResult {
            if (feed.error_count != 0L || feed.last_error != null) {
                feeds.resetErrorCount(now, feed.id)
            }

            // A 304 carries no validators, so writing them back would erase the ones that produced it.
            if (!fetched.notModified &&
                (fetched.etag != feed.etag || fetched.lastModified != feed.last_modified)
            ) {
                feeds.updateCacheHeaders(fetched.etag, fetched.lastModified, now, feed.id)
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

            if (redirectTarget != null) {
                feeds.updateUrl(redirectTarget, now, feed.id)
            }

            articleRepository.insertPrepared(prepared)
        }

        // Notifications are emitted after the commit: `notify` suspends, which a transaction block
        // cannot host, and a listener must not observe a half-applied feed anyway.
        if (redirectTarget != null) {
            notify(
                NotificationText.FeedUrlChanged(feed.displayTitle()),
                AppNotificationLevel.WARNING,
                action = AppNotificationAction.ShowFeedDetail(feed.id),
            )
        }
        // No sync is scheduled here: a refresh of many feeds would otherwise arm the debounce once
        // per feed and could fire a whole-DB upload mid-refresh. RefreshCycleRunner covers the batch
        // (it syncs right after, or schedules one when the cycle synced first); the single-feed
        // refreshFeed() schedules its own.
        return RefreshOutcome(Result.Ok(newCount), fetched.articles.isNotEmpty())
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
                launchFetches(feedList, steps)
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
    private fun CoroutineScope.launchFetches(feedList: List<Feeds>, steps: SendChannel<RefreshStep>) {
        val semaphore = Semaphore(REFRESH_FETCH_CONCURRENCY)
        val pendingApply = Semaphore(REFRESH_PENDING_APPLY_LIMIT)
        val fetches = feedList.map { feed ->
            launch(dispatcher) {
                val faviconLookup = trackRefreshing(feed.id) {
                    // The pending-apply permit is taken only once this feed's turn for a fetch slot
                    // comes, and inside trackRefreshing: every feed keeps its place in the fetch queue
                    // ahead of any favicon lookup (which queues for the same slots), and is marked
                    // refreshing from the start. A writer more than REFRESH_PENDING_APPLY_LIMIT behind
                    // holds the fetch slots until it catches up — the point of the bound.
                    val fetch = semaphore.withPermit {
                        pendingApply.acquire()
                        try {
                            feedFetcher.fetch(feed.url, feed.etag, feed.last_modified)
                        } catch (e: Throwable) {
                            pendingApply.release()
                            throw e
                        }
                    }
                    try {
                        // Holds no fetch permit while waiting, so a writer within
                        // REFRESH_PENDING_APPLY_LIMIT of the fetches never caps the fetch concurrency.
                        val committed = CompletableDeferred<Unit>()
                        steps.send(RefreshStep.Fetched(feed, fetch, committed))
                        committed.await()
                        // Only the site URL outlives the commit: the lookup below can wait on a permit
                        // and the network for a while, and must not keep every parsed article resident
                        // meanwhile.
                        if (fetch is Result.Ok && needsFaviconLookup(feed)) FaviconLookup(fetch.value.siteUrl ?: feed.site_url) else null
                    } finally {
                        pendingApply.release()
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
                    val outcome = applyFetch(step.feed, step.fetch)
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
        val notification = AppNotification(
            id = IdGenerator.newId(),
            level = level,
            text = text,
            timestampMillis = clock.nowMillis(),
            action = action,
        )
        if (coalesce) notificationCenter.addCoalescing(notification) else notificationCenter.add(notification)
    }
}

/**
 * Iterates over a reordered feed-id list and writes [newSortOrder]/[newSortOrderUpdatedAt]
 * only for feeds whose sort order actually changed. The caller provides [onFeedId] for any
 * extra action on the feed being moved/inserted (e.g. folder update).
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

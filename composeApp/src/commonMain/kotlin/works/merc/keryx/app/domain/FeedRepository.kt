package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.FetchedFeed

/**
 * Max feeds fetched concurrently in [FeedRepository.refreshAll]'s network phase. Bounded so a large
 * subscription list doesn't open an unbounded number of sockets at once; DB writes stay serial.
 */
private const val REFRESH_FETCH_CONCURRENCY = 6

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
    private val messages: NotificationMessages,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val feeds get() = db.feedsQueries

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
     * @return A successful result containing the subscribed feed, or the fetch error.
     */
    suspend fun subscribeFeed(url: String): Result<Feeds> {
        val outcome = subscribeFeedWrite(url)
        if (outcome.hadArticles) ftsManager.indexMissing()
        return outcome.result
    }

    /** Outcome of [subscribeFeedWrite]: the subscribe result, plus whether the feed had articles. */
    internal data class SubscribeOutcome(val result: Result<Feeds>, val hadArticles: Boolean)

    /**
     * Fetches a feed and persists its metadata and articles without indexing them.
     *
     * @param url The URL of the feed to subscribe to.
     * @return The subscription result, including the stored feed or fetch error and whether articles were fetched.
     */
    internal suspend fun subscribeFeedWrite(url: String): SubscribeOutcome {
        val fetched = when (val r = feedFetcher.fetch(url)) {
            is Result.Ok -> r.value
            is Result.Err -> return SubscribeOutcome(r, hadArticles = false)
        }
        val effectiveUrl = fetched.redirectUrl ?: url
        val existing = feeds.getByUrl(effectiveUrl).executeAsOneOrNull()
        // Deterministic id from the (redirect-resolved) url so two devices that subscribe the same
        // feed independently get the SAME id and the sync merge can converge them (existing feeds
        // keep their id). See IdGenerator.feedId.
        val feedId = existing?.id ?: IdGenerator.feedId(effectiveUrl)
        val now = clock.nowMillis()
        val favicon = faviconResolver.resolve(fetched.siteUrl, effectiveUrl)

        // sort_order: a brand-new feed starts at the end of the "no folder" group (new feeds
        // always start with folder_id = null); a still-live feed being re-fetched keeps its
        // existing position; a previously-unsubscribed feed being re-subscribed is re-numbered to
        // the end of the group it used to belong to (its old relative position may no longer be
        // meaningful after other feeds in that group moved around while it was unsubscribed).
        val sortOrder = when {
            existing == null -> feeds.nextSortOrderInGroup(null).executeAsOne()
            existing.deleted_at == null -> existing.sort_order
            else -> feeds.nextSortOrderInGroup(existing.folder_id).executeAsOne()
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
            custom_title = existing?.custom_title,
            deleted_at = null,
            updated_at = now,
            created_at = existing?.created_at ?: now,
            sort_order = sortOrder,
        )
        // Re-subscribing (feed was soft-deleted) is a subscription-state change: stamp its
        // last-wins timestamp so it propagates over another device's refresh on the next sync.
        if (existing?.deleted_at != null) feeds.stampResubscribed(now, feedId)
        articleRepository.upsertParsed(feedId, fetched.articles)
        syncScheduler.scheduleSync()
        return SubscribeOutcome(
            Result.Ok(feeds.getById(feedId).executeAsOne()),
            hadArticles = fetched.articles.isNotEmpty(),
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
            newOrder.forEachIndexed { index, id ->
                if (id == feedId) {
                    feeds.updateFolderAndSortOrder(folderId, index.toLong(), now, now, now, id)
                } else if (sortOrderOf[id] != index.toLong()) {
                    feeds.updateSortOrder(index.toLong(), now, now, id)
                }
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
        db.transaction {
            var next = feeds.nextSortOrderInGroup(null).executeAsOne()
            for (feed in feeds.getByFolder(folderId).executeAsList()) {
                feeds.updateFolderAndSortOrder(null, next, now, now, now, feed.id)
                next++
            }
        }
    }

    /**
     * Refreshes a feed and indexes any newly fetched articles.
     *
     * @param feed The feed to refresh.
     * @return The number of new articles, or the refresh error.
     */
    suspend fun refreshFeed(feed: Feeds): Result<Int> {
        val outcome = refreshFeedArticles(feed)
        if (outcome.hadArticles) ftsManager.indexMissing()
        return outcome.result
    }

    /** Outcome of [applyFetch]: the article-count result, plus whether any articles were fetched. */
    private data class RefreshOutcome(val result: Result<Int>, val hadArticles: Boolean)

    /**
     * A feed's network fetch result, gathered off the DB write path so [refreshAll] can fetch many
     * feeds concurrently before applying their writes serially. [resolvedFavicon] is the favicon URL
     * resolved during the fetch (only attempted when the feed had none), or null.
     */
    private data class FeedFetchPhase(val fetch: Result<FetchedFeed>, val resolvedFavicon: String?)

    /**
     * Network phase: fetch the feed and, if it has no favicon yet, resolve one. Does NO DB writes,
     * so it is safe to run for many feeds concurrently.
     */
    private suspend fun fetchFeed(feed: Feeds): FeedFetchPhase {
        val fetch = feedFetcher.fetch(feed.url, feed.etag, feed.last_modified)
        val favicon = if (fetch is Result.Ok && feed.favicon_url.isNullOrEmpty()) {
            faviconResolver.resolve(fetch.value.siteUrl ?: feed.site_url, feed.url)
        } else {
            null
        }
        return FeedFetchPhase(fetch, favicon)
    }

    /**
     * Applies fetched feed data, persists its articles, and emits notifications for permanent removal or URL changes.
     *
     * @param feed The feed being refreshed.
     * @param phase The fetched feed data and any resolved favicon.
     * @return The article update result and whether the fetch contained articles.
     */
    private suspend fun applyFetch(feed: Feeds, phase: FeedFetchPhase): RefreshOutcome {
        val fetched = when (val r = phase.fetch) {
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
                    notify(
                        messages.feedGone(feed.displayTitle()),
                        AppNotificationLevel.WARNING,
                        action = AppNotificationAction.ShowFeedDetail(feed.id),
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

            if (feed.favicon_url.isNullOrEmpty()) {
                phase.resolvedFavicon?.let {
                    feeds.updateFaviconUrl(it, now, feed.id)
                }
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
                messages.feedUrlChanged(feed.displayTitle()),
                AppNotificationLevel.WARNING,
                action = AppNotificationAction.ShowFeedDetail(feed.id),
            )
        }
        syncScheduler.scheduleSync()
        return RefreshOutcome(Result.Ok(newCount), fetched.articles.isNotEmpty())
    }

    /** Refreshes a single feed: fetch (network) then apply (DB), one after the other. */
    private suspend fun refreshFeedArticles(feed: Feeds): RefreshOutcome = applyFetch(feed, fetchFeed(feed))

    /**
     * Refreshes all feeds and collects the article-count result for each feed.
     *
     * Network fetching is performed concurrently with bounded concurrency, while database updates
     * are applied in the original feed order.
     *
     * @return A map from feed ID to the result containing the number of newly processed articles.
     */
    suspend fun refreshAll(): Map<String, Result<Int>> {
        val feedList = feeds.watchAll().executeAsList()
        // Phase 1: fetch every feed's network data concurrently (bounded by a semaphore), with NO
        // DB writes — this is where the wall-clock win comes from vs. the old sequential loop.
        val semaphore = Semaphore(REFRESH_FETCH_CONCURRENCY)
        val phases = coroutineScope {
            feedList.map { feed ->
                async(dispatcher) { feed to semaphore.withPermit { fetchFeed(feed) } }
            }.awaitAll()
        }
        // Phase 2: apply DB writes + notifications serially, in the original feed order, so writes
        // stay single-threaded. Each feed's upsertParsed still notifies watchAll, so articles from
        // earlier feeds appear incrementally as the loop advances.
        val result = LinkedHashMap<String, Result<Int>>()
        var anyHadArticles = false
        for ((feed, phase) in phases) {
            val outcome = applyFetch(feed, phase)
            result[feed.id] = outcome.result
            if (outcome.hadArticles) anyHadArticles = true
        }
        if (anyHadArticles) ftsManager.indexMissing()
        return result
    }

    /**
     * Adds a notification with the specified message and severity level.
     *
     * @param message The notification message.
     * @param level The notification severity level.
     * @param action The next action offered when the user acts on the notification.
     */
    private suspend fun notify(message: String, level: AppNotificationLevel, action: AppNotificationAction? = null) {
        notificationCenter.add(
            AppNotification(
                id = IdGenerator.newId(),
                level = level,
                message = message,
                timestampMillis = clock.nowMillis(),
                action = action,
            ),
        )
    }
}

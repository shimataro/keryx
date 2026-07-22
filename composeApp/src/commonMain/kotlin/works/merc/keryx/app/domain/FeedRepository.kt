package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.FetchedFeed

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

    fun getFeedById(id: String): Feeds? = feeds.getById(id).executeAsOneOrNull()

    fun getAllFeeds(): List<Feeds> = feeds.watchAll().executeAsList()

    suspend fun previewFeed(url: String): Result<FetchedFeed> = feedFetcher.fetch(url)

    suspend fun subscribeFeed(url: String): Result<Unit> {
        val fetched = when (val r = feedFetcher.fetch(url)) {
            is Result.Ok -> r.value
            is Result.Err -> return r
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
        if (fetched.articles.isNotEmpty()) ftsManager.indexMissing()
        syncScheduler.scheduleSync()
        return Result.Ok(Unit)
    }

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
     * Moves [feedId] into the [folderId] group (null = "no folder"), positioned directly before
     * [targetFeedId] within that group (or at the end if [targetFeedId] is null). Used for both
     * cross-folder moves and same-folder reordering — they're the same operation. Feeds other than
     * [feedId] are only written when their `sort_order` actually changes, so an unrelated feed's
     * `updated_at` isn't bumped (which would otherwise risk clobbering an unrelated edit made on
     * another device during the next sync merge).
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

    /** Refreshes one feed. Returns the count of new articles, or an error. */
    suspend fun refreshFeed(feed: Feeds): Result<Int> {
        val outcome = refreshFeedArticles(feed)
        if (outcome.hadArticles) ftsManager.indexMissing()
        return outcome.result
    }

    /** Outcome of [refreshFeedArticles]: the article-count result, plus whether any articles were fetched. */
    private data class RefreshOutcome(val result: Result<Int>, val hadArticles: Boolean)

    /**
     * Refreshes a feed's metadata and articles.
     *
     * @param feed The feed to refresh.
     * @return The article upsert result and whether the fetched feed contained articles.
     */
    private suspend fun refreshFeedArticles(feed: Feeds): RefreshOutcome {
        val fetched = when (val r = feedFetcher.fetch(feed.url, feed.etag, feed.last_modified)) {
            is Result.Ok -> r.value
            is Result.Err -> {
                val ex = r.exception
                if (ex !is FeedNotFoundException) {
                    feeds.incrementErrorCount(ex.messageText, clock.nowMillis(), feed.id)
                }
                if (ex is FeedNotFoundException && ex.isGone) {
                    notify(messages.feedGone(feed.displayTitle()), AppNotificationLevel.WARNING)
                }
                return RefreshOutcome(r, hadArticles = false)
            }
        }

        feeds.resetErrorCount(clock.nowMillis(), feed.id)

        if (feed.favicon_url.isNullOrEmpty()) {
            faviconResolver.resolve(fetched.siteUrl ?: feed.site_url, feed.url)?.let {
                feeds.updateFaviconUrl(it, clock.nowMillis(), feed.id)
            }
        }

        feeds.updateCacheHeaders(fetched.etag, fetched.lastModified, clock.nowMillis(), feed.id)

        if (fetched.title != null || fetched.description != null) {
            feeds.upsert(
                id = feed.id,
                url = feed.url,
                site_url = fetched.siteUrl ?: feed.site_url,
                title = fetched.title ?: feed.title,
                description = fetched.description ?: feed.description,
                favicon_url = feed.favicon_url,
                etag = fetched.etag,
                last_modified = fetched.lastModified,
                error_count = 0,
                last_error = null,
                custom_title = feed.custom_title,
                deleted_at = feed.deleted_at,
                updated_at = clock.nowMillis(),
                created_at = feed.created_at,
                sort_order = feed.sort_order,
            )
        }

        if (fetched.redirectUrl != null && fetched.redirectUrl != feed.url) {
            feeds.updateUrl(fetched.redirectUrl, clock.nowMillis(), feed.id)
            notify(messages.feedUrlChanged(feed.displayTitle()), AppNotificationLevel.WARNING)
        }

        val newCount = articleRepository.upsertParsed(feed.id, fetched.articles)
        syncScheduler.scheduleSync()
        return RefreshOutcome(Result.Ok(newCount), fetched.articles.isNotEmpty())
    }

    suspend fun refreshAll(): Map<String, Result<Int>> {
        val result = LinkedHashMap<String, Result<Int>>()
        var anyHadArticles = false
        for (feed in feeds.watchAll().executeAsList()) {
            val outcome = refreshFeedArticles(feed)
            result[feed.id] = outcome.result
            if (outcome.hadArticles) anyHadArticles = true
        }
        if (anyHadArticles) ftsManager.indexMissing()
        return result
    }

    /**
     * Fills in favicons for feeds that lack one. An empty-string favicon is a
     * sentinel meaning "already checked, none found" and is skipped.
     */
    suspend fun backfillMissingFavicons() {
        for (feed in feeds.watchAll().executeAsList()) {
            if (feed.favicon_url == "") continue
            if (feed.favicon_url != null && faviconResolver.isReachable(feed.favicon_url)) continue
            val url = faviconResolver.resolve(feed.site_url, feed.url)
            feeds.updateFaviconUrl(url ?: "", clock.nowMillis(), feed.id)
        }
    }

    /**
     * Adds a notification with the specified message and severity level.
     *
     * @param message The notification message.
     * @param level The notification severity level.
     */
    private suspend fun notify(message: String, level: AppNotificationLevel) {
        notificationCenter.add(
            AppNotification(
                id = IdGenerator.newId(),
                level = level,
                message = message,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }
}

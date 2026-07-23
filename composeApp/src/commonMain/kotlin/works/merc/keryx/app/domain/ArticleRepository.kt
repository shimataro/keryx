package works.merc.keryx.app.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.HtmlText
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.ParsedArticle

/**
 * A full-text search result: the matched article plus FTS5 highlight markup for its title (matched
 * terms wrapped in [FtsSearch.MARK_START]/[FtsSearch.MARK_END]). The UI renders the markup as
 * highlighted spans (bold + marker background).
 */
data class ArticleSearchResult(
    val article: Articles,
    val titleMarked: String,
)

/**
 * Read/star state, article queries, upsert of fetched articles, and full-text
 * search. Read/star changes use a timestamp so the last operation wins on sync.
 */
class ArticleRepository(
    private val db: KeryxDatabase,
    private val ftsSearch: FtsSearch,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val articles get() = db.articlesQueries

    fun watchArticles(filter: ArticleFilter): Flow<List<Articles>> = when (filter) {
        // Search results aren't a DB query; the article-list pane renders them from `search()`
        // (via HomeViewModel.searchResults) instead of this flow.
        ArticleFilter.Search -> return flowOf(emptyList())
        ArticleFilter.All -> articles.watchAll()
        ArticleFilter.Starred -> articles.watchStarred()
        is ArticleFilter.Feed -> articles.watchByFeed(filter.feedId)
        is ArticleFilter.Tag -> articles.watchByTag(filter.tagId)
        is ArticleFilter.Folder -> articles.watchByFolder(filter.folderId)
    }.asFlow().mapToList(dispatcher)

    fun watchUnreadCount(): Flow<Long> = articles.watchUnreadCount().asFlow().mapToOne(dispatcher)

    fun watchStarredUnreadCount(): Flow<Long> = articles.watchStarredUnreadCount().asFlow().mapToOne(dispatcher)

    /**
     * A change ping that re-emits on every mutation of the `articles` table (the emitted count is
     * discarded by the collector). Used to re-run FTS search reactively — `articles_fts` is raw SQL
     * outside SQLDelight, so its results don't auto-refresh on their own.
     */
    fun watchArticleChanges(): Flow<Long> = articles.countArticles().asFlow().mapToOne(dispatcher)

    fun watchUnreadCountsByFeed(): Flow<Map<String, Long>> =
        articles.watchUnreadCountsByFeed().asFlow().mapToList(dispatcher)
            .map { rows -> rows.associate { it.feed_id to it.cnt } }

    fun watchUnreadCountsByTag(): Flow<Map<String, Long>> =
        articles.watchUnreadCountsByTag().asFlow().mapToList(dispatcher)
            .map { rows -> rows.associate { it.tag_id to it.cnt } }

    fun watchUnreadCountsByFolder(): Flow<Map<String, Long>> =
        articles.watchUnreadCountsByFolder().asFlow().mapToList(dispatcher)
            .map { rows -> rows.associate { it.folder_id to it.cnt } }

    fun getArticleById(id: String): Articles? = articles.getById(id).executeAsOneOrNull()

    fun markAsRead(id: String) {
        val now = clock.nowMillis()
        articles.updateReadStatus(is_read = 1L, read_at = now, updated_at = now, id = id)
        syncScheduler.scheduleSync()
    }

    fun markAsUnread(id: String) {
        val now = clock.nowMillis()
        articles.updateReadStatus(is_read = 0L, read_at = now, updated_at = now, id = id)
        syncScheduler.scheduleSync()
    }

    fun setStarred(id: String, starred: Boolean) {
        val now = clock.nowMillis()
        articles.updateStarStatus(is_starred = if (starred) 1L else 0L, starred_at = now, updated_at = now, id = id)
        syncScheduler.scheduleSync()
    }

    /**
     * Marks exactly the given article [ids] as read in a single transaction, with one
     * [SyncScheduler.scheduleSync] call regardless of how many ids are passed. Used for the
     * search scope's "mark all read", where the target set comes from an FTS snapshot rather
     * than a `WHERE` clause like [markAllAsRead].
     */
    fun markArticlesAsRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock.nowMillis()
        db.transaction {
            for (id in ids) {
                articles.updateReadStatus(is_read = 1L, read_at = now, updated_at = now, id = id)
            }
        }
        syncScheduler.scheduleSync()
    }

    fun markAllAsRead(filter: ArticleFilter) {
        val now = clock.nowMillis()
        when (filter) {
            ArticleFilter.All -> articles.markAllReadAll(now, now)
            is ArticleFilter.Feed -> articles.markAllReadByFeed(now, now, filter.feedId)
            is ArticleFilter.Tag -> articles.markAllReadByTag(now, now, filter.tagId)
            is ArticleFilter.Folder -> articles.markAllReadByFolder(now, now, filter.folderId)
            ArticleFilter.Starred, ArticleFilter.Search -> return // no-op
        }
        syncScheduler.scheduleSync()
    }

    fun search(query: String): List<ArticleSearchResult> =
        try {
            ftsSearch.search(query).mapNotNull { hit ->
                articles.getById(hit.id).executeAsOneOrNull()?.let {
                    ArticleSearchResult(article = it, titleMarked = hit.titleMarked)
                }
            }
        } catch (_: Exception) {
            // `articles_fts` is raw SQL outside SQLDelight and is briefly DROPped during a sync's
            // upload window (rebuilt right after). Querying it then throws a raw SQLite exception;
            // absorb it here (DataSource/Repository boundary) so the transient "index unavailable"
            // state surfaces as no hits instead of leaking a raw exception to the ViewModel.
            emptyList()
        }

    /**
     * Inserts or updates fetched articles while preserving existing read and starred state.
     *
     * @param feedId The identifier of the feed containing the articles.
     * @param parsed The fetched articles to store.
     * @return The number of articles that were not previously stored for the feed.
     */
    fun upsertParsed(feedId: String, parsed: List<ParsedArticle>): Int {
        if (parsed.isEmpty()) return 0
        var newCount = 0
        val now = clock.nowMillis()
        db.transaction {
            for (p in parsed) {
                val exists = articles.getByFeedAndGuid(feedId, p.guid).executeAsOneOrNull() != null
                if (!exists) newCount++
                // search_text is the FTS body target: strip HTML so tag names/attributes
                // don't match. content/summary keep their raw HTML for reader rendering.
                val searchText = (p.content ?: p.summary)?.let { HtmlText.toPlainText(it) } ?: ""
                // id is a deterministic UUIDv5 of (feed_id, guid) so the same article gets the SAME
                // id on every device — required for the sync merge (which matches articles by id) to
                // propagate read/star state cross-device. On re-fetch, ON CONFLICT(feed_id, guid)
                // keeps the existing row's id/created_at and preserves is_read/is_starred/read_at/starred_at.
                articles.insert(
                    id = IdGenerator.articleId(feedId, p.guid),
                    feed_id = feedId,
                    guid = p.guid,
                    url = p.url ?: "",
                    title = p.title ?: "",
                    summary = p.summary,
                    content = p.content,
                    author = p.author,
                    published_at = p.publishedAtMillis,
                    thumbnail_url = p.thumbnailUrl,
                    is_read = 0L,
                    read_at = null,
                    is_starred = 0L,
                    starred_at = null,
                    cached_at = now,
                    search_text = searchText,
                    updated_at = now,
                    created_at = now,
                )
            }
        }
        return newCount
    }

    fun deleteExpiredArticles(retentionDays: Int?) {
        if (retentionDays == null) return
        val cutoff = clock.nowMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        articles.deleteExpired(cutoff)
    }
}

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
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.ParsedArticle

/**
 * Max ids bound into a single `id IN (...)` query. SQLite's default bound-parameter limit is 999;
 * 900 leaves headroom. Large id lists (search hits, mark-as-read snapshots) are chunked to this size.
 */
private const val ID_FETCH_CHUNK = 900

/**
 * The columns of an article that the list panes actually render, plus `created_at` as the sort
 * tie-break.
 *
 * A deliberately narrow view of [Articles]: the full row also carries `content`, `summary` and
 * `search_text`, i.e. the article body twice over, none of which the list shows. Loading those for
 * every row made a single list emission proportional to the whole corpus's text — and the list
 * query re-runs on every write to `articles` or `feeds`. The body is loaded for the one selected
 * article instead, via [ArticleRepository.getArticleById].
 *
 * Constructed positionally as `::ArticleListRow` from the `.sq` list queries, so its parameter
 * order and the SELECT column order must stay in step (see the note in `articles.sq`).
 */
data class ArticleListRow(
    val id: String,
    val feed_id: String,
    val title: String,
    val url: String,
    val published_at: Long?,
    val created_at: Long,
    val is_read: Long,
    val is_starred: Long,
)

/** Narrows a full article row to the columns the list renders. */
fun Articles.toListRow(): ArticleListRow = ArticleListRow(
    id = id,
    feed_id = feed_id,
    title = title,
    url = url,
    published_at = published_at,
    created_at = created_at,
    is_read = is_read,
    is_starred = is_starred,
)

/**
 * A full-text search result: the matched article plus FTS5 highlight markup for its title (matched
 * terms wrapped in [FtsSearch.MARK_START]/[FtsSearch.MARK_END]). The UI renders the markup as
 * highlighted spans (bold + marker background).
 */
data class ArticleSearchResult(
    val article: ArticleListRow,
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

    fun watchArticles(filter: ArticleFilter): Flow<List<ArticleListRow>> = when (filter) {
        // Search results aren't a DB query; the article-list pane renders them from `search()`
        // (via HomeViewModel.searchResults) instead of this flow.
        ArticleFilter.Search -> return flowOf(emptyList())
        // ::ArticleListRow rather than the per-query generated types: narrowing a SELECT makes
        // SQLDelight emit a distinct data class per query, which would leave these five branches
        // with five mutually incompatible row types.
        ArticleFilter.All -> articles.watchAll(::ArticleListRow)
        ArticleFilter.Starred -> articles.watchStarred(::ArticleListRow)
        is ArticleFilter.Feed -> articles.watchByFeed(filter.feedId, ::ArticleListRow)
        is ArticleFilter.Tag -> articles.watchByTag(filter.tagId, ::ArticleListRow)
        is ArticleFilter.Folder -> articles.watchByFolder(filter.folderId, ::ArticleListRow)
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
        // One `id IN (...)` UPDATE per chunk instead of a per-id UPDATE, kept inside a single
        // transaction so the batch stays atomic (chunked only to stay under the bound-parameter limit).
        db.transaction {
            for (chunk in ids.chunked(ID_FETCH_CHUNK)) {
                articles.updateReadStatusByIds(read_at = now, updated_at = now, id = chunk)
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
            val hits = ftsSearch.search(query)
            // Load all hit rows with one `id IN (...)` query per chunk (chunked to stay under
            // SQLite's bound-parameter limit) instead of one getById per hit. Iterating `hits`
            // preserves rank order regardless of fetch/map order; mapNotNull drops ids whose row
            // is gone, exactly as the previous per-hit getByFeedAndGuid-style lookup did.
            val byId = hits.asSequence()
                .map { it.id }
                .chunked(ID_FETCH_CHUNK)
                .flatMap { articles.getListRowsByIds(it, ::ArticleListRow).executeAsList().asSequence() }
                .associateBy { it.id }
            hits.mapNotNull { hit ->
                byId[hit.id]?.let { ArticleSearchResult(article = it, titleMarked = hit.titleMarked) }
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
        val now = clock.nowMillis()
        // Precompute everything CPU-heavy OUTSIDE the write transaction so HTML stripping (a full
        // Ksoup DOM parse) and UUIDv5 hashing don't hold the SQLite write lock. The existence check
        // is collapsed from one SELECT per article to a single guid fetch: seed a set with the
        // feed's existing guids, then add each parsed guid — add() returns false when the guid was
        // already present (existing row, or an intra-batch duplicate), so newCount counts each new
        // article exactly once, matching the previous in-transaction re-read semantics.
        val seenGuids = HashSet(articles.getGuidsByFeed(feedId).executeAsList())
        var newCount = 0
        val prepared = parsed.map { p ->
            if (seenGuids.add(p.guid)) newCount++
            // search_text is the FTS body target: strip HTML so tag names/attributes don't match.
            // content/summary keep their raw HTML for reader rendering.
            val searchText = (p.content ?: p.summary)?.let { HtmlText.toPlainText(it) } ?: ""
            // id is a deterministic UUIDv5 of (feed_id, guid) so the same article gets the SAME id on
            // every device — required for the sync merge (which matches articles by id) to propagate
            // read/star state cross-device. On re-fetch, ON CONFLICT(feed_id, guid) keeps the existing
            // row's id/created_at and preserves is_read/is_starred/read_at/starred_at.
            PreparedInsert(id = IdGenerator.articleId(feedId, p.guid), parsed = p, searchText = searchText)
        }
        db.transaction {
            for (pi in prepared) {
                val p = pi.parsed
                articles.insert(
                    id = pi.id,
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
                    search_text = pi.searchText,
                    updated_at = now,
                    created_at = now,
                )
            }
        }
        return newCount
    }

    /** A parsed article with its FTS body text and deterministic id precomputed off the write path. */
    private data class PreparedInsert(val id: String, val parsed: ParsedArticle, val searchText: String)

    /**
     * Soft-deletes articles older than the specified retention period.
     *
     * @param retentionDays The maximum number of days articles may be retained; `null` disables expiration.
     */
    fun deleteExpiredArticles(retentionDays: Int?) {
        if (retentionDays == null) return
        val now = clock.nowMillis()
        val cutoff = now - retentionDays.toLong() * MILLIS_PER_DAY
        // Soft-delete (not physical DELETE) so the deletion propagates via the sync merge
        // instead of being resurrected from the cloud on the next sync.
        articles.softDeleteExpired(
            deleted_at = now,
            deleted_updated_at = now,
            updated_at = now,
            cached_at = cutoff,
        )
    }
}

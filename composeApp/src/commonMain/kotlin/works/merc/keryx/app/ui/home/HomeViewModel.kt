package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FEED_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.MAX_REMEMBERED_SCROLL_POSITIONS
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.searchTerms
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.data.remote.UrlResolver
import works.merc.keryx.app.data.local.ArticleScrollPosition
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.data.remote.FetchedFeed
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.ArticleSearchResult
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.TagRepository

private data class ArticlesQueryParams(
    val filter: ArticleFilter,
    val unreadOnly: Boolean,
    val newestFirst: Boolean,
    val pinnedReadArticles: Map<String, Articles>,
)

/** Debounced FTS results tagged with the query that produced them (see [HomeViewModel.searching]). */
private data class SearchSnapshot(
    val query: String,
    val results: List<ArticleSearchResult>,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val feedRepository: FeedRepository,
    private val articleRepository: ArticleRepository,
    private val tagRepository: TagRepository,
    private val folderRepository: FolderRepository,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val cloudSession: CloudSession,
    private val activityCenter: ActivityCenter,
    private val clock: Clock,
    private val newArticleNotifier: NewArticleNotifier,
    private val notificationMessages: NotificationMessages,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Imperative read/star DB writes run here instead of the UI thread. Single-threaded so writes
    // stay serialized (one writer, as they were on the UI thread) — the JVM SQLite driver opens a
    // fresh connection per statement with no busy_timeout, so concurrent writes could hit
    // SQLITE_BUSY. UI state is updated optimistically before the write is dispatched.
    private val dbWriteDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ViewModel() {

    // Eagerly (not WhileSubscribed) so these start populating as soon as the ViewModel is
    // created — main.kt pre-warms it before the window is shown, so Home's first frame
    // already has real data instead of flashing empty lists.
    private val started = SharingStarted.Eagerly

    val feeds: StateFlow<List<Feeds>> =
        feedRepository.watchAllFeeds().stateIn(viewModelScope, started, emptyList())

    val tags: StateFlow<List<Tags>> =
        tagRepository.watchAllTags().stateIn(viewModelScope, started, emptyList())

    val feedTagMap: StateFlow<Map<String, Set<String>>> =
        tagRepository.watchFeedTagMap().stateIn(viewModelScope, started, emptyMap())

    val unreadByFeed: StateFlow<Map<String, Long>> =
        articleRepository.watchUnreadCountsByFeed().stateIn(viewModelScope, started, emptyMap())

    val unreadByTag: StateFlow<Map<String, Long>> =
        articleRepository.watchUnreadCountsByTag().stateIn(viewModelScope, started, emptyMap())

    val folders: StateFlow<List<Folders>> =
        folderRepository.watchAllFolders().stateIn(viewModelScope, started, emptyList())

    val unreadByFolder: StateFlow<Map<String, Long>> =
        articleRepository.watchUnreadCountsByFolder().stateIn(viewModelScope, started, emptyMap())

    val totalUnread: StateFlow<Long> =
        articleRepository.watchUnreadCount().stateIn(viewModelScope, started, 0L)

    val starredUnreadCount: StateFlow<Long> =
        articleRepository.watchStarredUnreadCount().stateIn(viewModelScope, started, 0L)

    /**
     * Restores the last-selected filter from local settings, falling back to
     * [ArticleFilter.All] if it's missing, undecodable, or points at a feed/tag/folder that
     * was deleted while the app was closed.
     */
    private fun restoreFilter(): ArticleFilter {
        val encoded = settingsRepository.getLocalSettings().lastFilter ?: return ArticleFilter.All
        return when (val decoded = decodeArticleFilter(encoded) ?: return ArticleFilter.All) {
            is ArticleFilter.Feed -> {
                val feed = feedRepository.getFeedById(decoded.feedId)
                if (feed != null && feed.deleted_at == null) decoded else ArticleFilter.All
            }
            is ArticleFilter.Tag -> {
                val tag = tagRepository.getTagById(decoded.tagId)
                if (tag != null && tag.deleted_at == null) decoded else ArticleFilter.All
            }
            is ArticleFilter.Folder -> {
                val folder = folderRepository.getFolderById(decoded.folderId)
                if (folder != null && folder.deleted_at == null) decoded else ArticleFilter.All
            }
            ArticleFilter.All, ArticleFilter.Starred -> decoded
            // Search results depend on a query that isn't persisted, so a restored "search" filter
            // would show an empty view — fall back to All.
            ArticleFilter.Search -> ArticleFilter.All
        }
    }

    // One-time migration: the persisted "unread" filter (removed as a selectable option) is
    // folded into the unreadOnly toggle instead, so users who had it selected keep equivalent
    // behavior after upgrading.
    private val legacyUnreadFilter = settingsRepository.getLocalSettings().lastFilter == "unread"

    private val _filter = MutableStateFlow<ArticleFilter>(restoreFilter())
    val filter: StateFlow<ArticleFilter> = _filter

    private val _unreadOnly = MutableStateFlow(
        legacyUnreadFilter ||
            (
                settingsRepository.getLocalSettings().lastUnreadOnly
                    ?: settingsRepository.getArticleListDefaultUnreadOnly()
                ),
    )
    val unreadOnly: StateFlow<Boolean> = _unreadOnly

    private val _newestFirst = MutableStateFlow(settingsRepository.getLocalSettings().lastNewestFirst ?: true)
    val newestFirst: StateFlow<Boolean> = _newestFirst

    // Articles selected while browsing the current filter that became read as a side effect of
    // selection. Kept visible (in read styling) until the user reloads/syncs or switches filters,
    // so the list doesn't shift under the user while reading down an unread list.
    private val _pinnedReadArticles = MutableStateFlow<Map<String, Articles>>(emptyMap())

    val articles: StateFlow<List<Articles>> =
        combine(_filter, _unreadOnly, _newestFirst, _pinnedReadArticles) { f, unread, newest, pinned ->
            ArticlesQueryParams(f, unread, newest, pinned)
        }
            .flatMapLatest { (f, unread, newest, pinned) ->
                articleRepository.watchArticles(f).map { list ->
                    val existingIds = list.mapTo(HashSet(list.size)) { it.id }
                    val extra = pinned.values.filter { it.id !in existingIds }
                    val merged = if (extra.isEmpty()) list else (list + extra).sortedWith(
                        compareByDescending<Articles> { it.published_at ?: 0L }
                            .thenByDescending { it.created_at }
                            .thenByDescending { it.id }
                    )
                    val filtered = if (unread && f != ArticleFilter.Starred) {
                        merged.filter { it.is_read == 0L || it.id in pinned }
                    } else {
                        merged
                    }
                    if (newest) filtered else filtered.reversed()
                }
            }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, emptyList())

    private val _selectedArticle = MutableStateFlow<Articles?>(null)
    val selectedArticle: StateFlow<Articles?> = _selectedArticle

    // --- Search ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // markAllRead() marks Search results as read after the fact, but search is a one-shot
    // snapshot rather than a DB-reactive flow like `articles` — this forces _rawSearchResults
    // to re-run against the same query text so the freshly-read state actually shows up.
    private val _searchRefreshTrigger = MutableStateFlow(0)

    private val articleChangeSignal: StateFlow<Int> =
        articleRepository.watchArticleChanges()
            .scan(0) { acc, _ -> acc + 1 }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, 0)

    // The debounced FTS results tagged with the query that produced them, so `searching` can tell
    // whether the current live query has been searched yet (see below).
    private val _rawSearchResults: StateFlow<SearchSnapshot> =
        combine(
            _searchQuery.debounce(250),
            _searchRefreshTrigger,
            // Re-run search whenever the articles table changes (read/star toggles, refresh, sync
            // merge) so results stay in sync — search() reads a raw-SQL FTS index that SQLDelight
            // doesn't auto-notify. search() absorbs the transient articles_fts-dropped case itself.
            articleChangeSignal,
        ) { q, _, _ -> q }
            .map { q ->
                SearchSnapshot(q, if (searchTerms(q).isEmpty()) emptyList() else articleRepository.search(q))
            }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, SearchSnapshot("", emptyList()))

    // True while the live query has usable terms but its results haven't arrived yet (still inside
    // the 250ms debounce, or the FTS query is running). Lets the search pane hold instead of
    // flashing "no results" between keystrokes before the real results land.
    val searching: StateFlow<Boolean> =
        combine(_searchQuery, _rawSearchResults) { live, snapshot ->
            searchTerms(live).isNotEmpty() && live != snapshot.query
        }.stateIn(viewModelScope, started, false)

    // _newestFirst is deliberately never consulted here (search order is always relevance-rank).
    // Unlike `articles`, this also never merges pinned-but-absent-from-raw articles back in —
    // a changed query text means a new search, so leaving a pinned article from the previous
    // query stuck in results that no longer match would be surprising.
    val searchResults: StateFlow<List<ArticleSearchResult>> =
        combine(_rawSearchResults, _unreadOnly, _pinnedReadArticles) { snapshot, unread, pinned ->
            val raw = snapshot.results
            // Apply only the optimistic read-state from pinned (never the whole snapshot): other
            // fields — notably is_starred — must come from the fresh re-search, or starring an
            // already-read result would be hidden by the stale pinned copy. Mirrors how
            // searchUnreadCount reads `pinned[id]?.is_read` field-wise.
            val merged = raw.map { result ->
                pinned[result.article.id]?.let { p ->
                    result.copy(article = result.article.copy(is_read = p.is_read))
                } ?: result
            }
            if (unread) merged.filter { it.article.is_read == 0L || it.article.id in pinned } else merged
        }.flowOn(dispatcher).stateIn(viewModelScope, started, emptyList())

    // Unread count shown on the sidebar's "Search" row. Counts the raw (pre-unread-only) hits, so
    // it means "total unread matches" regardless of the unread-only toggle — matching the other
    // sidebar rows. The effective is_read reflects pinned articles so the badge decrements as the
    // user opens results and increments when a result is marked unread.
    val searchUnreadCount: StateFlow<Long> =
        combine(_rawSearchResults, _pinnedReadArticles) { snapshot, pinned ->
            snapshot.results.count { result ->
                val effectiveIsRead = pinned[result.article.id]?.is_read ?: result.article.is_read
                effectiveIsRead == 0L
            }.toLong()
        }.flowOn(dispatcher).stateIn(viewModelScope, started, 0L)

    // One-shot requests to move keyboard focus into the sidebar's search field (Cmd+F, clicking the
    // "Search" sidebar row) — collected by FeedListPane, which owns the field's FocusRequester.
    private val _searchFocusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchFocusRequests: SharedFlow<Unit> = _searchFocusRequests.asSharedFlow()

    fun requestSearchFocus() {
        _searchFocusRequests.tryEmit(Unit)
    }

    // --- Pane widths ---
    private val _feedListPaneWidth = MutableStateFlow(
        settingsRepository.getLocalSettings().feedListPaneWidth
            .coerceIn(FEED_LIST_PANE_MIN_WIDTH.toDouble(), FEED_LIST_PANE_MAX_WIDTH.toDouble()),
    )
    val feedListPaneWidth: StateFlow<Double> = _feedListPaneWidth.asStateFlow()

    private val _articleListPaneWidth = MutableStateFlow(
        settingsRepository.getLocalSettings().articleListPaneWidth
            .coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), ARTICLE_LIST_PANE_MAX_WIDTH.toDouble()),
    )
    val articleListPaneWidth: StateFlow<Double> = _articleListPaneWidth.asStateFlow()

    private val _collapsedFolderIds = MutableStateFlow(
        settingsRepository.getLocalSettings().collapsedFolderIds,
    )
    val collapsedFolderIds: StateFlow<Set<String>> = _collapsedFolderIds.asStateFlow()

    fun toggleFolderCollapsed(folderId: String) {
        _collapsedFolderIds.value = _collapsedFolderIds.value.let {
            if (folderId in it) it - folderId else it + folderId
        }
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(collapsedFolderIds = _collapsedFolderIds.value),
        )
    }

    // --- Article scroll position memory ---

    private val _scrollPositions = MutableStateFlow(
        settingsRepository.getLocalSettings().recentArticleScrollPositions,
    )

    fun getScrollPosition(articleId: String): Int =
        _scrollPositions.value.firstOrNull { it.articleId == articleId }?.scrollOffset ?: 0

    fun saveScrollPosition(articleId: String, offset: Int) {
        val updated = (
            listOf(ArticleScrollPosition(articleId, offset)) +
                _scrollPositions.value.filter { it.articleId != articleId }
            ).take(MAX_REMEMBERED_SCROLL_POSITIONS)
        _scrollPositions.value = updated
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(recentArticleScrollPositions = updated),
        )
    }

    init {
        // Restore the last-selected article (not via selectArticle(), to avoid re-marking it as
        // read and clobbering another device's "mark as unread" sync via read_at last-write-wins).
        val restoredArticle = settingsRepository.getLocalSettings().lastArticleId
            ?.let { articleRepository.getArticleById(it) }
        if (restoredArticle != null) {
            if (restoredArticle.is_read == 1L) {
                // Keep it visible in an unread-only list, mirroring selectArticle()'s pinning.
                _pinnedReadArticles.update { it + (restoredArticle.id to restoredArticle) }
            }
            _selectedArticle.value = restoredArticle
        }

        combine(_feedListPaneWidth, _articleListPaneWidth) { feed, article -> feed to article }
            .debounce(500)
            .onEach { (feed, article) ->
                settingsRepository.saveLocalSettings(
                    settingsRepository.getLocalSettings().copy(feedListPaneWidth = feed, articleListPaneWidth = article),
                )
            }.launchIn(viewModelScope)

        // Any write to `articles` can be a sync merge propagating a soft-delete tombstone for an
        // article currently pinned here; revalidate the pins so a deleted one can't stay visible.
        articleChangeSignal
            .onEach { reconcilePinnedReadArticles() }
            .flowOn(dispatcher)
            .launchIn(viewModelScope)
    }

    fun setFeedListPaneWidth(width: Double) {
        _feedListPaneWidth.value = width.coerceIn(FEED_LIST_PANE_MIN_WIDTH.toDouble(), FEED_LIST_PANE_MAX_WIDTH.toDouble())
    }

    fun setArticleListPaneWidth(width: Double) {
        _articleListPaneWidth.value = width.coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), ARTICLE_LIST_PANE_MAX_WIDTH.toDouble())
    }

    // --- Selection / navigation ---

    fun selectFilter(filter: ArticleFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        _selectedArticle.value = null
        _pinnedReadArticles.value = emptyMap()
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(lastFilter = filter.encode(), lastArticleId = null),
        )
    }

    fun selectArticle(article: Articles) {
        if (article.is_read == 0L) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        }
        // Optimistic: show it read immediately; persist off the UI thread.
        _selectedArticle.value = article.copy(is_read = 1L)
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(lastArticleId = article.id),
        )
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsRead(article.id) }
    }

    fun selectNext() = moveSelection(1)
    fun selectPrevious() = moveSelection(-1)

    /**
     * The article list currently shown in the center pane: search hits when the Search scope is
     * active, otherwise the feed-backed [articles]. Used for keyboard navigation and first-select.
     */
    fun currentArticles(): List<Articles> =
        if (_filter.value is ArticleFilter.Search) searchResults.value.map { it.article } else articles.value

    private fun moveSelection(delta: Int) {
        val list = currentArticles()
        if (list.isEmpty()) return
        val currentId = _selectedArticle.value?.id
        val index = list.indexOfFirst { it.id == currentId }
        val next = when {
            index < 0 -> 0
            else -> (index + delta).coerceIn(0, list.lastIndex)
        }
        selectArticle(list[next])
    }

    fun markSelectedUnread() {
        val current = _selectedArticle.value ?: return
        val id = current.id
        // Optimistic: flip to unread in place (no DB read-back); persist off the UI thread.
        _pinnedReadArticles.update { it - id }
        _selectedArticle.value = current.copy(is_read = 0L)
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsUnread(id) }
    }

    fun toggleRead(article: Articles) {
        val nowRead = article.is_read == 0L
        if (nowRead) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        } else {
            _pinnedReadArticles.update { it - article.id }
        }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.value = article.copy(is_read = if (nowRead) 1L else 0L)
        }
        viewModelScope.launch(dbWriteDispatcher) {
            if (nowRead) articleRepository.markAsRead(article.id) else articleRepository.markAsUnread(article.id)
        }
    }

    fun toggleStar(article: Articles) {
        val starred = article.is_starred == 0L
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.value = article.copy(is_starred = if (starred) 1L else 0L)
        }
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.setStarred(article.id, starred = starred) }
    }

    /**
     * Marks applicable articles as read for the current filter.
     *
     * The starred filter preserves article read states, while other filters optimistically
     * retain currently visible articles with updated read timestamps until the data refreshes.
     */
    fun markAllRead() {
        val filter = _filter.value
        // Starred's markAllAsRead is a no-op (you don't "read" the starred view), so mark-all-read
        // must not force the selected article read there; every other scope does mark it read.
        val marksSelectedRead = filter != ArticleFilter.Starred
        // Optimistic update: pin every currently-visible unread article in its read state so the list
        // doesn't collapse the instant the user presses "mark all read" under unread-only.
        // All pins are cleared on filter switch / refresh, so articles disappear naturally later.
        val selected = _selectedArticle.value
        if (marksSelectedRead) {
            val nowRead = clock.nowMillis()
            val visibleUnread = currentArticles().filter { it.is_read == 0L }
            val pins = _pinnedReadArticles.value.toMutableMap()
            visibleUnread.forEach { article ->
                pins[article.id] = article.copy(is_read = 1L, read_at = nowRead)
            }
            if (selected != null) {
                val updatedSelected = selected.copy(is_read = 1L, read_at = nowRead)
                pins[selected.id] = updatedSelected
                _selectedArticle.value = updatedSelected
            }
            _pinnedReadArticles.value = pins
        } else {
            // Starred: markAllAsRead is a no-op, don't alter read state.
            _pinnedReadArticles.value = if (selected != null) mapOf(selected.id to selected) else emptyMap()
        }
        val idsToMark = if (filter == ArticleFilter.Search) {
            _rawSearchResults.value.results
                .filter { it.article.is_read == 0L }
                .map { it.article.id }
        } else {
            emptyList()
        }

        viewModelScope.launch(dbWriteDispatcher) {
            if (filter == ArticleFilter.Search) {
                val ids = idsToMark
                if (ids.isNotEmpty()) {
                    articleRepository.markArticlesAsRead(ids)
                    // Re-run search only after the write lands so the freshly-read state shows up.
                    _searchRefreshTrigger.update { it + 1 }
                }
            } else {
                articleRepository.markAllAsRead(filter)
            }
        }
    }

    fun setUnreadOnly(value: Boolean) {
        if (value == _unreadOnly.value) return
        if (value) {
            _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        }
        _unreadOnly.value = value
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(lastUnreadOnly = value),
        )
    }

    /**
     * Pinned-read map keeping only the currently selected article (if already read). Used
     * wherever an external data change (refresh, sync, unread-only toggle) would otherwise wipe
     * pins that other in-view articles no longer need, but the selected one should survive.
     */
    private fun pinnedReadArticlesKeepingSelected(): Map<String, Articles> {
        val selected = _selectedArticle.value
        return if (selected != null && selected.is_read == 1L) mapOf(selected.id to selected) else emptyMap()
    }

    /**
     * Drops any pinned entry whose backing row has since been soft-deleted (e.g. a tombstone
     * propagated by a sync merge while the article was pinned), so the `articles` merge step
     * (which re-adds a pinned id missing from the filtered repository result) can never
     * resurrect deleted content into the visible list.
     */
    private fun reconcilePinnedReadArticles() {
        _pinnedReadArticles.update { pinned ->
            if (pinned.isEmpty()) return@update pinned
            pinned.filterValues { articleRepository.getArticleById(it.id)?.deleted_at == null }
        }
    }

    fun toggleSort() {
        _newestFirst.value = !_newestFirst.value
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(lastNewestFirst = _newestFirst.value),
        )
    }

    fun getInitialFocusedPane(): HomePane =
        settingsRepository.getLocalSettings().lastFocusedPane
            ?.let { raw -> HomePane.entries.firstOrNull { it.name == raw } }
            ?: HomePane.ArticleList

    fun setFocusedPane(pane: HomePane) {
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(lastFocusedPane = pane.name),
        )
    }

    // --- Search controls ---

    /**
     * Updates the search query and switches to the Search filter when the query is non-empty.
     *
     * Clears pinned read-state when the query changes.
     *
     * @param query The new search query.
     */
    fun setSearchQuery(query: String) {
        // Start a fresh browsing context when the text actually changes (already in Search scope).
        if (query != _searchQuery.value) {
            _pinnedReadArticles.value = emptyMap()
        }
        _searchQuery.value = query
        if (query.isNotEmpty() && _filter.value != ArticleFilter.Search) {
            selectFilter(ArticleFilter.Search)
        }
    }

    // --- Feed actions ---

    suspend fun previewFeed(url: String): Result<FetchedFeed> = feedRepository.previewFeed(url)

    /**
 * Subscribes to a feed using the specified URL.
 *
 * @param url The URL of the feed to subscribe to.
 * @return The subscription result containing the subscribed feed on success.
 */
suspend fun subscribeFeed(url: String): Result<Feeds> = feedRepository.subscribeFeed(url)

    /**
     * Previews [rawUrl] and maps the outcome for the add-feed dialog. Handles scheme resolution
     * (prepending `https://`, then retrying with `http://` when the user typed no scheme and the
     * https attempt failed for a non-discovery reason) and turns a [FeedDiscoveryException] into a
     * list of candidate feed links. The returned [AddFeedPreview.Single.resolvedUrl] is the actual
     * URL that resolved, so the dialog can both display it and subscribe with it.
     */
    suspend fun resolvePreview(rawUrl: String): AddFeedPreview {
        val trimmed = rawUrl.trim()
        val hadScheme = UrlResolver.hasScheme(trimmed)
        var attemptUrl = UrlResolver.withDefaultScheme(trimmed)
        var result = feedRepository.previewFeed(attemptUrl)
        if (!hadScheme && result is Result.Err && result.exception !is FeedDiscoveryException) {
            val httpUrl = "http://$trimmed"
            val httpResult = feedRepository.previewFeed(httpUrl)
            result = httpResult
            if (httpResult is Result.Ok) attemptUrl = httpUrl
        }
        return when (val r = result) {
            is Result.Ok -> AddFeedPreview.Single(
                resolvedUrl = attemptUrl,
                title = r.value.title ?: attemptUrl,
                articleCount = r.value.articles.size,
            )
            is Result.Err -> when (val ex = r.exception) {
                is FeedDiscoveryException -> AddFeedPreview.Multiple(ex.candidates)
                else -> AddFeedPreview.Failed(ex)
            }
        }
    }

    /** Subscribes to every URL in [urls], returning the success/failure tally and the first error. */
    suspend fun subscribeFeeds(urls: List<String>): SubscribeOutcome {
        val results = urls.map { feedRepository.subscribeFeed(it) }
        val successCount = results.count { it is Result.Ok }
        return SubscribeOutcome(
            successCount = successCount,
            failCount = results.size - successCount,
            firstError = results.filterIsInstance<Result.Err>().firstOrNull()?.exception,
        )
    }

    fun unsubscribeFeed(id: String) {
        feedRepository.unsubscribeFeed(id)
        if (_filter.value == ArticleFilter.Feed(id)) selectFilter(ArticleFilter.All)
    }

    fun renameFeed(id: String, title: String?) = feedRepository.renameFeed(id, title)

    /** True while a feed refresh (manual, per-feed, or background) is in flight. */
    val feedRefreshing: StateFlow<Boolean> = activityCenter.feedRefreshing

    /** True while a cloud sync (manual, debounced, or background) is in flight. */
    val syncing: StateFlow<Boolean> = activityCenter.syncing

    fun refreshFeed(feed: Feeds) {
        viewModelScope.launch {
            activityCenter.trackFeedRefresh { feedRepository.refreshFeed(feed) }
        }
    }

    /**
     * Refreshes all feeds, notifies about newly available articles when enabled, and synchronizes data.
     */
    fun refreshAll() {
        if (activityCenter.feedRefreshing.value) return
        _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        viewModelScope.launch {
            val results = activityCenter.trackFeedRefresh { feedRepository.refreshAll() }
            newArticleNotifier.notifyIfEnabled(
                results, settingsRepository.getLocalSettings().notificationEnabled, notificationMessages,
            )
            syncRepository.sync()
        }
    }

    fun sync() {
        _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        viewModelScope.launch { syncRepository.sync() }
    }

    /** Discards the cloud sync data and re-uploads local fresh (recovery for a corrupt/incompatible
     *  cloud DB). Errors surface via the notification center from [SyncRepository]. */
    fun resetCloudData() {
        viewModelScope.launch { syncRepository.resetCloudData() }
    }

    val cloudConnected: Boolean get() = cloudSession.isConnected()

    // --- Tag actions ---

    fun createTag(name: String, color: String? = null): String? {
        if (name.isBlank()) return null
        return tagRepository.createTag(name.trim(), color)
    }

    fun updateTag(id: String, name: String, color: String?) {
        if (name.isBlank()) return
        tagRepository.updateTag(id, name.trim(), color)
    }

    fun deleteTag(id: String) {
        tagRepository.deleteTag(id)
        if (_filter.value == ArticleFilter.Tag(id)) selectFilter(ArticleFilter.All)
    }

    fun setFeedTag(feedId: String, tagId: String, attached: Boolean) =
        tagRepository.setFeedTag(feedId, tagId, attached)

    // --- Folder actions ---

    fun createFolder(name: String): String? {
        if (name.isBlank()) return null
        return folderRepository.createFolder(name.trim())
    }

    fun updateFolder(id: String, name: String) {
        if (name.isBlank()) return
        folderRepository.updateFolder(id, name.trim())
    }

    fun deleteFolder(id: String) {
        folderRepository.deleteFolder(id)
        if (_filter.value == ArticleFilter.Folder(id)) selectFilter(ArticleFilter.All)
        _collapsedFolderIds.value = _collapsedFolderIds.value - id
        settingsRepository.saveLocalSettings(
            settingsRepository.getLocalSettings().copy(collapsedFolderIds = _collapsedFolderIds.value),
        )
    }

    fun moveFeed(feedId: String, folderId: String?, targetFeedId: String? = null) =
        feedRepository.moveFeed(feedId, folderId, targetFeedId)

    fun reorderFolders(draggedFolderId: String, targetFolderId: String?) =
        folderRepository.reorderFolders(draggedFolderId, targetFolderId)
}

/** Outcome of [HomeViewModel.resolvePreview] for the add-feed dialog. */
sealed interface AddFeedPreview {
    /** A single feed resolved directly. [title] falls back to [resolvedUrl] when the feed is untitled. */
    data class Single(val resolvedUrl: String, val title: String, val articleCount: Int) : AddFeedPreview

    /** The URL pointed at an HTML page advertising multiple feeds; the user picks which to subscribe. */
    data class Multiple(val candidates: List<DiscoveredFeedLink>) : AddFeedPreview

    /** Preview failed for a non-discovery reason. */
    data class Failed(val exception: KeryxException) : AddFeedPreview
}

/** Tally returned by [HomeViewModel.subscribeFeeds]. */
data class SubscribeOutcome(val successCount: Int, val failCount: Int, val firstError: KeryxException?)

/**
 * Whether the add-feed dialog's subscribe action should be enabled for the current [preview] and
 * [selectedCandidates]. A single result is always subscribable; a multi-candidate result requires
 * at least one selection (so subscribing with nothing selected can't fall back to re-previewing).
 */
fun addFeedCanSubscribe(preview: AddFeedPreview?, selectedCandidates: Set<String>): Boolean =
    when (preview) {
        is AddFeedPreview.Single -> true
        is AddFeedPreview.Multiple -> selectedCandidates.isNotEmpty()
        else -> false
    }

package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FEED_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.MAX_REMEMBERED_SCROLL_POSITIONS
import works.merc.keryx.app.core.searchTerms
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.data.local.ArticleScrollPosition
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
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

/**
 * A repository article list tagged with the filter that produced it, so the display transform can
 * apply filter-dependent rules (e.g. the starred exemption) against the right filter even though
 * it no longer runs inside the `flatMapLatest` that switched to that filter.
 */
private data class FilteredArticles(
    val filter: ArticleFilter,
    val articles: List<Articles>,
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
    // fresh connection per statement, so concurrent writes would contend for the write lock and
    // only avoid SQLITE_BUSY by burning the busy_timeout DatabaseDriverFactory sets. UI state is
    // updated optimistically before the write is dispatched.
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

    // Only the filter keys the DB query: switching filters must switch queries, but the unread-only,
    // sort and pinned inputs are pure display transforms over whatever that query returned. Keeping
    // them in the flatMapLatest key made every article selection (which pins the article it marks
    // read) cancel and re-execute the whole unbounded list query.
    private val filteredArticles: Flow<FilteredArticles> =
        _filter.flatMapLatest { f -> articleRepository.watchArticles(f).map { FilteredArticles(f, it) } }

    val articles: StateFlow<List<Articles>> =
        combine(filteredArticles, _unreadOnly, _newestFirst, _pinnedReadArticles) { (f, list), unread, newest, pinned ->
            // Nothing pinned is the common case, and then the id set has no reader — skip
            // building it rather than hashing every article's id on every emission.
            val extra = if (pinned.isEmpty()) {
                emptyList()
            } else {
                val existingIds = list.mapTo(HashSet(list.size)) { it.id }
                pinned.values.filter { it.id !in existingIds }
            }
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
            // asReversed() is a view, not a second full copy: `filtered` is freshly derived
            // per emission and never mutated afterwards, so it reads identically.
            if (newest) filtered else filtered.asReversed()
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

    /**
     * Toggles whether a folder is collapsed and persists the updated state.
     *
     * @param folderId The identifier of the folder to toggle.
     */
    fun toggleFolderCollapsed(folderId: String) {
        _collapsedFolderIds.value = _collapsedFolderIds.value.let {
            if (folderId in it) it - folderId else it + folderId
        }
        settingsRepository.mutateLocalSettings { it.copy(collapsedFolderIds = _collapsedFolderIds.value) }
    }

    private val _expandedTagIds = MutableStateFlow(
        settingsRepository.getLocalSettings().expandedTagIds,
    )
    val expandedTagIds: StateFlow<Set<String>> = _expandedTagIds.asStateFlow()

    /**
     * Toggles whether a tag's attached-feed list is expanded and persists the updated state.
     *
     * @param tagId The identifier of the tag to toggle.
     */
    fun toggleTagExpanded(tagId: String) {
        _expandedTagIds.value = _expandedTagIds.value.let {
            if (tagId in it) it - tagId else it + tagId
        }
        settingsRepository.mutateLocalSettings { it.copy(expandedTagIds = _expandedTagIds.value) }
    }

    // --- Article scroll position memory ---

    private val _scrollPositions = MutableStateFlow(
        settingsRepository.getLocalSettings().recentArticleScrollPositions,
    )

    fun getScrollPosition(articleId: String): Int =
        _scrollPositions.value.firstOrNull { it.articleId == articleId }?.scrollOffset ?: 0

    /**
     * Saves the scroll offset for an article and retains only the most recent remembered positions.
     *
     * @param articleId The identifier of the article.
     * @param offset The article's scroll offset.
     */
    fun saveScrollPosition(articleId: String, offset: Int) {
        val updated = (
            listOf(ArticleScrollPosition(articleId, offset)) +
                _scrollPositions.value.filter { it.articleId != articleId }
            ).take(MAX_REMEMBERED_SCROLL_POSITIONS)
        _scrollPositions.value = updated
        settingsRepository.mutateLocalSettings { it.copy(recentArticleScrollPositions = updated) }
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
                settingsRepository.mutateLocalSettings { it.copy(feedListPaneWidth = feed, articleListPaneWidth = article) }
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

    /**
     * Selects the active article filter and clears the current article selection and pinned read articles.
     *
     * @param filter The article filter to select.
     */

    fun selectFilter(filter: ArticleFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        _selectedArticle.value = null
        _pinnedReadArticles.value = emptyMap()
        settingsRepository.mutateLocalSettings { it.copy(lastFilter = filter.encode(), lastArticleId = null) }
    }

    /**
     * Selects an article and marks it as read.
     *
     * @param article The article to select.
     */
    fun selectArticle(article: Articles) {
        if (article.is_read == 0L) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        }
        // Optimistic: show it read immediately; persist off the UI thread.
        _selectedArticle.value = article.copy(is_read = 1L)
        settingsRepository.mutateLocalSettings { it.copy(lastArticleId = article.id) }
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

    /**
     * Enables or disables filtering the article list to unread articles.
     *
     * @param value Whether to show only unread articles.
     */
    fun setUnreadOnly(value: Boolean) {
        if (value == _unreadOnly.value) return
        if (value) {
            _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        }
        _unreadOnly.value = value
        settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnly = value) }
    }

    /**
     * Preserves the selected read article for continued display when it remains available.
     *
     * @return A map containing the selected article if it is read and not deleted; an empty map otherwise.
     */
    private fun pinnedReadArticlesKeepingSelected(): Map<String, Articles> {
        val selected = _selectedArticle.value
        if (selected == null || selected.is_read != 1L) return emptyMap()
        // The selected row may have been tombstoned by a sync merge that landed while it was
        // selected. Re-pinning it would put deleted content back into the visible list, because the
        // `articles` merge step re-adds any pinned id missing from the repository result — the same
        // reason [reconcilePinnedReadArticles] exists, and the same check it applies.
        if (articleRepository.getArticleById(selected.id)?.deleted_at != null) return emptyMap()
        return mapOf(selected.id to selected)
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

    /**
     * Toggles the article sort order and persists the updated preference.
     */
    fun toggleSort() {
        _newestFirst.value = !_newestFirst.value
        settingsRepository.mutateLocalSettings { it.copy(lastNewestFirst = _newestFirst.value) }
    }

    /**
             * Retrieves the last focused home pane from local settings.
             *
             * @return The previously focused pane, or [HomePane.ArticleList] when no valid saved pane exists.
             */
            fun getInitialFocusedPane(): HomePane =
        settingsRepository.getLocalSettings().lastFocusedPane
            ?.let { raw -> HomePane.entries.firstOrNull { it.name == raw } }
            ?: HomePane.ArticleList

    /**
     * Sets the pane that should receive focus.
     *
     * @param pane The pane to focus.
     */
    fun setFocusedPane(pane: HomePane) {
        settingsRepository.mutateLocalSettings { it.copy(lastFocusedPane = pane.name) }
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

    private val addFeedPreviewResolver = AddFeedPreviewResolver(feedRepository)

    /** @see AddFeedPreviewResolver.resolvePreview */
    suspend fun resolvePreview(rawUrl: String): AddFeedPreview = addFeedPreviewResolver.resolvePreview(rawUrl)

    /** @see AddFeedPreviewResolver.subscribeFeeds */
    suspend fun subscribeFeeds(urls: List<String>): SubscribeOutcome = addFeedPreviewResolver.subscribeFeeds(urls)

    /**
     * Unsubscribes from a feed and switches to the all-articles filter if it is selected.
     *
     * @param id The identifier of the feed to unsubscribe from.
     */
    fun unsubscribeFeed(id: String) {
        feedRepository.unsubscribeFeed(id)
        if (_filter.value == ArticleFilter.Feed(id)) selectFilter(ArticleFilter.All)
    }

    fun renameFeed(id: String, title: String?) = feedRepository.renameFeed(id, title)

    /** True while a feed refresh (manual, per-feed, or background) is in flight. */
    val feedRefreshing: StateFlow<Boolean> = activityCenter.feedRefreshing

    /** True while a cloud sync (manual, debounced, or background) is in flight. */
    val syncing: StateFlow<Boolean> = activityCenter.syncing

    /**
     * Refreshes the specified feed.
     *
     * @param feed The feed to refresh.
     */
    fun refreshFeed(feed: Feeds) {
        viewModelScope.launch {
            withContext(dispatcher) { activityCenter.trackFeedRefresh { feedRepository.refreshFeed(feed) } }
        }
    }

    /**
     * Refreshes all feeds, notifies about newly available articles when enabled, and synchronizes data.
     */
    fun refreshAll() {
        if (activityCenter.feedRefreshing.value) return
        _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        // The heavy work goes off the UI thread: a full feed refresh (fetch, parse, per-feed DB
        // writes, FTS indexing) followed by a sync (whole-DB write, ATTACH merge, VACUUM INTO,
        // whole-DB read). `viewModelScope` is Dispatchers.Main.immediate, so a launch naming no
        // dispatcher ran all of that inline on the AWT EDT. Only the IO is moved — the coroutine
        // itself stays on Main so the state writes below remain confined there, as they were, rather
        // than racing the UI's own writes to the same flows. Mirrors what SettingsViewModel already
        // does for its equivalent calls. Feed writes stay serialized either way: refreshAll()
        // applies them in one sequential loop internally.
        viewModelScope.launch {
            val results = withContext(dispatcher) {
                activityCenter.trackFeedRefresh { feedRepository.refreshAll() }
            }
            newArticleNotifier.notifyIfEnabled(
                results, settingsRepository.getLocalSettings().notificationEnabled, notificationMessages,
            )
            withContext(dispatcher) { syncRepository.sync() }
            // Re-trim using the selection as it stands now: it may have changed since the snapshot
            // above was taken, and the stale pre-refresh selection must not outlive it.
            _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        }
    }

    /**
     * Synchronizes local data with the cloud.
     */
    fun sync() {
        _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        // IO off the UI thread — see refreshAll(): a sync writes the downloaded cloud DB to disk,
        // runs the ATTACH merge, VACUUM INTOs a snapshot and reads it all back.
        viewModelScope.launch {
            withContext(dispatcher) { syncRepository.sync() }
            _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        }
    }

    /** Discards the cloud sync data and re-uploads local fresh (recovery for a corrupt/incompatible
     *  cloud DB). Errors surface via the notification center from [SyncRepository]. */
    fun resetCloudData() {
        viewModelScope.launch { withContext(dispatcher) { syncRepository.resetCloudData() } }
    }

    /**
     * Whether a cloud provider is selected, configured and holds tokens.
     *
     * A StateFlow rather than a getter because `CloudSession.isConnected()` reaches the OS secret
     * store, and this is read straight from composition: as a getter it ran an uncached Secret
     * Service / Credential Manager round trip (Linux / Windows) — or, on the first macOS call, a
     * `security` subprocess spawn — on the UI thread on *every* recomposition of the feed list and
     * the menu bar. Re-evaluated only when the selected provider changes, which is the only thing
     * that can change it deliberately: every connect / disconnect path writes `cloudStorageType`
     * (SettingsViewModel, SetupViewModel). Gating on that one field matters — `localSettings` itself
     * is rewritten by unrelated state such as pane widths, which change on every drag frame.
     *
     * The trade-off versus the getter this replaced: if the OS secret store is unreadable at seed
     * time but becomes readable later, this stays `false` until the provider selection changes again,
     * where the getter would have healed on the next recomposition.
     */
    val cloudConnected: StateFlow<Boolean> =
        settingsRepository.localSettings
            .map { it.cloudStorageType }
            .distinctUntilChanged()
            .map { cloudSession.isConnected() }
            .flowOn(dispatcher)
            // Seeded synchronously so the first frame already has the real value instead of
            // flashing the sync action off and back on.
            .stateIn(viewModelScope, started, cloudSession.isConnected())

    // --- Tag actions ---

    fun createTag(name: String, color: String? = null): String? {
        if (name.isBlank()) return null
        return tagRepository.createTag(name.trim(), color)
    }

    /**
     * Updates a tag with the specified name and color.
     *
     * Blank names are ignored.
     *
     * @param id The identifier of the tag to update.
     * @param name The tag's new name.
     * @param color The tag's new color, or `null` to remove the color.
     */
    fun updateTag(id: String, name: String, color: String?) {
        if (name.isBlank()) return
        tagRepository.updateTag(id, name.trim(), color)
    }

    /**
     * Deletes a tag and removes it from the expanded-tag state.
     *
     * @param id The identifier of the tag to delete.
     */
    fun deleteTag(id: String) {
        tagRepository.deleteTag(id)
        if (_filter.value == ArticleFilter.Tag(id)) selectFilter(ArticleFilter.All)
        _expandedTagIds.value = _expandedTagIds.value - id
        settingsRepository.mutateLocalSettings { it.copy(expandedTagIds = _expandedTagIds.value) }
    }

    /**
         * Updates whether a feed is associated with a tag.
         *
         * @param feedId The ID of the feed.
         * @param tagId The ID of the tag.
         * @param attached Whether the tag should be associated with the feed.
         */
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

    /**
     * Deletes a folder and removes it from the collapsed-folder state.
     *
     * @param id The identifier of the folder to delete.
     */
    fun deleteFolder(id: String) {
        folderRepository.deleteFolder(id)
        if (_filter.value == ArticleFilter.Folder(id)) selectFilter(ArticleFilter.All)
        _collapsedFolderIds.value = _collapsedFolderIds.value - id
        settingsRepository.mutateLocalSettings { it.copy(collapsedFolderIds = _collapsedFolderIds.value) }
    }

    /**
         * Moves a feed into a folder and optionally positions it relative to another feed.
         *
         * @param feedId The identifier of the feed to move.
         * @param folderId The destination folder identifier, or `null` to remove the feed from a folder.
         * @param targetFeedId The identifier of the feed to position the moved feed relative to, or `null` to use the default position.
         */
        fun moveFeed(feedId: String, folderId: String?, targetFeedId: String? = null) =
        feedRepository.moveFeed(feedId, folderId, targetFeedId)

    /**
         * Reorders a folder relative to the specified target folder.
         *
         * @param draggedFolderId The identifier of the folder being moved.
         * @param targetFolderId The identifier of the folder to move before, or `null` to move to the end.
         */
        fun reorderFolders(draggedFolderId: String, targetFolderId: String?) =
        folderRepository.reorderFolders(draggedFolderId, targetFolderId)
}


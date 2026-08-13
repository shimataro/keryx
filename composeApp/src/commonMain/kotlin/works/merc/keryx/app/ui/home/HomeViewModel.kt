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
import works.merc.keryx.app.core.PANE_WIDTH_PERSIST_DEBOUNCE_MS
import works.merc.keryx.app.core.SEARCH_DEBOUNCE_MS
import works.merc.keryx.app.core.searchTerms
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.AddFeedPreviewResolver
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.ArticleSearchResult
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SubscribeOutcome
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.TagRepository

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

    // Which *rendered row* of the feed list the selection is on — a feed renders once under its
    // folder and again under every expanded tag it carries, and only this says which of those the
    // user is actually on (primary highlight, scroll-into-view target, keyboard-nav cursor).
    // Deliberately not persisted: only the filter is restored across launches, so the instance
    // starts at that filter's canonical (folder-group) row, matching pre-instance behavior.
    private val _selectedRowInstance = MutableStateFlow(FeedListRowSelection.canonicalFor(_filter.value))
    val selectedRowInstance: StateFlow<FeedListRowSelection> = _selectedRowInstance

    private val _unreadOnly = MutableStateFlow(
        legacyUnreadFilter ||
            (
                settingsRepository.getLocalSettings().lastUnreadOnly
                    ?: settingsRepository.getArticleListDefaultUnreadOnly()
                ),
    )

    // Starred is scoped independently from every other filter: a user who keeps "unread only" on
    // while browsing feeds would otherwise see an almost-empty Starred view the moment they switch,
    // since most starred articles are already read by the time they're starred. Deliberately not
    // seeded from getArticleListDefaultUnreadOnly() — that default is about the shared toggle, not
    // this dedicated one, so an unset Starred toggle always starts OFF.
    private val _unreadOnlyStarred = MutableStateFlow(
        settingsRepository.getLocalSettings().lastUnreadOnlyStarred ?: false,
    )

    // Search is scoped independently for the same reason as Starred: the primary motivation for
    // searching is finding an article already read, so inheriting the shared toggle would leave
    // "unread only" users with empty/incomplete results with no obvious cause. Deliberately not
    // seeded from getArticleListDefaultUnreadOnly() for the same reason as Starred.
    private val _unreadOnlySearch = MutableStateFlow(
        settingsRepository.getLocalSettings().lastUnreadOnlySearch ?: false,
    )

    // Selects which backing toggle is currently in effect. Starred's own toggle still filters
    // "starred ∩ unread" correctly when turned on (a state sync merge can legitimately produce,
    // since read/star are merged independently — see MergeSql), only which toggle is consulted
    // differs by filter.
    val unreadOnly: StateFlow<Boolean> =
        combine(_filter, _unreadOnly, _unreadOnlyStarred, _unreadOnlySearch) { f, general, starred, search ->
            when (f) {
                ArticleFilter.Starred -> starred
                ArticleFilter.Search -> search
                else -> general
            }
        }.stateIn(
            viewModelScope,
            started,
            when (_filter.value) {
                ArticleFilter.Starred -> _unreadOnlyStarred.value
                ArticleFilter.Search -> _unreadOnlySearch.value
                else -> _unreadOnly.value
            },
        )

    private val _newestFirst = MutableStateFlow(settingsRepository.getLocalSettings().lastNewestFirst ?: true)
    val newestFirst: StateFlow<Boolean> = _newestFirst

    // Articles selected while browsing the current filter that became read as a side effect of
    // selection. Kept visible (in read styling) until the user reloads/syncs or switches filters,
    // so the list doesn't shift under the user while reading down an unread list.
    private val _pinnedReadArticles = MutableStateFlow<Map<String, ArticleListRow>>(emptyMap())

    // Only the filter keys the DB query: switching filters must switch queries, but the unread-only,
    // sort and pinned inputs are pure display transforms over whatever that query returned. Keeping
    // them in the flatMapLatest key made every article selection (which pins the article it marks
    // read) cancel and re-execute the whole unbounded list query.
    private val filteredArticles: Flow<List<ArticleListRow>> =
        _filter.flatMapLatest { f -> articleRepository.watchArticles(f) }

    val articles: StateFlow<List<ArticleListRow>> =
        combine(filteredArticles, unreadOnly, _newestFirst, _pinnedReadArticles) { list, unread, newest, pinned ->
            // Nothing pinned is the common case, and then the id set has no reader — skip
            // building it rather than hashing every article's id on every emission.
            val extra = if (pinned.isEmpty()) {
                emptyList()
            } else {
                val existingIds = list.mapTo(HashSet(list.size)) { it.id }
                pinned.values.filter { it.id !in existingIds }
            }
            val merged = if (extra.isEmpty()) list else (list + extra).sortedWith(
                compareByDescending<ArticleListRow> { it.published_at ?: 0L }
                    .thenByDescending { it.created_at }
                    .thenByDescending { it.id }
            )
            val filtered = if (unread) {
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

    /**
     * The list cursor for keyboard navigation, and the identity of the newest selection request.
     *
     * [selectArticle] loads the article body asynchronously, so [_selectedArticle] lags the user's
     * intent; this is updated synchronously instead, which keeps a held arrow key advancing at
     * key-repeat speed and lets a superseded hydration recognise that it lost. Only ever touched on
     * the ViewModel's (main) context, so it needs no synchronization.
     */
    private var selectionCursorId: String? = null

    /**
     * Identity of the current browsing context, bumped whenever the whole pinned-read set is dropped
     * for a fresh one ([selectFilter], and a query change in [setSearchQuery]).
     *
     * [selectionCursorId] cannot stand in for this: it carries no scope identity, so a selection
     * made under the new scope puts a non-null id back, and a hydration still in flight from the old
     * one passes its null check and re-adds a pin that was just cleared. Only ever touched on the
     * ViewModel's (main) context, so it needs no synchronization.
     */
    private var browsingEpoch = 0

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
            _searchQuery.debounce(SEARCH_DEBOUNCE_MS),
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
        combine(_rawSearchResults, unreadOnly, _pinnedReadArticles) { snapshot, unread, pinned ->
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
        // A collapsed tag no longer renders its nested feed rows, so a selection on one of them
        // falls back to that feed's canonical row.
        val instance = _selectedRowInstance.value
        if (instance is FeedListRowSelection.FeedInTag && instance.tagId == tagId && tagId !in _expandedTagIds.value) {
            _selectedRowInstance.value = FeedListRowSelection.FeedInFolderGroup(instance.feedId)
        }
        settingsRepository.mutateLocalSettings { it.copy(expandedTagIds = _expandedTagIds.value) }
    }

    // --- Article scroll position memory ---

    private val scrollPositionStore = ArticleScrollPositionStore(settingsRepository)

    /**
     * Gets the saved scroll position for an article.
     *
     * @param articleId The identifier of the article.
     * @return The saved scroll offset, or the default position when none is stored.
     */
    fun getScrollPosition(articleId: String): Int = scrollPositionStore.getScrollPosition(articleId)

    /**
     * Saves the scroll offset for an article and retains only the most recent remembered positions.
     *
     * @param articleId The identifier of the article.
     * @param offset The article's scroll offset.
     */
    fun saveScrollPosition(articleId: String, offset: Int) = scrollPositionStore.saveScrollPosition(articleId, offset)

    init {
        // Restore the last-selected article (not via selectArticle(), to avoid re-marking it as
        // read and clobbering another device's "mark as unread" sync via read_at last-write-wins).
        // A tombstone can land while the app is closed, so the restored row is filtered the same way
        // selectArticle filters a concurrently-deleted one — otherwise the next launch would select
        // and pin deleted content.
        val restoredArticle = settingsRepository.getLocalSettings().lastArticleId
            ?.let { articleRepository.getArticleById(it) }
            ?.takeIf { it.deleted_at == null }
        if (restoredArticle != null) {
            if (restoredArticle.is_read == 1L) {
                // Keep it visible in an unread-only list, mirroring selectArticle()'s pinning.
                _pinnedReadArticles.update { it + (restoredArticle.id to restoredArticle.toListRow()) }
            }
            _selectedArticle.value = restoredArticle
            // Seed the navigation cursor too, so the first arrow key steps from the restored
            // article instead of jumping back to the top of the list.
            selectionCursorId = restoredArticle.id
        }

        combine(_feedListPaneWidth, _articleListPaneWidth) { feed, article -> feed to article }
            .debounce(PANE_WIDTH_PERSIST_DEBOUNCE_MS)
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
     * @param instance Which rendered feed-list row was selected — defaults to [filter]'s canonical
     *   (folder-group) row for callers with no specific row in mind (search, notification actions).
     *   Selecting a *different rendered instance of the already-selected filter* (e.g. the
     *   tag-nested copy of a feed already selected under its folder) only moves the highlight: the
     *   article/cursor/epoch side effects below stay gated on the filter itself changing.
     */
    fun selectFilter(
        filter: ArticleFilter,
        instance: FeedListRowSelection = FeedListRowSelection.canonicalFor(filter),
    ) {
        if (filter == _filter.value) {
            _selectedRowInstance.value = instance
            return
        }
        _filter.value = filter
        _selectedRowInstance.value = instance
        _selectedArticle.value = null
        _pinnedReadArticles.value = emptyMap()
        // Cancels any selection whose body is still loading: without this, a hydration in flight
        // across the switch would restore the selection and re-add the pin just cleared here. The
        // cursor alone cannot carry that veto — a selection made under the new filter puts a
        // non-null id straight back — so the epoch records the switch itself.
        selectionCursorId = null
        browsingEpoch++
        settingsRepository.mutateLocalSettings { it.copy(lastFilter = filter.encode(), lastArticleId = null) }
    }

    /**
     * Selects an existing article, loads its full content, and marks it as read.
     *
     * @param article The article row to select.
     */
    fun selectArticle(article: ArticleListRow) {
        // Synchronous, so keyboard navigation always steps from where the user actually is rather
        // than from whatever the last completed hydration left in _selectedArticle.
        selectionCursorId = article.id
        // Stamped here too: the hydration below must pin into the browsing context the user actually
        // selected in, not into whichever one is current when its DB lookup finally returns.
        val epoch = browsingEpoch
        viewModelScope.launch {
            // The list row carries no body, so the detail pane's copy is loaded here — one PK lookup
            // on selection, in place of loading every article's body on every list emission. Off the
            // UI thread: this pulls the whole row including `content`, and the JVM driver opens a
            // fresh connection per statement, so under a sync merge's or refresh's write lock it can
            // wait out the whole busy_timeout. Held arrow keys would do that ~30 times a second.
            val full = withContext(dispatcher) { articleRepository.getArticleById(article.id) }
            // A null (or tombstoned) row means a sync merge deleted it between the emission the user
            // clicked and the click itself: leave the selection, the pin and the persisted id
            // entirely alone rather than resurrecting deleted content into the list (which is what
            // pinning would do — the `articles` merge above re-adds any pinned id missing from the
            // query result) or restoring it on the next launch.
            if (full == null || full.deleted_at != null) {
                // Resume navigating from what is actually on screen, not from the dead article.
                if (selectionCursorId == article.id) selectionCursorId = _selectedArticle.value?.id
                return@launch
            }
            // Marking read is unconditional (external-spec §7: read the instant it is selected), so
            // an article passed over by a fast key repeat is still marked read exactly as before.
            viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsRead(article.id) }
            // Nothing is selected any more — a filter switch, or an earlier hydration finding its
            // own article tombstoned — so there is nothing left to apply below.
            val cursor = selectionCursorId ?: return@launch
            // Pinned even when superseded, so an unread-only list cannot collapse under a held key —
            // but never into a browsing context that dropped every pin (a filter switch, a new search
            // query) while this lookup was still running, which the `articles` merge would read as an
            // instruction to re-add this article to a list it does not belong to.
            if (epoch == browsingEpoch && article.is_read == 0L) {
                _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
            }
            // Only the newest selection reaches the reader: an older lookup that finished later
            // would otherwise put the wrong article back on screen.
            if (cursor != article.id) return@launch
            // Optimistic: show it read immediately; the persist above already went out.
            _selectedArticle.value = full.copy(is_read = 1L)
            settingsRepository.mutateLocalSettings { it.copy(lastArticleId = article.id) }
        }
    }

    fun selectNext() = moveSelection(1)
    fun selectPrevious() = moveSelection(-1)

    /**
     * Provides the article rows currently displayed in the center pane.
     *
     * @return Search-result rows for the search filter, or the filtered article rows otherwise.
     */
    fun currentArticles(): List<ArticleListRow> =
        if (_filter.value is ArticleFilter.Search) searchResults.value.map { it.article } else articles.value

    private fun moveSelection(delta: Int) {
        val list = currentArticles()
        if (list.isEmpty()) return
        // The cursor, not _selectedArticle: the latter only catches up once the body has loaded, so
        // stepping from it would make a held arrow key re-read the same stale index every repeat.
        val currentId = selectionCursorId
        val index = list.indexOfFirst { it.id == currentId }
        val next = when {
            index < 0 -> 0
            else -> (index + delta).coerceIn(0, list.lastIndex)
        }
        selectArticle(list[next])
    }

    /**
     * Marks the selected article as unread.
     */
    fun markSelectedUnread() {
        val current = _selectedArticle.value ?: return
        val id = current.id
        // Optimistic: flip to unread in place (no DB read-back); persist off the UI thread.
        _pinnedReadArticles.update { it - id }
        _selectedArticle.value = current.copy(is_read = 0L)
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsUnread(id) }
    }

    /**
     * Toggles the read state of an article and persists the change.
     *
     * @param article The article whose read state should be toggled.
     */
    fun toggleRead(article: ArticleListRow) {
        val nowRead = article.is_read == 0L
        if (nowRead) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        } else {
            _pinnedReadArticles.update { it - article.id }
        }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_read = if (nowRead) 1L else 0L) }
        }
        viewModelScope.launch(dbWriteDispatcher) {
            if (nowRead) articleRepository.markAsRead(article.id) else articleRepository.markAsUnread(article.id)
        }
    }

    /**
     * Toggles the read state of the selected article.
     */
    fun toggleReadSelected() = _selectedArticle.value?.let { toggleRead(it.toListRow()) }

    /**
     * Toggles the starred state of an article.
     *
     * @param article The article whose starred state should be toggled.
     */
    fun toggleStar(article: ArticleListRow) {
        val starred = article.is_starred == 0L
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_starred = if (starred) 1L else 0L) }
        }
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.setStarred(article.id, starred = starred) }
    }

    /**
     * Toggles the starred state of the selected article.
     */
    fun toggleStarSelected() = _selectedArticle.value?.let { toggleStar(it.toListRow()) }

    /**
     * Marks unread articles in the current filter as read.
     *
     * The starred filter preserves article read states, while other filters retain visible articles
     * optimistically until the updated data is refreshed.
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
                pins[article.id] = article.copy(is_read = 1L)
            }
            if (selected != null) {
                val updatedSelected = selected.copy(is_read = 1L, read_at = nowRead)
                pins[selected.id] = updatedSelected.toListRow()
                _selectedArticle.value = updatedSelected
            }
            _pinnedReadArticles.value = pins
        } else {
            // Starred: markAllAsRead is a no-op, don't alter read state.
            _pinnedReadArticles.value =
                if (selected != null) mapOf(selected.id to selected.toListRow()) else emptyMap()
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
        if (value == unreadOnly.value) return
        if (value) {
            _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
        }
        when (_filter.value) {
            ArticleFilter.Starred -> {
                _unreadOnlyStarred.value = value
                settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnlyStarred = value) }
            }
            ArticleFilter.Search -> {
                _unreadOnlySearch.value = value
                settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnlySearch = value) }
            }
            else -> {
                _unreadOnly.value = value
                settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnly = value) }
            }
        }
    }

    /**
     * Preserves the selected read article for continued display when it remains available.
     *
     * @return A map containing the selected article if it is read and not deleted; an empty map otherwise.
     */
    private fun pinnedReadArticlesKeepingSelected(): Map<String, ArticleListRow> {
        val selected = _selectedArticle.value
        if (selected == null || selected.is_read != 1L) return emptyMap()
        // A newer selection is still loading its body, so [_selectedArticle] is the article being
        // replaced: keeping it would preserve a pin the user has already navigated away from. The
        // hydration in flight pins its own article when it lands, so nothing is lost by dropping
        // everything here.
        if (selectionCursorId != selected.id) return emptyMap()
        // The selected row may have been tombstoned by a sync merge that landed while it was
        // selected. Re-pinning it would put deleted content back into the visible list, because the
        // `articles` merge step re-adds any pinned id missing from the repository result — the same
        // reason [reconcilePinnedReadArticles] exists, and the same check it applies.
        if (selected.id !in articleRepository.aliveArticleIds(listOf(selected.id))) return emptyMap()
        return mapOf(selected.id to selected.toListRow())
    }

    /**
     * Removes pinned articles that have been deleted.
     */
    private fun reconcilePinnedReadArticles() {
        val snapshot = _pinnedReadArticles.value
        if (snapshot.isEmpty()) return
        // Resolved with ONE query, outside the update lambda. Per-pin getById was both an N+1 (each
        // one a full row on its own connection) and inside a CAS retry loop that can re-run it;
        // "mark all read" sizes this set to the whole visible list, and it runs on every articles
        // write.
        val alive = articleRepository.aliveArticleIds(snapshot.keys)
        _pinnedReadArticles.update { pinned ->
            // Keys added since the snapshot are kept: they were just pinned, so they are alive by
            // construction, and `alive` has no verdict on them.
            pinned.filterKeys { it in alive || it !in snapshot }
        }
    }

    /**
     * Toggles between newest-first and oldest-first article ordering.
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
            // Same veto as selectFilter: a body load still in flight from the previous query must
            // not re-add a pin into the fresh context. Unlike a filter switch, the cursor and the
            // selection deliberately survive a query change — only the pin does not.
            browsingEpoch++
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
     * Deletes a tag and resets the active filter if it references the deleted tag.
     *
     * @param id The identifier of the tag to delete.
     */
    fun deleteTag(id: String) {
        tagRepository.deleteTag(id)
        if (_filter.value == ArticleFilter.Tag(id)) selectFilter(ArticleFilter.All)
        // A deleted tag no longer renders its nested feed rows, so a selection on one of them
        // falls back to that feed's canonical row (a no-op if the branch above already reset it).
        val instance = _selectedRowInstance.value
        if (instance is FeedListRowSelection.FeedInTag && instance.tagId == id) {
            _selectedRowInstance.value = FeedListRowSelection.FeedInFolderGroup(instance.feedId)
        }
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


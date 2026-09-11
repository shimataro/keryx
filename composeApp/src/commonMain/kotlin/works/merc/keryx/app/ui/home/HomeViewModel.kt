package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/**
 * Debounced FTS results tagged with the query/filter that produced them (see
 * [HomeViewModel.searching]).
 */
private data class SearchSnapshot(
    val query: String,
    val filter: ArticleFilter,
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

    /**
     * A one-shot check for whether any feed exists at all, read directly from
     * [FeedRepository.watchAllFeeds] rather than the already-collected [feeds] above: [feeds]'
     * `Eagerly`-shared `StateFlow` starts at `emptyList()` before its first real emission lands, so
     * reading `feeds.value` here couldn't tell "genuinely zero feeds" apart from "not loaded yet".
     * `watchAllFeeds()` is backed by a SQLDelight query, so its first emission is already the real
     * DB content — see `HomePaneLayout.kt`'s `shouldAutoOpenFeedDrawer`, the only caller.
     */
    suspend fun hasAnyFeed(): Boolean = feedRepository.watchAllFeeds().first().isNotEmpty()

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
        val decoded = decodeArticleFilter(encoded) ?: return ArticleFilter.All
        return validateFilterTarget(decoded)
    }

    /**
     * Falls back to [ArticleFilter.All] when [filter] references a feed/tag/folder that no longer
     * exists (soft-deleted locally, or since [restoreFilter] read it). Filters with no target of
     * their own pass through unchanged.
     */
    private fun validateFilterTarget(filter: ArticleFilter): ArticleFilter = when (filter) {
        is ArticleFilter.Feed -> {
            val feed = feedRepository.getFeedById(filter.feedId)
            if (feed != null && feed.deleted_at == null) filter else ArticleFilter.All
        }
        is ArticleFilter.Tag -> {
            val tag = tagRepository.getTagById(filter.tagId)
            if (tag != null && tag.deleted_at == null) filter else ArticleFilter.All
        }
        is ArticleFilter.Folder -> {
            val folder = folderRepository.getFolderById(filter.folderId)
            if (folder != null && folder.deleted_at == null) filter else ArticleFilter.All
        }
        ArticleFilter.All, ArticleFilter.Starred -> filter
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

    // --- Search ---
    //
    // Search is orthogonal to `_filter`, not a variant of it: the query narrows whatever filter is
    // already selected (see `searchActive`/`searchResults` below), rather than displacing it. This
    // means there is nothing to restore when search ends — the filter, its row selection, and the
    // browsing context (pins/selection/cursor) were never touched in the first place.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Whether the expanded search bar/field is open. At PaneLayout.Triple this is always true —
    // FeedListPane's own field is permanent there — kept true by HomeScreen's own
    // LaunchedEffect(layout); at a narrow layout it starts false and is toggled by the search
    // icon/back arrow (see ArticleListPane's own KDoc). searchActive (below) additionally requires
    // a non-empty query, so opening the bar alone never disturbs the article list underneath it.
    private val _searchBarVisible = MutableStateFlow(false)
    val searchBarVisible: StateFlow<Boolean> = _searchBarVisible.asStateFlow()

    fun setSearchBarVisible(visible: Boolean) {
        _searchBarVisible.value = visible
        // Drops a focus request no field ever consumed (e.g. Cmd+F, then navigating elsewhere
        // before the bar composed), so it can't steal focus at whatever field appears next.
        if (!visible) _pendingSearchFocus.value = false
    }

    // Whether the article list is currently showing search results rather than `_filter`'s own
    // list. Deliberately keyed on the query being non-empty (not on `searchTerms` having any valid
    // ones) — a single short character still needs to show the "too short" hint instead of the
    // filter's own list; see ArticleListPane's own 4-state body.
    val searchActive: StateFlow<Boolean> =
        combine(_searchBarVisible, _searchQuery) { visible, query -> visible && query.isNotEmpty() }
            .stateIn(viewModelScope, started, false)

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

    // When search is active the same key as the underlying filter is used (general for
    // All/Feed/Tag/Folder, starred for Starred).
    val unreadOnly: StateFlow<Boolean> =
        combine(_filter, searchActive, _unreadOnly, _unreadOnlyStarred) { f, active, general, starred ->
            when {
                active -> if (f == ArticleFilter.Starred) starred else general
                f == ArticleFilter.Starred -> starred
                else -> general
            }
        }.stateIn(
            viewModelScope,
            started,
            when {
                searchActive.value -> if (_filter.value == ArticleFilter.Starred) _unreadOnlyStarred.value else _unreadOnly.value
                _filter.value == ArticleFilter.Starred -> _unreadOnlyStarred.value
                else -> _unreadOnly.value
            },
        )

    private val _newestFirst = MutableStateFlow(settingsRepository.getLocalSettings().lastNewestFirst ?: true)
    val newestFirst: StateFlow<Boolean> = _newestFirst

    // Articles selected while browsing the current filter that became read as a side effect of
    // selection. Kept visible (in read styling) until the user reloads/syncs or switches filters,
    // so the list doesn't shift under the user while reading down an unread list.
    private val _pinnedReadArticles = MutableStateFlow<Map<String, ArticleListRow>>(emptyMap())

    // Articles unstarred while browsing the Starred filter. Kept visible (with the star cleared)
    // until the user switches filters, so the list doesn't shift under the user the instant they
    // unstar something. A separate map from _pinnedReadArticles (rather than reusing it) because
    // the two have different reset rules: setUnreadOnly's pinnedReadArticlesKeepingSelected() only
    // re-seeds the read pin, and conflating the two would make an unstarred article's grace period
    // dependent on read-state bookkeeping it has nothing to do with.
    private val _pinnedUnstarredArticles = MutableStateFlow<Map<String, ArticleListRow>>(emptyMap())

    // Only the filter keys the DB query: switching filters must switch queries, but the unread-only,
    // sort and pinned inputs are pure display transforms over whatever that query returned. Keeping
    // them in the flatMapLatest key made every article selection (which pins the article it marks
    // read) cancel and re-execute the whole unbounded list query.
    private val filteredArticles: Flow<List<ArticleListRow>> =
        _filter.flatMapLatest { articleRepository.watchArticles(it) }

    val articles: StateFlow<List<ArticleListRow>> =
        combine(
            filteredArticles, unreadOnly, _newestFirst, _pinnedReadArticles, _pinnedUnstarredArticles,
        ) { list, unread, newest, pinnedRead, pinnedUnstarred ->
            // Nothing pinned is the common case, and then neither a resolved copy nor the id set has
            // a reader — skip building either rather than touching every row on every emission.
            val resolvedList: List<ArticleListRow>
            val extra: List<ArticleListRow>
            if (pinnedRead.isEmpty() && pinnedUnstarred.isEmpty()) {
                resolvedList = list
                extra = emptyList()
            } else {
                // A pinned article can already be present in `list` — its own optimistic write just
                // hasn't reached the raw query result yet (dbWriteDispatcher runs it asynchronously).
                // Without this, the row would show the pre-toggle field for that window, exactly the
                // gap the pin exists to paper over (e.g. is_starred still 1 for an article just
                // unstarred while browsing Starred). Per-field resolution (rather than picking one
                // map's snapshot outright) covers an article pinned in both at once, e.g. read and
                // then unstarred while browsing Starred + unread-only.
                resolvedList = list.map { row ->
                    row.copy(
                        is_read = pinnedRead[row.id]?.is_read ?: row.is_read,
                        is_starred = pinnedUnstarred[row.id]?.is_starred ?: row.is_starred,
                    )
                }
                val existingIds = list.mapTo(HashSet(list.size)) { it.id }
                extra = (pinnedRead.keys + pinnedUnstarred.keys).filter { it !in existingIds }.map { id ->
                    val base = pinnedRead[id] ?: pinnedUnstarred.getValue(id)
                    base.copy(
                        is_read = pinnedRead[id]?.is_read ?: base.is_read,
                        is_starred = pinnedUnstarred[id]?.is_starred ?: base.is_starred,
                    )
                }
            }
            val merged = if (extra.isEmpty()) resolvedList else (resolvedList + extra).sortedWith(
                compareByDescending<ArticleListRow> { it.published_at ?: 0L }
                    .thenByDescending { it.created_at }
                    .thenByDescending { it.id }
            )
            // Independent of the resolution above: every _pinnedReadArticles entry is is_read == 1
            // by construction (see its declaration), so this OR is what keeps a just-marked-read
            // article visible for its grace period under unread-only — id membership, not staleness,
            // is what this needs.
            val filtered = if (unread) {
                merged.filter { it.is_read == 0L || it.id in pinnedRead }
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

    // markAllRead() marks search results as read after the fact, but search is a one-shot
    // snapshot rather than a DB-reactive flow like `articles` — this forces _rawSearchResults
    // to re-run against the same query text so the freshly-read state actually shows up.
    private val _searchRefreshTrigger = MutableStateFlow(0)

    private val articleChangeSignal: StateFlow<Int> =
        articleRepository.watchArticleChanges()
            .scan(0) { acc, _ -> acc + 1 }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, 0)

    // The debounced FTS results tagged with the query/filter that produced them, so `searching` can
    // tell whether the current live query (against the current scope) has been searched yet (see
    // below). Only the query is debounced — a filter change is a discrete user action, not a run of
    // keystrokes, so it re-searches immediately.
    private val _rawSearchResults: StateFlow<SearchSnapshot> =
        combine(
            _searchQuery.debounce(SEARCH_DEBOUNCE_MS),
            _filter,
            _searchRefreshTrigger,
            // Re-run search whenever the articles table changes (read/star toggles, refresh, sync
            // merge) so results stay in sync — search() reads a raw-SQL FTS index that SQLDelight
            // doesn't auto-notify. search() absorbs the transient articles_fts-dropped case itself.
            articleChangeSignal,
        ) { q, f, _, _ -> q to f }
            .map { (q, f) ->
                SearchSnapshot(q, f, if (searchTerms(q).isEmpty()) emptyList() else articleRepository.search(q, f))
            }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, SearchSnapshot("", _filter.value, emptyList()))

    // True while the live query has usable terms but its results haven't arrived yet — still
    // inside the 250ms debounce, the FTS query is running, or the filter it should be scoped to has
    // moved on since the snapshot was taken. Lets the search pane hold instead of flashing "no
    // results" between keystrokes (or right after switching filters) before the real results land.
    val searching: StateFlow<Boolean> =
        combine(_searchQuery, _filter, _rawSearchResults) { live, filter, snapshot ->
            searchTerms(live).isNotEmpty() && (live != snapshot.query || filter != snapshot.filter)
        }.stateIn(viewModelScope, started, false)

    // _newestFirst is deliberately never consulted here (search order is always relevance-rank).
    // Unlike `articles`, this also never merges pinned-but-absent-from-raw articles back in —
    // a changed query text means a new search, so leaving a pinned article from the previous
    // query stuck in results that no longer match would be surprising.
    val searchResults: StateFlow<List<ArticleSearchResult>> =
        combine(_rawSearchResults, unreadOnly, _pinnedReadArticles, _pinnedUnstarredArticles) { snapshot, unread, pinnedRead, pinnedUnstarred ->
            val raw = snapshot.results
            // Apply only the optimistic read-state from pinned (never the whole snapshot): other
            // fields — notably is_starred — must come from the fresh re-search, or starring an
            // already-read result would be hidden by the stale pinned copy. Field-wise merge of
            // `is_read` from `pinnedRead` and `is_starred` from `pinnedUnstarred`, matching the
            // merge in the `articles` flow above.
            val merged = raw.map { result ->
                val readPin = pinnedRead[result.article.id]
                val unstarPin = pinnedUnstarred[result.article.id]
                when {
                    readPin != null && unstarPin != null -> result.copy(
                        article = result.article.copy(is_read = readPin.is_read, is_starred = unstarPin.is_starred),
                    )
                    readPin != null -> result.copy(article = result.article.copy(is_read = readPin.is_read))
                    unstarPin != null -> result.copy(article = result.article.copy(is_starred = unstarPin.is_starred))
                    else -> result
                }
            }
            if (unread) merged.filter { it.article.is_read == 0L || it.article.id in pinnedRead } else merged
        }.flowOn(dispatcher).stateIn(viewModelScope, started, emptyList())

    // Requests to move keyboard focus into whichever composable currently owns the search field —
    // FeedListPane's own KeryxTextField at PaneLayout.Triple, or ArticleListPane's
    // KeryxExpandedSearchBar at a narrow layout (Cmd+F, or tapping the search icon, both call
    // requestSearchFocus()). Deliberately a *latched* StateFlow rather than a one-shot SharedFlow:
    // at a narrow layout the request is raised in the same click that opens the bar, so the
    // composable that will own the field has not composed yet — a SharedFlow emission (as this used
    // to be) is dropped silently when no collector exists yet, which is exactly what happened here.
    // The latch stays set until the field that actually gains focus consumes it
    // (consumeSearchFocusRequest()), and setSearchBarVisible(false) clears it when the bar closes
    // without any field ever consuming it, so a stale request can never steal focus from an
    // unrelated field later.
    private val _pendingSearchFocus = MutableStateFlow(false)
    val pendingSearchFocus: StateFlow<Boolean> = _pendingSearchFocus.asStateFlow()

    fun requestSearchFocus() {
        _pendingSearchFocus.value = true
    }

    fun consumeSearchFocusRequest() {
        _pendingSearchFocus.value = false
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
            .onEach { reconcilePinnedArticlesAndSelection() }
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
     * Deliberately does not touch [searchQuery] or [searchBarVisible] — search is orthogonal to the
     * filter (see this class's own "Search" section), so switching feeds/folders/tags while
     * actively searching keeps the query and simply re-scopes the search to the new filter.
     *
     * @param filter The article filter to select.
     * @param instance Which rendered feed-list row was selected — defaults to [filter]'s canonical
     *   (folder-group) row for callers with no specific row in mind (notification actions).
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
        _pinnedUnstarredArticles.value = emptyMap()
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
            // Dispatched before the optimistic pin/selection below, not after — see
            // reconcilePinnedArticlesAndSelection's own KDoc for why this order is load-bearing.
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
     * Whether [selectNext] would actually land on a different article.
     *
     * Read by the article reader's swipe gesture (`ui/home/ArticleSwipeNav.kt`) to decide whether a
     * drag in that direction moves the content or only rubber-bands, so the two must agree with
     * [moveSelection]'s own clamping — which is why both resolve the current row through the same
     * [selectionIndex] helper.
     *
     * @return `true` when there is a following article to move to.
     */
    fun canSelectNext(): Boolean {
        val list = currentArticles()
        val index = selectionIndex(list)
        // No cursor means nothing is selected: the keyboard's own `moveSelection` would jump to the
        // first row here, but a swipe on the reader has no article on screen to swipe away from, so
        // report neither direction as available rather than inventing a selection from a gesture.
        return index >= 0 && index < list.lastIndex
    }

    /**
     * Whether [selectPrevious] would actually land on a different article.
     *
     * @return `true` when there is a preceding article to move to.
     */
    fun canSelectPrevious(): Boolean = selectionIndex(currentArticles()) > 0

    /**
     * Provides the article rows currently displayed in the center pane.
     *
     * @return Search-result rows while [searchActive], or the filtered article rows otherwise.
     */
    fun currentArticles(): List<ArticleListRow> =
        if (searchActive.value) searchResults.value.map { it.article } else articles.value

    private fun moveSelection(delta: Int) {
        val list = currentArticles()
        if (list.isEmpty()) return
        val index = selectionIndex(list)
        val next = when {
            index < 0 -> 0
            else -> (index + delta).coerceIn(0, list.lastIndex)
        }
        selectArticle(list[next])
    }

    /**
     * Locates the current selection within [list].
     *
     * Resolved from the cursor, not from `_selectedArticle`: the latter only catches up once the
     * body has loaded, so stepping from it would make a held arrow key re-read the same stale index
     * every repeat.
     *
     * @param list The rows currently displayed, as returned by [currentArticles].
     * @return The selected row's index, or `-1` when nothing in [list] is selected.
     */
    private fun selectionIndex(list: List<ArticleListRow>): Int {
        val currentId = selectionCursorId ?: return -1
        return list.indexOfFirst { it.id == currentId }
    }

    /**
     * Marks the selected article as unread.
     */
    fun markSelectedUnread() {
        val current = _selectedArticle.value ?: return
        val id = current.id
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic unread state using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsUnread(id) }
        // Optimistic: flip to unread in place (no DB read-back).
        _pinnedReadArticles.update { it - id }
        _selectedArticle.value = current.copy(is_read = 0L)
    }

    /**
     * Toggles the read state of an article and persists the change.
     *
     * @param article The article whose read state should be toggled.
     */
    fun toggleRead(article: ArticleListRow) {
        val nowRead = article.is_read == 0L
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) {
            if (nowRead) articleRepository.markAsRead(article.id) else articleRepository.markAsUnread(article.id)
        }
        if (nowRead) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        } else {
            _pinnedReadArticles.update { it - article.id }
        }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_read = if (nowRead) 1L else 0L) }
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
        // Only the Starred filter's query excludes an unstarred article, so only pin there —
        // switching into Starred later already starts from a fresh, un-pinned query (selectFilter).
        // Re-starring UPDATES the pin to the confirmed value rather than clearing it outright: the DB
        // write below is dispatched asynchronously, so clearing the pin immediately would leave a gap
        // — for at least one `articles` emission the article is in neither the raw query result (write
        // not committed yet) nor the pin map (just cleared) — and a LazyColumn keyed by article id
        // reacts to that gap by shifting its scroll anchor to the next row, so the re-starred article
        // jumps out of view once it reappears. Leaving the pin in place is harmless: the `articles`
        // combine resolves this exact confirmed value onto the row once the raw query catches up, so
        // nothing changes, and it's cleared for good on the next filter switch, exactly like the
        // unstarred-pin lifecycle.
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.setStarred(article.id, starred = starred) }
        if (_filter.value == ArticleFilter.Starred) {
            _pinnedUnstarredArticles.update { it + (article.id to article.copy(is_starred = if (starred) 1L else 0L)) }
        } else if (starred) {
            _pinnedUnstarredArticles.update { it - article.id }
        }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_starred = if (starred) 1L else 0L) }
        }
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
        val active = searchActive.value
        // Starred's markAllAsRead is a no-op (you don't "read" the starred view), so mark-all-read
        // must not force the selected article read there — except while actively searching within
        // it: search results are an explicit, scoped-down selection the user chose to act on, not
        // the plain Starred list, so idsToMark below marks them regardless of the filter beneath.
        // Without the `active ||` here, marksSelectedRead would say "false" while idsToMark still
        // marked every matched id — leaving the selected article inconsistently unmarked among them.
        val marksSelectedRead = active || filter != ArticleFilter.Starred
        val idsToMark = if (active) {
            val resultIds = _rawSearchResults.value.results
                .filter { it.article.is_read == 0L }
                .map { it.article.id }
                .toMutableList()
            // The raw search snapshot can lag an optimistic unread change (e.g. markSelectedUnread()
            // immediately followed by markAllRead()). Include the selected article if it is currently
            // unread so the operation is not treated as a no-op and the article is actually marked read.
            _selectedArticle.value
                ?.takeIf { it.is_read == 0L }
                ?.id
                ?.let { if (it !in resultIds) resultIds += it }
            resultIds
        } else {
            emptyList()
        }
        // Everything the optimistic update below needs is read here, *before* the write is
        // dispatched — not after. dbWriteDispatcher is Dispatchers.Unconfined in tests (and could
        // race a real write landing before this reads it in production), so reading `articles`
        // after the dispatch below could already observe the post-write, all-read snapshot and see
        // no unread articles left to pin at all.
        val selected = _selectedArticle.value
        val visibleUnread = if (marksSelectedRead) currentArticles().filter { it.is_read == 0L } else emptyList()
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        if (active && idsToMark.isEmpty()) {
            // Nothing in the current search results needs marking read; skip both the DB write and
            // the dependent search refresh.
            return
        }
        viewModelScope.launch(dbWriteDispatcher) {
            if (active) {
                articleRepository.markArticlesAsRead(idsToMark)
                // Re-run search only after the write lands so the freshly-read state shows up.
                _searchRefreshTrigger.update { it + 1 }
            } else {
                articleRepository.markAllAsRead(filter)
            }
        }
        // Optimistic update: pin every currently-visible unread article in its read state so the list
        // doesn't collapse the instant the user presses "mark all read" under unread-only.
        // All pins are cleared on filter switch / refresh, so articles disappear naturally later.
        if (marksSelectedRead) {
            val nowRead = clock.nowMillis()
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
        when {
            _filter.value == ArticleFilter.Starred -> {
                _unreadOnlyStarred.value = value
                settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnlyStarred = value) }
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
        // reason [reconcilePinnedArticlesAndSelection] exists, and the same check it applies.
        if (selected.id !in articleRepository.aliveArticleFlags(listOf(selected.id))) return emptyMap()
        return mapOf(selected.id to selected.toListRow())
    }

    /**
     * Revalidates every optimistic pin (and the current selection's cached flags) against the DB's
     * current state, so a pin can never hide an external change forever.
     *
     * [_pinnedReadArticles]/[_pinnedUnstarredArticles] intentionally show a value that outruns the
     * DB while their own write is still in flight (see each of [selectArticle]/[toggleRead]/
     * [toggleStar]/[markAllRead]/[markSelectedUnread]'s own comments) — but nothing here ever
     * re-checks that the DB actually caught up, so a pin that started as "optimistic" could
     * otherwise stay wrong forever once something *external* changes the same article: another
     * device's sync propagating a "mark unread" or a restar, or a soft-delete tombstone. This runs
     * on every write to `articles` (via the `articleChangeSignal` collector below) precisely so
     * such a change surfaces promptly rather than staying hidden until the next filter switch.
     *
     * The concurrency argument this relies on — that a pin observed here can never be checked
     * *before* the write that justified it has landed — is spelled out where the read happens,
     * below.
     */
    private suspend fun reconcilePinnedArticlesAndSelection() {
        val readSnapshot = _pinnedReadArticles.value
        val unstarredSnapshot = _pinnedUnstarredArticles.value
        val selectedSnapshot = _selectedArticle.value
        if (readSnapshot.isEmpty() && unstarredSnapshot.isEmpty() && selectedSnapshot == null) return
        // Resolved with ONE query covering all three, outside the update lambdas below. Per-pin
        // getById was both an N+1 (each one a full row on its own connection) and inside a CAS retry
        // loop that can re-run it; "mark all read" sizes the read map to the whole visible list, and
        // this runs on every articles write.
        //
        // Read via dbWriteDispatcher, not the `dispatcher` this function itself runs on (see the
        // articleChangeSignal collector in init, below) — deliberately, and this is the ordering
        // argument every optimistic-update call site above points back to. _pinnedReadArticles/
        // _pinnedUnstarredArticles/_selectedArticle are all MutableStateFlow, so if this function
        // observes a given pin/selection value, the Main-thread write that produced it has already
        // happened (StateFlow's memory-visibility guarantee) — and every call site that sets one of
        // these now dispatches its DB write to dbWriteDispatcher strictly *before* that state update,
        // so that write was necessarily enqueued on dbWriteDispatcher before this value became
        // observable. Reading here through the same dbWriteDispatcher — which runs everything
        // dispatched to it in FIFO order (limitedParallelism(1)) — therefore guarantees this read
        // executes *after* that write lands, never seeing a stale pre-write value that would
        // otherwise make this function incorrectly drop a still-valid optimistic pin/selection. This
        // is not "same thread, so it's safe" — the two sides run on different dispatchers.
        val ids = readSnapshot.keys + unstarredSnapshot.keys + listOfNotNull(selectedSnapshot?.id)
        val flags = withContext(dbWriteDispatcher) { articleRepository.aliveArticleFlags(ids) }
        if (readSnapshot.isNotEmpty()) {
            _pinnedReadArticles.update { pinned ->
                // Keys added since the snapshot are kept: they were just pinned, so `flags` has no
                // verdict on them. A pin whose article is alive but no longer actually read (an
                // external "mark unread", or a soft-delete tombstone) is dropped too — this map must
                // hold only is_read == 1 entries, since the unread-only filter trusts membership
                // alone (see its own declaration).
                pinned.filterKeys { it !in readSnapshot || flags[it]?.isRead == 1L }
            }
        }
        if (unstarredSnapshot.isNotEmpty()) {
            _pinnedUnstarredArticles.update { pinned ->
                // Same idea for the starred pin: dropped once the article's current is_starred no
                // longer matches what was optimistically pinned (deleted, or externally re-starred),
                // not just once it is deleted.
                pinned.filterKeys { it !in unstarredSnapshot || flags[it]?.isStarred == pinned.getValue(it).is_starred }
            }
        }
        // Keeps the detail pane's toolbar (read/star toggle state) from staying stale forever behind
        // an external change, the same way the two pins above do for the list. Body/title are left
        // alone — this only ever revalidates the two flags, never re-fetches content.
        selectedSnapshot?.let { selected ->
            val current = flags[selected.id] ?: return@let
            if (current.isRead != selected.is_read || current.isStarred != selected.is_starred) {
                _selectedArticle.update {
                    if (it?.id == selected.id) it.copy(is_read = current.isRead, is_starred = current.isStarred) else it
                }
            }
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
     * Updates the search query.
     *
     * Deliberately does not touch [_pinnedReadArticles] or [browsingEpoch] — unlike a filter
     * switch, a query change doesn't start a fresh browsing context, it narrows the *same* one.
     * `_pinnedReadArticles` is shared between the underlying filter's own list and search results
     * over it (see [searchResults]' own combine), so clearing it here would drop a just-read
     * article from the filter's list the instant the user types a character into an
     * always-visible field ([PaneLayout.Triple]'s sidebar). [searchResults] never merges a pinned
     * id back in that the fresh query no longer matches (see its own KDoc), so nothing pinned
     * under a previous query can leak into results that don't match it.
     *
     * @param query The new search query.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val addFeedPreviewResolver = AddFeedPreviewResolver(feedRepository, tagRepository)

    /** @see AddFeedPreviewResolver.resolvePreview */
    suspend fun resolvePreview(rawUrl: String): AddFeedPreview = addFeedPreviewResolver.resolvePreview(rawUrl)

    /** @see AddFeedPreviewResolver.subscribeFeeds */
    suspend fun subscribeFeeds(urls: List<String>): SubscribeOutcome =
        addFeedPreviewResolver.subscribeFeeds(
            urls,
            folderIdForNewFeed(),
            afterFeedIdForNewFeed(),
            beforeFeedIdForNewFeed(),
            tagIdForNewFeed(),
        )

    /**
     * The folder a newly subscribed feed should be filed into, derived from the feed list's
     * current selection: the selected folder itself, or the folder of the selected feed. Any
     * other selection (all/starred/tag) yields `null` (no folder).
     */
    private fun folderIdForNewFeed(): String? = when (val f = _filter.value) {
        is ArticleFilter.Folder -> f.folderId
        is ArticleFilter.Feed -> feeds.value.firstOrNull { it.id == f.feedId }?.folder_id
        else -> null
    }

    /**
     * The feed a newly subscribed feed should be inserted directly after, derived from the feed
     * list's current selection: the selected feed itself, whether it's filed in a folder or
     * unfiled. Any other selection (folder/all/starred/tag) yields `null`, which appends
     * the new feed to the end of its target group instead (unchanged from before this feature).
     */
    private fun afterFeedIdForNewFeed(): String? = when (val f = _filter.value) {
        is ArticleFilter.Feed -> f.feedId
        else -> null
    }

    /**
     * The feed a newly subscribed feed should be inserted directly before: the current first feed
     * in the target group (the selected folder, the selected feed's folder, or the "no folder" group
     * when no folder/feed context is selected), or `null` if that group is empty. Only consulted when
     * [afterFeedIdForNewFeed] is null or its target isn't in the group (see
     * [FeedRepository.insertionSortOrderForNewFeed]), so this doesn't affect the "insert directly
     * after the selected feed" behavior.
     */
    private fun beforeFeedIdForNewFeed(): String? =
        feeds.value.filter { it.folder_id == folderIdForNewFeed() }.minByOrNull { it.sort_order }?.id

    /**
     * The tag a newly subscribed feed should be tagged with: the currently selected tag, whether
     * selected directly or via a feed selected within an expanded tag's feed sub-list. `null` for
     * any other selection (folder, feed outside a tag, starred, or nothing selected) — no tag is
     * applied.
     */
    private fun tagIdForNewFeed(): String? = when (val selection = _selectedRowInstance.value) {
        is FeedListRowSelection.Tag -> selection.tagId
        is FeedListRowSelection.FeedInTag -> selection.tagId
        else -> null
    }

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


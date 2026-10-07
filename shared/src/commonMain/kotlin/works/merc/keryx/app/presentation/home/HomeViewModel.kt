package works.merc.keryx.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
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
import works.merc.keryx.app.domain.ActivitySnapshot
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.AddFeedPreviewResolver
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.ArticleSearchResult
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.RefreshCycleRunner
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SubscribeOutcome
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.presentation.ManualSync

/**
 * How long the article-change signal must stay quiet before an active search re-runs — short
 * enough that a read/star toggle's re-searched row still updates promptly, long enough to coalesce
 * a refresh's per-feed commits.
 */
internal const val SEARCH_ARTICLE_CHANGE_DEBOUNCE_MS = 100L

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
    private val activityCenter: ActivityCenter,
    private val clock: Clock,
    private val refreshCycleRunner: RefreshCycleRunner,
    // The one "Sync now" every route shares (CloudSyncController in production) — see [sync].
    private val manualSync: ManualSync,
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
     * [feeds], minus the emissions that change only `etag` / `last_modified` / `updated_at`
     * ([feedsStructurallyEqual]) — what a refresh rewrites once per fetched feed without anything on
     * screen reading it. Consumed by the Apple app, which rebuilds its sidebar, feed lookups and
     * article rows' feed info from this rather than from every [feeds] emission; the comparison
     * runs on [dispatcher] instead of reading every field across the Swift bridge on the main
     * thread. Its `Feeds` may therefore lag [feeds] in those three fields — anything needing the
     * fresh conditional-request fields (a refresh) must resolve the row from [feeds].
     */
    val structuralFeeds: StateFlow<List<Feeds>> =
        feeds.distinctUntilChanged(::feedsStructurallyEqual)
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, emptyList())

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
     * was deleted while the app was closed. The second value says whether the saved filter was
     * actually reproduced (false for any of those fallbacks).
     */
    private fun restoreFilter(): Pair<ArticleFilter, Boolean> {
        val encoded = settingsRepository.getLocalSettings().lastFilter ?: return ArticleFilter.All to false
        val decoded = decodeArticleFilter(encoded) ?: return ArticleFilter.All to false
        val validated = validateFilterTarget(decoded)
        return validated to (validated == decoded)
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

    private val launchFilter = restoreFilter()

    /** Whether the previous session's filter was restored at launch — see [initialHomePane]. */
    val filterRestoredOnLaunch: Boolean = launchFilter.second

    private val _filter = MutableStateFlow<ArticleFilter>(launchFilter.first)
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
    // icon/back arrow (see ArticleListPane's own KDoc). The SwiftUI app's iOS search field (on its
    // article list) binds this to `.searchable`'s `isPresented`. searchActive (below) additionally requires
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

    /** [Starred] uses its own dedicated toggle ([_unreadOnlyStarred]); every other filter shares
     * [_unreadOnly]. Search narrows whichever filter is already selected rather than displacing
     * it (see "Search is orthogonal to ArticleFilter" in app-architecture.md), so it reads the
     * same key as the filter underneath it — there is no separate search-specific toggle. */
    private fun unreadOnlyFor(filter: ArticleFilter, general: Boolean, starred: Boolean): Boolean =
        if (filter == ArticleFilter.Starred) starred else general

    val unreadOnly: StateFlow<Boolean> =
        combine(_filter, _unreadOnly, _unreadOnlyStarred, ::unreadOnlyFor).stateIn(
            viewModelScope,
            started,
            unreadOnlyFor(_filter.value, _unreadOnly.value, _unreadOnlyStarred.value),
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

    // Backs the "new articles" pill (ArticleListPane/NewArticlesPill). Declared before
    // filteredArticles below, which writes to it on every raw query emission: `articles`' own
    // stateIn is Eagerly, so under an immediate test dispatcher the collector can start during this
    // class's own construction, and a property declared later than its first writer would still be
    // null at that point (Kotlin initializes properties in declaration order).
    private val _newArticleTracking = MutableStateFlow(NewArticleTracking())

    // Only the filter keys the DB query: switching filters must switch queries, but the unread-only,
    // sort and pinned inputs are pure display transforms over whatever that query returned. Keeping
    // them in the flatMapLatest key made every article selection (which pins the article it marks
    // read) cancel and re-execute the whole unbounded list query.
    private val filteredArticles: Flow<List<ArticleListRow>> =
        _filter.flatMapLatest { filter ->
            articleRepository.watchArticles(filter)
                // A filter change re-keys flatMapLatest, so this runs before the new filter's first
                // emission and resets tracking to a fresh, unseeded instance — otherwise the new
                // filter's own existing articles would look "new" against the old filter's id set.
                .onStart { resetNewArticleTracking() }
                .onEach { list ->
                    val ids = list.mapTo(HashSet(list.size)) { it.id }
                    // Only an id not seen before under this filter *and* inserted after the
                    // watermark is new — the rowid lookup is what rules out an existing article
                    // re-entering the query (re-starred, its feed moved into this folder/tag). It
                    // runs only when there is a candidate at all, so the common re-emission (a read
                    // or star toggle) issues no extra query. Runs on `dispatcher` (articles'
                    // flowOn), like the list query itself.
                    val tracking = _newArticleTracking.value
                    val candidates = tracking.candidatesIn(ids)
                    val inserted = if (candidates.isEmpty()) {
                        emptySet()
                    } else {
                        articleRepository.articleIdsInsertedAfter(tracking.insertedAfterRowId, candidates)
                    }
                    _newArticleTracking.update { it.withList(ids, inserted) }
                }
        }

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
                //
                // Only a pinned row whose resolved fields differ is replaced; every other row keeps
                // its instance, and once the writes have landed the raw list itself is returned.
                var resolved: MutableList<ArticleListRow>? = null
                val presentPinnedIds = HashSet<String>()
                list.forEachIndexed { index, row ->
                    val readPin = pinnedRead[row.id]
                    val unstarPin = pinnedUnstarred[row.id]
                    if (readPin == null && unstarPin == null) return@forEachIndexed
                    presentPinnedIds += row.id
                    val isRead = readPin?.is_read ?: row.is_read
                    val isStarred = unstarPin?.is_starred ?: row.is_starred
                    if (isRead != row.is_read || isStarred != row.is_starred) {
                        val target = resolved ?: list.toMutableList().also { resolved = it }
                        target[index] = row.copy(is_read = isRead, is_starred = isStarred)
                    }
                }
                resolvedList = resolved ?: list
                extra = (pinnedRead.keys + pinnedUnstarred.keys).filter { it !in presentPinnedIds }.map { id ->
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

    // The article list's viewport as of its last markArticlesSeen report — what newArticleCount
    // measures "the fresh side" against. null until the list first reports.
    private val _visibleRange = MutableStateFlow<VisibleRange?>(null)

    /**
     * Count for the article list's "new articles" pill — ids tracked by [_newArticleTracking] that
     * are also in the currently displayed [articles] (so an unseen id hidden by unread-only doesn't
     * inflate the count; it reappears if the toggle is turned back off) *and* sit beyond the
     * viewport on the list's fresh side (see [freshSideUnseenCount]), so scrolling to the fresh end
     * always clears the pill. Always `0` while search is active, since search results aren't what
     * [_newArticleTracking] was seeded from.
     */
    val newArticleCount: StateFlow<Int> =
        combine(
            _newArticleTracking, articles, searchActive, _visibleRange, _newestFirst,
        ) { tracking, list, searching, viewport, newest ->
            // Short-circuits before touching the list: this re-runs on every visible-row change
            // while scrolling, and nothing is unseen almost all of the time.
            if (searching || tracking.unseenIds.isEmpty()) 0 else freshSideUnseenCount(list, tracking.unseenIds, viewport, newest)
        }.stateIn(viewModelScope, started, 0)

    /**
     * Reports the article ids currently visible in the list, in display order — clearing them from
     * [newArticleCount], and recording the viewport that count's fresh side is measured against.
     */
    fun markArticlesSeen(ids: List<String>) {
        _visibleRange.value = if (ids.isEmpty()) null else VisibleRange(ids.first(), ids.last())
        if (ids.isEmpty()) return
        _newArticleTracking.update { it.withVisible(ids.toSet()) }
    }

    /** The "new articles" pill's own tap action — jumps to the fresh end of the list. */
    fun markAllArticlesSeen() {
        _newArticleTracking.update { it.allSeen() }
    }

    /**
     * Re-seeds [_newArticleTracking]'s baseline from scratch — used by [filteredArticles]'s
     * `onStart` (a filter change): whatever the new filter's first raw query emission contains was
     * not "missed" by the user, so it must become the new baseline rather than being diffed against
     * the previous filter's id set. ([subscribeFeeds] deliberately does not reset — it acknowledges
     * just the subscribed feeds' articles via [withAcknowledged].)
     *
     * Also takes the insertion watermark. `onStart` runs before the new filter's query first
     * executes, so the watermark can never be later than the baseline snapshot: a row inserted
     * in between is above the watermark and, if it's missing from that snapshot, still counts once
     * a later emission picks it up.
     */
    private fun resetNewArticleTracking() {
        _newArticleTracking.value = NewArticleTracking(insertedAfterRowId = articleRepository.maxArticleRowId())
    }

    private val _selectedArticle = MutableStateFlow<Articles?>(null)
    val selectedArticle: StateFlow<Articles?> = _selectedArticle

    /** The display title of the feed that owns the currently selected article, if any. */
    val selectedFeedName: StateFlow<String?> = combine(feeds, selectedArticle) { feeds, article ->
        article?.let { a -> feeds.find { it.id == a.feed_id }?.displayTitle() }
    }.stateIn(viewModelScope, started, null)

    /** The favicon URL of the feed that owns the currently selected article, if any. */
    val selectedFeedFaviconUrl: StateFlow<String?> = combine(feeds, selectedArticle) { feeds, article ->
        article?.let { a -> feeds.find { it.id == a.feed_id }?.favicon_url }
    }.stateIn(viewModelScope, started, null)

    /** Backing state of [selectionCursorId], kept observable for [selectedArticleShownUnread]. */
    private val _selectionCursor = MutableStateFlow<String?>(null)

    /**
     * The list cursor for keyboard navigation.
     *
     * [selectArticle] loads the article body asynchronously, so [_selectedArticle] lags the user's
     * intent; this is updated synchronously instead, which keeps a held arrow key advancing at
     * key-repeat speed and lets a hydration whose selection was cleared (a filter switch) or moved
     * elsewhere recognise it. Which of several selections is the newest is told by
     * [latestSelectionToken]. Only ever written on the ViewModel's (main) context, so it needs no
     * synchronization.
     */
    private var selectionCursorId: String?
        get() = _selectionCursor.value
        set(value) {
            _selectionCursor.value = value
        }

    /**
     * Whether the reader's read/unread button should show the unread state: [selectedArticle] is unread
     * *and* is the article the selection cursor points at.
     *
     * [selectedArticle] lags the cursor while a newly selected article's body loads, so for that moment it
     * is still the previous article. Drawing the previous article's unread state there would flip the
     * button as soon as the new article (read by its selection) arrives, which iOS animates. Until then the
     * button shows the state the incoming article will almost always have — read; an explicit unread
     * made on it, or a restored unread article, is shown as soon as it applies.
     */
    val selectedArticleShownUnread: StateFlow<Boolean> = combine(_selectedArticle, _selectionCursor, this::shownUnread)
        .stateIn(viewModelScope, started, false)

    /**
     * [selectedArticleShownUnread] right now, read synchronously.
     *
     * For a caller that cannot wait for the flow to deliver — the SwiftUI app copies the flow's values
     * asynchronously, so a screen pushed in the same turn as a selection would first draw the stale value
     * and then change it (and animate). Such a caller reads this just after the selection instead.
     */
    fun isSelectedArticleShownUnread(): Boolean = shownUnread(_selectedArticle.value, selectionCursorId)

    private fun shownUnread(article: Articles?, cursor: String?): Boolean =
        article != null && article.is_read == 0L && article.id == cursor

    /**
     * Identity of the current browsing context, bumped whenever the whole pinned-read set is dropped
     * for a fresh one ([selectFilter], and a query change in [setSearchQuery]).
     *
     * [selectionCursorId] cannot stand in for this: it carries no scope identity, so a selection
     * made under the new scope puts a non-null id back, and a hydration still in flight from the old
     * one would pass its null check and take back a pin made under the new one. Only ever touched on the
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
            // Debounced: a refresh commits once per fetched feed, and each commit would otherwise
            // re-run the whole FTS query while a search is showing.
            articleChangeSignal.debounce(SEARCH_ARTICLE_CHANGE_DEBOUNCE_MS),
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

    /**
     * The article rows the reader's pager pages through, in the order the list itself shows them.
     *
     * The Flow form of [currentArticles] — deliberately the same resolution, so the pager and
     * [selectNext]/[selectPrevious] can never disagree about what "the next article" is. The
     * imperative one stays for callers that need today's value synchronously ([moveSelection]
     * steps from where the user actually is, not from whatever a Flow last emitted).
     */
    val pagerArticles: StateFlow<List<ArticleListRow>> =
        combine(searchActive, articles, searchResults) { active, rows, results ->
            if (active) results.map { it.article } else rows
        }
            .flowOn(dispatcher)
            // The one flow here that is not `started` (Eagerly): only the reader's pager collects
            // it, and only at a narrow layout, so on desktop — where the pager never exists — this
            // would otherwise re-run `articles`' own list comparison on the main thread for every
            // article write, for nobody.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * Whether the article list toolbar's "hide read" action currently has anything to do: unread
     * only is on, and the list currently on screen (search results while searching, the filter's
     * own list otherwise — the same resolution [pagerArticles] uses) has a read row other than the
     * selected one. Pressing the action re-runs the same re-trim [setUnreadOnly] already applies
     * when turning unread-only on ([pinnedReadArticlesKeepingSelected]), so this only decides
     * whether that re-trim currently has anything left to do.
     */
    val canHideRead: StateFlow<Boolean> =
        combine(unreadOnly, searchActive, articles, searchResults, _selectedArticle) { unread, active, rows, results, selected ->
            unread && hasHideableRead(if (active) results.map { it.article } else rows, selected?.id)
        }.stateIn(viewModelScope, started, false)

    /** Runs [ArticleListTopBar]'s "hide read" action — see [canHideRead]. */
    fun hideRead() {
        if (canHideRead.value) _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
    }

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

    private val expansion = FeedListExpansion(settingsRepository)

    /** The feed list's collapsed folders — see [FeedListExpansion.collapsedFolderIds]. */
    val collapsedFolderIds: StateFlow<Set<String>> get() = expansion.collapsedFolderIds

    /** The feed list's expanded tags — see [FeedListExpansion.expandedTagIds]. */
    val expandedTagIds: StateFlow<Set<String>> get() = expansion.expandedTagIds

    /**
     * Toggles whether a folder is collapsed and persists the updated state.
     *
     * @param folderId The identifier of the folder to toggle.
     */
    fun toggleFolderCollapsed(folderId: String) = expansion.toggleFolder(folderId)

    /**
     * Toggles whether a tag's attached-feed list is expanded and persists the updated state.
     *
     * @param tagId The identifier of the tag to toggle.
     */
    fun toggleTagExpanded(tagId: String) {
        val expanded = expansion.toggleTag(tagId)
        // A collapsed tag no longer renders its nested feed rows, so a selection on one of them
        // falls back to that feed's canonical row.
        val instance = _selectedRowInstance.value
        if (!expanded && instance is FeedListRowSelection.FeedInTag && instance.tagId == tagId) {
            _selectedRowInstance.value = FeedListRowSelection.FeedInFolderGroup(instance.feedId)
        }
    }

    /**
     * Expands whatever collapsed folder or tag hides the exact row [instance] (see
     * [FeedListExpansion.reveal]) — collapse state is device-local and never synced. Called before an
     * inline rename starts on the selection (F2/Return, the Feed menu), whose editor needs a
     * rendered row.
     */
    fun revealFeedListRow(instance: FeedListRowSelection) = expansion.reveal(instance, feeds.value)

    /** Whether the previous session's selected article was restored at launch — see [initialHomePane]. */
    val articleRestoredOnLaunch: Boolean

    /**
     * The pane that should hold keyboard focus when the home screen first appears, fixed at
     * construction from what this launch managed to restore — see [resolveInitialHomePane]. Read by
     * the Apple app; desktop Compose keeps its own `HomeLayoutViewModel.getInitialFocusedPane`.
     */
    val initialHomePane: InitialHomePane

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
        articleRestoredOnLaunch = restoredArticle != null
        initialHomePane = resolveInitialHomePane(
            savedPane = settingsRepository.getLocalSettings().lastFocusedPane,
            filterRestored = filterRestoredOnLaunch,
            articleRestored = articleRestoredOnLaunch,
        )

        // Any write to `articles` can be a sync merge propagating a soft-delete tombstone for an
        // article currently pinned here; revalidate the pins so a deleted one can't stay visible.
        articleChangeSignal
            .onEach { reconcilePinnedArticlesAndSelection() }
            .flowOn(dispatcher)
            .launchIn(viewModelScope)
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
        // across the switch would restore the selection, or take back a pin made under the new
        // filter. The
        // cursor alone cannot carry that veto — a selection made under the new filter puts a
        // non-null id straight back — so the epoch records the switch itself.
        selectionCursorId = null
        browsingEpoch++
        settingsRepository.mutateLocalSettings { it.copy(lastFilter = filter.encode(), lastArticleId = null) }
    }

    private val articleContentCache = ArticleContentCache(
        scope = viewModelScope,
        dispatcher = dispatcher,
        load = { articleRepository.getArticleById(it) },
    )

    /**
     * Article bodies the reader's pager is holding ready, keyed by article id — see
     * [ArticleContentCache], which owns the loading, the bound and the eviction.
     *
     * Filled by [requestArticleContent]; never by [selectArticle], which is the one path that marks
     * an article read. The article currently in [selectedArticle] may also appear here: the reader
     * merges its own fully-loaded row in ahead of this map (see `ArticlePagerSync.readerContents`),
     * so the copy on screen is always the authoritative one, while the copy kept here is what lets
     * a page stay rendered after the selection has moved on to its neighbour.
     */
    val articleContents: StateFlow<Map<String, ArticleReaderRow>> = articleContentCache.rows

    /**
     * Loads [id]'s body into [articleContents], unless it is already there or already loading.
     *
     * Does **not** mark the article read and does not touch the selection — that is what lets the
     * pager render a neighbouring page without it counting as opened.
     *
     * @param id The article to hydrate.
     */
    fun requestArticleContent(id: String) = articleContentCache.request(id)

    /**
     * Forgets every hydrated body — called when the reader leaves the composition, so a long
     * reading session's article bodies do not stay resident for this ViewModel's whole life.
     */
    fun clearArticleContents() = articleContentCache.clear()

    /**
     * Explicit read-state intents made while a [selectArticle] hydration is still loading — see
     * [SelectionReadIntents]. Recorded by [setRead] and [markAllRead].
     */
    private val readIntents = SelectionReadIntents()

    /**
     * Identity of the newest [selectArticle] call: only the hydration holding the latest token may
     * update the reader. Unlike comparing [selectionCursorId] to the article id, this also tells two
     * selections of the *same* article apart, so an older one finishing later cannot overwrite the
     * newer one's result. Main thread only, like [selectionCursorId].
     */
    private var latestSelectionToken = 0L

    /**
     * Selects an existing article, loads its full content, and marks it as read.
     *
     * @param article The article row to select.
     */
    fun selectArticle(article: ArticleListRow) {
        // Marking read is unconditional (external-spec §7: read the instant it is selected), so an
        // article passed over by a fast key repeat is still marked read. Enqueued here, at call time,
        // rather than after the body lookup below: dbWriteDispatcher runs writes one at a time in
        // the order they were enqueued, so this keeps DB writes in the user's order — a "mark as
        // unread" on this article made while the lookup is still waiting lands after this write,
        // not before it. Also dispatched before the optimistic pin below — see
        // reconcilePinnedArticlesAndSelection's own KDoc for why that order is load-bearing. Should a
        // sync merge tombstone the row concurrently, marking it read only stamps read_at/updated_at;
        // deletion's last-write-wins runs on deleted_updated_at, which this write never touches, so
        // it cannot revive or otherwise disturb the deletion.
        viewModelScope.launch(dbWriteDispatcher) { articleRepository.markAsRead(article.id) }
        // Synchronous, so keyboard navigation always steps from where the user actually is rather
        // than from whatever the last completed hydration left in _selectedArticle.
        selectionCursorId = article.id
        // Selecting reads the article (its write is already enqueued above), so reselecting the one already
        // on screen shows it read at once rather than once its body has reloaded — otherwise the reader
        // toolbar's read/unread button would start out unread and then flip (which iOS animates).
        if (_selectedArticle.value?.let { it.id == article.id && it.is_read == 0L } == true) {
            _selectedArticle.update { it?.copy(is_read = 1L) }
        }
        val token = ++latestSelectionToken
        // Stamped here: the hydration below must only touch the pin within the browsing context the
        // user actually selected in, not whichever one is current when its DB lookup returns.
        val epoch = browsingEpoch
        // Pinned now, right after the write is enqueued, rather than once the body has loaded: the
        // write can commit while the lookup is still waiting (e.g. out a sync's busy_timeout), and an
        // unread-only list would then drop the selected row until the hydration caught up. Pinned
        // even if a newer selection supersedes this one, so an unread-only list cannot collapse
        // under a held key.
        val pinnedAtSelect = article.is_read == 0L
        if (pinnedAtSelect) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        }
        // Read intents recorded after this point override this selection's implicit "read". Begun
        // synchronously and ended in the coroutine's `finally`; UNDISPATCHED runs the body up to its
        // first suspension even in an already-cancelled scope, so the `finally` — and with it end() —
        // always runs, keeping the two paired.
        val seqAtSelect = readIntents.begin()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hydrateSelection(article, token, epoch, pinnedAtSelect, seqAtSelect)
            } finally {
                readIntents.end()
            }
        }
    }

    /** The asynchronous half of [selectArticle]: loads the body and applies the selection. */
    private suspend fun hydrateSelection(
        article: ArticleListRow,
        token: Long,
        epoch: Int,
        pinnedAtSelect: Boolean,
        seqAtSelect: Long,
    ) {
        // The list row carries no body, so the detail pane's copy is loaded here — one PK lookup on
        // selection, in place of loading every article's body on every list emission. Off the UI
        // thread: this pulls the whole row including `content`, and the JVM driver opens a fresh
        // connection per statement, so under a sync merge's or refresh's write lock it can wait out
        // the whole busy_timeout. Held arrow keys would do that ~30 times a second.
        val full = withContext(dispatcher) { articleRepository.getArticleById(article.id) }
        // A null (or tombstoned) row means a sync merge deleted it between the emission the user
        // clicked and the click itself: leave the selection and the persisted id entirely alone, and
        // take back the pin selectArticle added, rather than resurrecting deleted content into the
        // list (the `articles` merge re-adds any pinned id missing from the query result) or
        // restoring it on the next launch. A pin dropped by a browsing-context switch since is not
        // this hydration's to touch.
        if (full == null || full.deleted_at != null) {
            if (pinnedAtSelect && epoch == browsingEpoch) _pinnedReadArticles.update { it - article.id }
            // Resume navigating from what is actually on screen, not from the dead article.
            if (selectionCursorId == article.id) selectionCursorId = _selectedArticle.value?.id
            return
        }
        // Nothing is selected any more (a filter switch, or an earlier hydration finding its own
        // article tombstoned), or a newer selection — possibly of this same article — owns the
        // reader: an older lookup that finished later must not put its result back on screen.
        val cursor = selectionCursorId ?: return
        if (cursor != article.id || token != latestSelectionToken) return
        // An explicit read-state intent made on this article after it was selected (e.g. "Mark as
        // unread" from the context menu that selected it) wins over the selection's implicit read;
        // its own DB write was enqueued after selectArticle's markAsRead, so the DB agrees, and that
        // intent already updated the pin itself.
        val readNow = readIntents.since(article.id, seqAtSelect) ?: true
        // Optimistic: show the intended read state immediately; its persist already went out.
        _selectedArticle.value = full.copy(is_read = if (readNow) 1L else 0L)
        settingsRepository.mutateLocalSettings { it.copy(lastArticleId = article.id) }
    }

    fun selectNext() = moveSelection(1)
    fun selectPrevious() = moveSelection(-1)

    /**
     * Whether [selectNext] would actually land on a different article.
     *
     * Read by the article reader's swipe gesture (`ui/home/ArticleSwipeNav.kt`) to decide whether a
     * drag in that direction moves the content or only rubber-bands, and by its screen-reader
     * actions, so the two must agree with [moveSelection]: at the last article both do nothing
     * (J/↓ is a no-op there, exactly like a swipe), which is why both resolve the current row
     * through the same [selectionIndex] helper.
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
     * Whether [selectPrevious] would actually land on a different article. Agrees with
     * [moveSelection], which leaves the first article selected as a no-op (K/↑ there does nothing,
     * like a swipe past the start).
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

    /**
     * Moves the selection [delta] rows. With nothing selected it opens the first row; at either end
     * of the list it does nothing — re-selecting the same article would mark it read again (undoing
     * a "mark as unread"), and the swipe gesture and screen-reader actions already stop there
     * ([canSelectNext]/[canSelectPrevious]).
     */
    private fun moveSelection(delta: Int) {
        val list = currentArticles()
        if (list.isEmpty()) return
        val index = selectionIndex(list)
        val next = if (index < 0) 0 else index + delta
        if (next == index || next !in list.indices) return
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
     * Toggles the read state of an article and persists the change.
     *
     * @param article The article whose read state should be toggled.
     */
    fun toggleRead(article: ArticleListRow) = setRead(article, read = article.is_read == 0L)

    /**
     * Sets an article's read state to [read] and persists it — an explicit intent rather than a
     * toggle, so a caller that displayed "Mark as unread" (e.g. a context menu whose row became read
     * through its own `onOpen`) always gets exactly that, whatever [article]'s snapshot says.
     * Setting the state the article already has is a harmless no-op in effect.
     *
     * @param article The article whose read state should be set.
     * @param read Whether the article should end up read.
     */
    fun setRead(article: ArticleListRow, read: Boolean) {
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) {
            if (read) articleRepository.markAsRead(article.id) else articleRepository.markAsUnread(article.id)
        }
        readIntents.record(article.id, read)
        if (read) {
            _pinnedReadArticles.update { it + (article.id to article.copy(is_read = 1L)) }
        } else {
            _pinnedReadArticles.update { it - article.id }
        }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_read = if (read) 1L else 0L) }
        }
    }

    /**
     * Toggles the read state of the selected article.
     *
     * The one implementation behind every route to "mark as read / unread" for the displayed article —
     * the Article menu's toggle item and the reader toolbar's read/unread button on both UIs — so the
     * effect is always the opposite of the state the toolbar is showing (see [selectedArticleShownUnread]).
     */
    fun toggleReadSelected() = _selectedArticle.value?.let { toggleRead(it.toListRow()) }

    /**
     * Toggles the starred state of an article.
     *
     * @param article The article whose starred state should be toggled.
     */
    fun toggleStar(article: ArticleListRow) = setStarred(article, starred = article.is_starred == 0L)

    /**
     * Sets an article's starred state to [starred] — the explicit-intent counterpart of
     * [toggleStar], for a caller that displayed a specific "Star"/"Unstar" label (see [setRead]).
     *
     * @param article The article whose starred state should be set.
     * @param starred Whether the article should end up starred.
     */
    fun setStarred(article: ArticleListRow, starred: Boolean) {
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
            // The raw search snapshot can lag an optimistic unread change (e.g. toggleReadSelected()
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
        if (active && idsToMark.isEmpty()) {
            // Nothing in the current search results needs marking read; skip both the DB write and
            // the dependent search refresh.
            return
        }
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) {
            if (active) {
                articleRepository.markArticlesAsRead(idsToMark)
                // Re-run search only after the write lands so the freshly-read state shows up.
                _searchRefreshTrigger.update { it + 1 }
            } else {
                articleRepository.markAllAsRead(filter)
            }
        }
        // An explicit "read" for everything this writes, so a selection still loading its body cannot
        // apply an earlier "mark as unread" over it (see SelectionReadIntents). Under search that is
        // exactly idsToMark; otherwise markAllAsRead marks the whole filter, which holds every
        // visible row as well as the selection (including one still loading).
        if (marksSelectedRead) {
            val marked = if (active) idsToMark else visibleUnread.map { it.id } + listOfNotNull(selected?.id, selectionCursorId)
            marked.forEach { readIntents.record(it, read = true) }
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
            // Starred: markAllAsRead is a no-op, don't alter read state. A selection still loading
            // its body keeps the pin selectArticle gave it, just as the selected article keeps its own.
            val pins = mutableMapOf<String, ArticleListRow>()
            val cursor = selectionCursorId
            if (cursor != null && cursor != selected?.id) _pinnedReadArticles.value[cursor]?.let { pins[cursor] = it }
            if (selected != null) pins[selected.id] = selected.toListRow()
            _pinnedReadArticles.value = pins
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
     * @return A map containing the selected article — or, while a newer selection is still
     *   loading, that selection's own row — if it is read and not deleted; an empty map otherwise.
     */
    private fun pinnedReadArticlesKeepingSelected(): Map<String, ArticleListRow> {
        val selected = _selectedArticle.value
        val cursor = selectionCursorId
        if (cursor != null && cursor != selected?.id) {
            // A newer selection is still loading its body, so [_selectedArticle] is the article
            // being replaced. That selection is the one to keep: its own pin (selectArticle pins a
            // row that was unread) or, for a row that was already read, its row as listed — or it
            // disappears from an unread-only list for good, since its hydration never pins.
            val pending = _pinnedReadArticles.value[cursor]
                ?: currentArticles().firstOrNull { it.id == cursor && it.is_read == 1L }
                ?: return emptyMap()
            if (pending.id !in articleRepository.aliveArticleFlags(listOf(pending.id))) return emptyMap()
            return mapOf(pending.id to pending)
        }
        if (selected == null || selected.is_read != 1L) return emptyMap()
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
     * [toggleStar]/[markAllRead]'s own comments) — but nothing here ever
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
    suspend fun subscribeFeeds(urls: List<String>): SubscribeOutcome {
        val outcome = addFeedPreviewResolver.subscribeFeeds(
            urls,
            folderIdForNewFeed(),
            afterFeedIdForNewFeed(),
            beforeFeedIdForNewFeed(),
            tagIdForNewFeed(),
        )
        // Subscribing is the user's own action, and whatever it fetched is right there in front of
        // them — it was never "missed". Acknowledge exactly the subscribed feeds' articles instead
        // of resetting the whole baseline: a reset would drop any unseen ids the user genuinely
        // hasn't scrolled to yet, and — when the current filter isn't affected by the subscription
        // and so never re-emits — leave the baseline unseeded, so the next real arrival would only
        // re-seed it and be missed. withAcknowledged gives the same result whether the raw query's
        // own emission for the fetched articles lands before or after this point.
        if (outcome.feedIds.isNotEmpty()) {
            val ids = withContext(dbWriteDispatcher) { articleRepository.articleIdsByFeeds(outcome.feedIds) }
            _newArticleTracking.update { it.withAcknowledged(ids) }
        }
        return outcome
    }

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

    /**
     * What's in flight right now — feed refreshes, syncs (manual, debounced, or background), and
     * whole refresh-then-sync sequences, including the gap between a sequence's two operations
     * where neither [ActivitySnapshot.feedRefreshing] nor [ActivitySnapshot.syncing] is up. One
     * consistent snapshot, current the instant any of them starts or ends — see
     * [ActivityCenter.activity].
     */
    val activity: StateFlow<ActivitySnapshot> = activityCenter.activity

    // Refresh / sync actions live in their own class; this ViewModel only delegates to it.
    private val refreshController = HomeRefreshController(
        scope = viewModelScope,
        dispatcher = dispatcher,
        runner = refreshCycleRunner,
        feedRepository = feedRepository,
        activityCenter = activityCenter,
        currentFilter = { _filter.value },
        repinSelected = ::repinSelected,
    )

    /**
     * Re-trims the pinned read articles down to the current selection — around every refresh and
     * every manual sync, so rows read during the previous browse don't outlive it.
     */
    private fun repinSelected() {
        _pinnedReadArticles.value = pinnedReadArticlesKeepingSelected()
    }

    /** Refreshes the specified feed. See [HomeRefreshController.refreshFeed]. */
    fun refreshFeed(feed: Feeds) = refreshController.refreshFeed(feed)

    /**
     * Refreshes all feeds, notifies about newly available articles when enabled, and synchronizes
     * data — unless anything is already in flight. See [HomeRefreshController.refreshAll].
     */
    fun refreshAll() = refreshController.refreshAll()

    /**
     * The selections whose pull-to-refresh is still running. See
     * [HomeRefreshController.pullRefreshingFilters].
     */
    val pullRefreshingFilters: StateFlow<Set<ArticleFilter>> get() = refreshController.pullRefreshingFilters

    /** Pull-to-refresh on the current selection. See [HomeRefreshController.pullToRefresh]. */
    fun pullToRefresh() = refreshController.pullToRefresh()

    /**
     * Pull-to-refresh on every feed, whatever is selected — the iOS sidebar's pull. Tracked as
     * [ArticleFilter.All] in [pullRefreshingFilters]. See [HomeRefreshController.pullToRefresh].
     */
    fun pullToRefreshAll() = refreshController.pullToRefresh(ArticleFilter.All)

    /**
     * "Sync now" from Home (toolbar button, Feed menu). Delegates to the one [ManualSync] every
     * route shares, so it runs under the same guard as the cloud-sync settings tab's button and is
     * a no-op whenever [canSyncNow] is false. The pinned-row re-trim around it happens in the
     * [ManualSync.runs] collector below, not here, so it covers a sync started from Settings too.
     */
    fun sync() = manualSync.syncNow()

    /** See [ManualSync.canSyncNow]. */
    val canSyncNow: StateFlow<Boolean> get() = manualSync.canSyncNow

    /** See [ManualSync.disabledByAuth]. */
    val syncDisabledByAuth: StateFlow<Boolean> get() = manualSync.disabledByAuth

    init {
        // Re-trim on both edges of every manual sync, whichever route started it: before, so rows
        // read until now don't carry into the synced list; after, against the selection as it
        // stands then (it may have changed while the sync ran).
        viewModelScope.launch {
            manualSync.runs.collect { repinSelected() }
        }
    }

    /** Discards the cloud sync data and re-uploads local fresh (recovery for a corrupt/incompatible
     *  cloud DB). Errors surface via the notification center from [SyncRepository]. */
    fun resetCloudData() {
        viewModelScope.launch { withContext(dispatcher) { syncRepository.resetCloudData() } }
    }

    /** See [ManualSync.connected]. */
    val cloudConnected: StateFlow<Boolean> get() = manualSync.connected

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
        expansion.forgetTag(id)
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
        expansion.forgetFolder(id)
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


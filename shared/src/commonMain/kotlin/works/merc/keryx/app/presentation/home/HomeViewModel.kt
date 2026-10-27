package works.merc.keryx.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
import works.merc.keryx.app.core.READER_HEADER_FIRST_GRACE_MS
import works.merc.keryx.app.core.SEARCH_DEBOUNCE_MS
import works.merc.keryx.app.core.searchTerms
import works.merc.keryx.app.core.decodeArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.core.toDbFlag
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
import works.merc.keryx.app.presentation.DerivedStateFlow
import works.merc.keryx.app.presentation.ManualSync
import works.merc.keryx.app.presentation.sameListOrBothEmpty

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

/**
 * The filter and narrowing a displayed list was produced from. The list's flows catch up with a
 * filter or mode change asynchronously, so for a moment the rows on screen can still belong to the
 * previous one — [HomeViewModel.markAllRead] waits until this matches the current selection.
 */
private data class ListScope(val filter: ArticleFilter, val narrowing: ListNarrowing)

/** Rows tagged with the [ListScope] that produced them. */
private data class ScopedRows<T>(val rows: List<T>, val scope: ListScope)

private fun narrowingFor(mode: ArticleListMode) =
    ListNarrowing(unreadOnly = mode == ArticleListMode.Unread, starredOnly = mode == ArticleListMode.Starred)

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

    // What [articleListBody] is decided from: small flags, so deciding it never compares a list.
    // `null` / false until the first DB emission lands, which tells "not loaded yet" apart from
    // "no feeds".
    private val hasFeeds = MutableStateFlow<Boolean?>(null)
    private val articlesLoaded = MutableStateFlow(false)
    private val _articleListHasRows = MutableStateFlow(false)

    // null until the first DB emission lands; every public view of it reads that as an empty list.
    // flowOn directly upstream of stateIn, as for the article list: stateIn fuses it into its own
    // coroutine, so the StateFlow's equality check of each new list — every row, on every feed write
    // a refresh makes — runs on `dispatcher` rather than the main thread.
    private val feedsSnapshot: StateFlow<List<Feeds>?> =
        feedRepository.watchAllFeeds()
            .onEach { hasFeeds.value = it.isNotEmpty() }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, null)

    val feeds: StateFlow<List<Feeds>> = DerivedStateFlow(
        { feedsSnapshot.value.orEmpty() },
        feedsSnapshot.map { it.orEmpty() },
        areEquivalent = ::sameListOrBothEmpty,
    )

    /**
     * [feeds], minus the emissions that change only `etag` / `last_modified` / `updated_at`
     * ([feedsStructurallyEqual]) — what a refresh rewrites once per fetched feed without anything on
     * screen reading it. What both UIs render the feed list, the article rows' feed info and every
     * feed lookup from (the Compose panes and menu bar, and the Apple app's sidebar), so a refresh
     * that only moves those three fields recomposes / rebuilds nothing; the comparison runs on
     * [dispatcher] instead of on the main thread. Its `Feeds` may therefore lag [feeds] in those
     * three fields — which is why [refreshFeed] takes an id and re-reads the row itself.
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

    // ArticleListMode.Starred — session-only by design (never persisted), so a list narrowed to a
    // handful of starred articles can't greet the next launch looking like the feeds stopped updating.
    // Kept across filter switches (browsing several feeds' starred articles in turn), except into the
    // Starred filter, where the mode can't apply and would otherwise linger out of sight.
    private val _starredMode = MutableStateFlow(false)

    /** [Starred] uses its own dedicated toggle ([_unreadOnlyStarred]); every other filter shares
     * [_unreadOnly]. Search narrows whichever filter is already selected rather than displacing
     * it (see "Search is orthogonal to ArticleFilter" in app-architecture.md), so it reads the
     * same key as the filter underneath it — there is no separate search-specific toggle. */
    private fun currentMode(): ArticleListMode =
        articleListModeFor(_filter.value, _unreadOnly.value, _unreadOnlyStarred.value, _starredMode.value)

    /** The article list's mode — see [ArticleListMode]. Changed through [setMode]. */
    val mode: StateFlow<ArticleListMode> =
        combine(_filter, _unreadOnly, _unreadOnlyStarred, _starredMode, ::articleListModeFor)
            .stateIn(viewModelScope, started, currentMode())

    /** Whether [ArticleListMode.Starred] can be chosen right now (not under [ArticleFilter.Starred]). */
    val starredModeEnabled: StateFlow<Boolean> =
        _filter.map(::starredModeAvailable).stateIn(viewModelScope, started, starredModeAvailable(_filter.value))

    /** Whether the list shows unread articles only — [mode] is [ArticleListMode.Unread]. */
    val unreadOnly: StateFlow<Boolean> =
        mode.map { it == ArticleListMode.Unread }
            .stateIn(viewModelScope, started, currentMode() == ArticleListMode.Unread)

    private val narrowing: StateFlow<ListNarrowing> =
        mode.map(::narrowingFor).stateIn(viewModelScope, started, narrowingFor(currentMode()))

    private val _newestFirst = MutableStateFlow(settingsRepository.getLocalSettings().lastNewestFirst ?: true)
    val newestFirst: StateFlow<Boolean> = _newestFirst

    // Read state the user just set on an article while browsing the current filter: read (as a side
    // effect of selection, or an explicit "mark as read") or unread (an explicit "mark as unread"). The
    // pinned value outruns the DB write still in flight, and a read one also keeps the row visible (in
    // read styling) until the user reloads/syncs or switches filters, so the list doesn't shift under
    // the user while reading down an unread list. An unread pin is what keeps the row from dropping out
    // of an unread-only list in the window before its "mark as unread" write lands.
    private val _pinnedReadStates = MutableStateFlow<Map<String, ArticleListRow>>(emptyMap())

    // Articles unstarred while browsing the Starred filter. Kept visible (with the star cleared)
    // until the user switches filters, so the list doesn't shift under the user the instant they
    // unstar something. A separate map from _pinnedReadStates (rather than reusing it) because
    // the two have different reset rules: setUnreadOnly's retrimPinnedReadStates() only
    // re-seeds the read-state pins, and conflating the two would make an unstarred article's grace
    // period dependent on read-state bookkeeping it has nothing to do with.
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
    private val filteredArticles: Flow<Pair<ArticleFilter, List<ArticleListRow>>> =
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
                .map { filter to it }
        }

    // null until the first list emission lands — see [feedsSnapshot].
    private val articlesSnapshot: StateFlow<ScopedRows<ArticleListRow>?> =
        combine(
            filteredArticles, narrowing, _newestFirst, _pinnedReadStates, _pinnedUnstarredArticles,
        ) { (filter, list), narrow, newest, pinnedReadStates, pinnedUnstarred ->
            val rows = resolvePinnedArticles(
                list, narrow.unreadOnly, newest, pinnedReadStates, pinnedUnstarred,
                starredOnly = narrow.starredOnly,
            )
            ScopedRows(rows, ListScope(filter, narrow))
        }
            // Before flowOn, not after: stateIn fuses a flowOn directly upstream of it into its own
            // coroutine, and any operator in between would put a channel hop back.
            .onEach {
                articlesLoaded.value = true
                _articleListHasRows.value = it.rows.isNotEmpty()
            }
            .flowOn(dispatcher)
            .stateIn(viewModelScope, started, null)

    val articles: StateFlow<List<ArticleListRow>> = DerivedStateFlow(
        { articlesSnapshot.value?.rows.orEmpty() },
        articlesSnapshot.map { it?.rows.orEmpty() },
        areEquivalent = ::sameListOrBothEmpty,
    )

    /**
     * Whether the article list the ViewModel holds has any rows — a flag a UI collects alongside
     * [articles], so it can tell "still catching up" from "no articles" ([articleListCatchingUp]).
     * A flow rather than a call that reads the current value: a UI must recompose when this changes,
     * and a value read in passing never makes it — the last change (to `false`, after rows that
     * never reached the UI) would leave the UI blank with no later emission to wake it.
     */
    val articleListHasRows: StateFlow<Boolean> = _articleListHasRows.asStateFlow()

    /**
     * What the article list shows in place of its rows before and without them — see
     * [articleListBody]. [ArticleListBody.Pending] only until the feed list and the article list
     * have each loaded once at launch; a filter switch never returns to it.
     */
    val articleListBody: StateFlow<ArticleListBody> = DerivedStateFlow(
        { articleListBody(hasFeeds.value, articlesLoaded.value) },
        combine(hasFeeds, articlesLoaded, ::articleListBody),
    )

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

    /**
     * The selected article, fully loaded (body included). It catches up with [selectedArticleId] only
     * once [selectArticle]'s body lookup returns, so it is still the previous article until then —
     * what the reader shows is [readerContent], and what the toolbar and every other per-selection
     * action act on is [selectedArticleRow].
     *
     * Not a source for any UI, so not part of the API the UIs see: reading it for the toolbar or a
     * shortcut is what made those act on the previous article while a selection's body loaded.
     * Kept `internal` for this module's tests of the hydration.
     */
    internal val selectedArticle: StateFlow<Articles?> = _selectedArticle

    /**
     * Explicit read-state intents made while a [selectArticle] hydration is still loading — see
     * [SelectionReadIntents]. Recorded by [setRead] and [markAllRead].
     */
    private val readIntents = SelectionReadIntents()

    /**
     * The star counterpart of [readIntents] (its Boolean means "starred"), recorded by [setStarred]: a
     * hydration's lookup runs on [dispatcher], outside [dbWriteDispatcher]'s FIFO, so it can read the
     * row from before a star toggled while it was waiting, and this is what it lays back over it.
     */
    private val starIntents = SelectionReadIntents()

    /** Backing state of [selectionCursorId], kept observable for [selectedArticleShownUnread]. */
    private val _selectionCursor = MutableStateFlow<String?>(null)

    /**
     * [SelectionReadIntents.currentSeq] as of the selection that put the cursor where it is, so only an intent
     * made on the cursor's article *after* that selection counts towards [selectedArticleShownUnread]
     * ([pendingReadIntent]). `Long.MAX_VALUE` — nothing counts — for a cursor not set by [selectArticle].
     */
    private val _cursorIntentSeq = MutableStateFlow(Long.MAX_VALUE)

    /**
     * The list row the newest [selectArticle] was called with (as read, since selecting reads it), with
     * the optimistic read/star writes made on it since — what [selectedArticleRow] and [readerContent]
     * show for the selection until its body has loaded. Cleared once it has, and by [selectFilter].
     */
    private val _pendingSelectedRow = MutableStateFlow<ArticleListRow?>(null)

    /**
     * The article whose header [readerContent] shows while its body is still loading: set by
     * [hydrateSelection]'s grace timer once [READER_HEADER_FIRST_GRACE_MS] has passed without the body,
     * cleared once it lands, by [selectFilter], and by a tombstoned lookup.
     */
    private val _headerArticleId = MutableStateFlow<String?>(null)

    /**
     * The id of the article the user selected, updated the instant [selectArticle] is called — before
     * its body has loaded, unlike [selectedArticle]. What the article list highlights and scrolls to,
     * what the pager follows, and what a URL copy's ✓ is matched against.
     */
    val selectedArticleId: StateFlow<String?> = _selectionCursor.asStateFlow()

    private val _selectionRevealRequests = MutableStateFlow(0)

    /**
     * Counts the user's selections that move to another article — one per such [selectArticle],
     * whatever route made it (a click, J/K, a notification); choosing the article that is already
     * selected is not counted — so the article list scrolls the selection into view when this changes, and
     * not whenever [selectedArticleId] does. That id also moves without the user acting: a selection
     * whose row turns out to have been tombstoned by a sync merge hands the cursor back to the
     * article before it, and the list must not scroll back to that article on its own
     * (external-spec §9, "Nothing happens unprompted").
     */
    val selectionRevealRequests: StateFlow<Int> = _selectionRevealRequests.asStateFlow()

    /**
     * The selected article's list row, current the instant [selectArticle] is called: the loaded
     * article once its body has landed, the row it was selected with until then (see
     * [resolveSelectedRow]), with every optimistic read/star write made on it either way. What the
     * reader's toolbar (star, open, copy, share), the menu bar and the keyboard shortcuts act on, so
     * none of them can act on — or show the feed of — the previous article while the body loads.
     * The read/unread button's own state is [selectedArticleShownUnread], never this row's `is_read`.
     */
    val selectedArticleRow: StateFlow<ArticleListRow?> = DerivedStateFlow(
        { resolveSelectedRow(_selectionCursor.value, _selectedArticle.value, _pendingSelectedRow.value) },
        combine(_selectionCursor, _selectedArticle, _pendingSelectedRow, ::resolveSelectedRow),
    )

    /**
     * What the single (non-pager) reader shows — see [resolveReaderContent]. Normally the previous
     * article stays until the new one's body has loaded and then the new one is shown in full, a
     * single document load; only a lookup that outlasts [READER_HEADER_FIRST_GRACE_MS] (one waiting
     * out a sync merge's or an FTS rebuild's write lock) shows the new article's header first.
     */
    val readerContent: StateFlow<ReaderContent> = DerivedStateFlow(
        { resolveReaderContent(_selectionCursor.value, _selectedArticle.value, _pendingSelectedRow.value, _headerArticleId.value) },
        combine(_selectionCursor, _selectedArticle, _pendingSelectedRow, _headerArticleId, ::resolveReaderContent),
    )

    /** The feed [feedId] names among [feeds], if any. */
    private fun selectedFeed(feeds: List<Feeds>, feedId: String?): Feeds? = feedId?.let { id -> feeds.find { it.id == id } }

    /**
     * The feed of [selectedArticleRow] — not of [selectedArticle] — so the reader's toolbar never names
     * the previous article's feed while the new one's body loads.
     */
    private val selectedFeedId: Flow<String?> = selectedArticleRow.map { it?.feed_id }.distinctUntilChanged()

    /** The display title of the feed that owns the currently selected article, if any. */
    val selectedFeedName: StateFlow<String?> = selectedFeedField { it.displayTitle() }

    /** The favicon URL of the feed that owns the currently selected article, if any. */
    val selectedFeedFaviconUrl: StateFlow<String?> = selectedFeedField { it.favicon_url }

    /** One field of the feed that owns the currently selected article (see [selectedFeedId]). */
    private fun <T> selectedFeedField(pick: (Feeds) -> T?): StateFlow<T?> = DerivedStateFlow(
        { selectedFeed(structuralFeeds.value, selectedArticleRow.value?.feed_id)?.let(pick) },
        combine(structuralFeeds, selectedFeedId) { feeds, id -> selectedFeed(feeds, id)?.let(pick) },
    )

    /**
     * The list cursor for keyboard navigation — the backing of [selectedArticleId].
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
            // A cursor not set by selectArticle has no selection to count intents from; selectArticle
            // overrides this right after its own write.
            _cursorIntentSeq.value = Long.MAX_VALUE
            _selectionCursor.value = value
        }

    /**
     * Whether the reader's read/unread button should show the unread state (see [isShownUnread]).
     * An explicit unread made on the incoming article while its body loads (read back from [readIntents], the
     * same record the hydration applies, so every route that records one is covered), or a restored unread
     * article, is shown as soon as it applies. The button's one source — never [selectedArticleRow]'s
     * `is_read`.
     */
    val selectedArticleShownUnread: StateFlow<Boolean> =
        combine(_selectedArticle, _selectionCursor, _cursorIntentSeq, readIntents.state) { article, cursor, seq, intents ->
            isShownUnread(article, cursor, pendingReadIntent(intents, cursor, seq))
        }
        .stateIn(viewModelScope, started, false)

    /**
     * [selectedArticleShownUnread] right now, read synchronously.
     *
     * For a caller that cannot wait for the flow to deliver — the SwiftUI app copies the flow's values
     * asynchronously, so a screen pushed in the same turn as a selection would first draw the stale value
     * and then change it (and animate). Such a caller reads this just after the selection instead.
     */
    fun isSelectedArticleShownUnread(): Boolean = isShownUnread(
        _selectedArticle.value,
        selectionCursorId,
        pendingReadIntent(readIntents.state.value, selectionCursorId, _cursorIntentSeq.value),
    )

    /**
     * Identity of the current browsing context, bumped whenever the whole read-state pin set is dropped
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
            // doesn't auto-notify. search() absorbs an unavailable index (absent before the first startup catch-up) itself.
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
    private val searchResultsSnapshot: StateFlow<ScopedRows<ArticleSearchResult>> =
        combine(
            _rawSearchResults, narrowing, _pinnedReadStates, _pinnedUnstarredArticles,
        ) { snapshot, narrow, pinnedReadStates, pinnedUnstarred ->
            val results = resolvePinnedSearchResults(
                snapshot.results, narrow.unreadOnly, pinnedReadStates, pinnedUnstarred,
                starredOnly = narrow.starredOnly,
            )
            ScopedRows(results, ListScope(snapshot.filter, narrow))
        }.flowOn(dispatcher).stateIn(
            viewModelScope,
            started,
            ScopedRows(emptyList(), ListScope(_rawSearchResults.value.filter, narrowing.value)),
        )

    val searchResults: StateFlow<List<ArticleSearchResult>> = DerivedStateFlow(
        { searchResultsSnapshot.value.rows },
        searchResultsSnapshot.map { it.rows },
    )

    /** The scope of the rows on screen — see [ListScope]; null before the article list first loads. */
    private fun displayedScope(): ListScope? =
        if (searchActive.value) searchResultsSnapshot.value.scope else articlesSnapshot.value?.scope

    /** The Flow form of [displayedScope]. */
    private val displayedScopes: Flow<ListScope?> =
        combine(searchActive, articlesSnapshot, searchResultsSnapshot) { active, rows, results ->
            if (active) results.scope else rows?.scope
        }

    private fun currentScope() = ListScope(_filter.value, narrowingFor(currentMode()))

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
            displayedRows(active, rows, results)
        }
            .flowOn(dispatcher)
            // The one flow here that is not `started` (Eagerly): only the reader's pager collects
            // it, and only at a narrow layout, so on desktop — where the pager never exists — this
            // would otherwise re-run `articles`' own list comparison on the main thread for every
            // article write, for nobody.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * Whether the View menu's "hide read" command currently has anything to do: unread
     * only is on, and the list currently on screen (search results while searching, the filter's
     * own list otherwise — the same resolution [pagerArticles] uses) has a read row other than the
     * selected one. Pressing the action re-runs the same re-trim [setUnreadOnly] already applies
     * when turning unread-only on ([retrimPinnedReadStates]), so this only decides
     * whether that re-trim currently has anything left to do.
     */
    val canHideRead: StateFlow<Boolean> =
        combine(unreadOnly, searchActive, articles, searchResults, _selectionCursor) { unread, active, rows, results, selectedId ->
            unread && hasHideableRead(displayedRows(active, rows, results), selectedId)
        }.stateIn(viewModelScope, started, false)

    /** Runs the View menu's "Hide Read Articles" command — see [canHideRead]. */
    fun hideRead() {
        if (canHideRead.value) retrimPinnedReadStates()
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
        if (!expanded) fallBackFromTagRow(tagId)
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
                _pinnedReadStates.update { it + (restoredArticle.id to restoredArticle.toListRow()) }
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
     * Selects the active article filter and clears the current article selection and the read-state pins.
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
        _pendingSelectedRow.value = null
        _headerArticleId.value = null
        _pinnedReadStates.value = emptyMap()
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
        // Set before the cursor, so selectedArticleRow never resolves the new cursor against the
        // previous selection's row.
        _pendingSelectedRow.value = article.copy(is_read = 1L)
        // Synchronous, so keyboard navigation always steps from where the user actually is rather
        // than from whatever the last completed hydration left in _selectedArticle.
        val movedSelection = selectionCursorId != article.id
        selectionCursorId = article.id
        // After the cursor, so a collector that sees the new count already sees the new id. Only a
        // selection that moves to another article is a request: choosing the one already selected
        // (a second click, a right-click) leaves the list where it is, as it always has.
        if (movedSelection) _selectionRevealRequests.update { it + 1 }
        _cursorIntentSeq.value = readIntents.currentSeq
        // A header already standing in for a body still loading (a write lock is holding the
        // lookups) moves straight to this selection, rather than the reader going back to the
        // article before it for a new grace period. Otherwise this selection gets its own grace.
        _headerArticleId.value =
            article.id.takeIf { _headerArticleId.value != null && _selectedArticle.value?.id != it }
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
            _pinnedReadStates.update { it + (article.id to article.copy(is_read = 1L)) }
        }
        // Read intents recorded after this point override this selection's implicit "read". Begun
        // synchronously and ended in the coroutine's `finally`; UNDISPATCHED runs the body up to its
        // first suspension even in an already-cancelled scope, so the `finally` — and with it end() —
        // always runs, keeping the two paired.
        val seqAtSelect = readIntents.begin()
        val starSeqAtSelect = starIntents.begin()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hydrateSelection(article, token, epoch, pinnedAtSelect, seqAtSelect, starSeqAtSelect)
            } finally {
                readIntents.end()
                starIntents.end()
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
        starSeqAtSelect: Long,
    ) {
        // Shows this article's header in the reader if its body has not landed within the grace
        // period. Cancelled in the `finally` below on every way out, and re-checks on firing that this
        // is still the newest selection, still on the cursor and still not loaded.
        val headerTimer = viewModelScope.launch {
            delay(READER_HEADER_FIRST_GRACE_MS)
            if (token == latestSelectionToken && selectionCursorId == article.id && _selectedArticle.value?.id != article.id) {
                _headerArticleId.value = article.id
            }
        }
        try {
            applyHydratedSelection(article, token, epoch, pinnedAtSelect, seqAtSelect, starSeqAtSelect)
        } finally {
            headerTimer.cancel()
        }
    }

    /** [hydrateSelection]'s body lookup, and the selection it applies once that returns. */
    private suspend fun applyHydratedSelection(
        article: ArticleListRow,
        token: Long,
        epoch: Int,
        pinnedAtSelect: Boolean,
        seqAtSelect: Long,
        starSeqAtSelect: Long,
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
            if (pinnedAtSelect && epoch == browsingEpoch) _pinnedReadStates.update { it - article.id }
            // Resume navigating from what is actually on screen, not from the dead article — and stop
            // standing in for it in the reader and the toolbar.
            if (selectionCursorId == article.id) selectionCursorId = _selectedArticle.value?.id
            if (_headerArticleId.value == article.id) _headerArticleId.value = null
            if (_pendingSelectedRow.value?.id == article.id) _pendingSelectedRow.value = null
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
        // Likewise a star toggled since the selection, whose write the lookup may have read the row
        // ahead of.
        val starredNow = starIntents.since(article.id, starSeqAtSelect)
        // Optimistic: show the intended read/star state immediately; its persist already went out.
        _selectedArticle.value = full.copy(
            is_read = readNow.toDbFlag(),
            is_starred = starredNow?.toDbFlag() ?: full.is_starred,
        )
        // After the article itself, so the reader goes from the header (if one was showing) straight
        // to the full article, and selectedArticleRow never resolves to nothing in between.
        _headerArticleId.value = null
        _pendingSelectedRow.value = null
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
        displayedRows(searchActive.value, articles.value, searchResults.value)

    /**
     * The rows the article list shows: the search results' articles while [searchActive], the
     * filter's own [rows] otherwise. The one resolution [currentArticles], [pagerArticles] and
     * [canHideRead] share, so they can never disagree.
     */
    private fun displayedRows(
        searchActive: Boolean,
        rows: List<ArticleListRow>,
        results: List<ArticleSearchResult>,
    ): List<ArticleListRow> = if (searchActive) results.map { it.article } else rows

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
        // Pinned with its confirmed value either way, never removed for "unread": until the write above
        // lands the raw query still says "read", and an unread-only list that had lost the pin would
        // drop the row for that window — the reader's pager then collapses to the selected article and
        // rebuilds every page it holds, so the open article visibly reloads. Same reasoning as setStarred.
        _pinnedReadStates.update { it + (article.id to article.copy(is_read = read.toDbFlag())) }
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_read = read.toDbFlag()) }
        }
        _pendingSelectedRow.update { if (it?.id == article.id) it.copy(is_read = read.toDbFlag()) else it }
    }

    /**
     * Toggles the read state of the selected article.
     *
     * The one implementation behind every route to "mark as read / unread" for the displayed article —
     * the Article menu's toggle item and the reader toolbar's read/unread button on both UIs — so the
     * effect is always the opposite of the state the toolbar is showing (see [selectedArticleShownUnread]).
     * That includes the moment a newly selected article's body is still loading: the target is the
     * article the selection cursor points at, not the previous one [selectedArticle] still holds, and the
     * state it is shown in includes an intent already made on it (see [readIntents]), so the tap
     * never lands on a different article than the one the button is drawn for and a second tap undoes the first.
     */
    fun toggleReadSelected() {
        val target = cursorArticle() ?: return
        setRead(target, read = isSelectedArticleShownUnread())
    }

    /**
     * The article the selection cursor points at, as the list currently shows it — or, once it has
     * left the list, as [selectedArticleRow] has it. The target of every "…selected" toggle, so none
     * can land on the previous article while a newly selected one's body is still loading.
     */
    private fun cursorArticle(): ArticleListRow? {
        val cursor = selectionCursorId ?: return null
        return currentArticles().firstOrNull { it.id == cursor } ?: selectedArticleRow.value?.takeIf { it.id == cursor }
    }

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
        // Only the Starred filter's query (and starred mode's narrowing) excludes an unstarred
        // article, so only pin there — switching into Starred later already starts from a fresh,
        // un-pinned query (selectFilter), and entering starred mode drops old pins (setMode).
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
        if (_filter.value == ArticleFilter.Starred || currentMode() == ArticleListMode.Starred) {
            _pinnedUnstarredArticles.update { it + (article.id to article.copy(is_starred = starred.toDbFlag())) }
        } else if (starred) {
            _pinnedUnstarredArticles.update { it - article.id }
        }
        // A row the raw query no longer returns is drawn from its read pin (the `articles` combine's
        // extra rows), so that pin carries the star too. A fresh instance, as for any user write.
        _pinnedReadStates.update { pins ->
            pins[article.id]?.let { pins + (article.id to it.copy(is_starred = starred.toDbFlag())) } ?: pins
        }
        starIntents.record(article.id, starred)
        if (_selectedArticle.value?.id == article.id) {
            _selectedArticle.update { it?.copy(is_starred = starred.toDbFlag()) }
        }
        _pendingSelectedRow.update { if (it?.id == article.id) it.copy(is_starred = starred.toDbFlag()) else it }
    }

    /**
     * Toggles the starred state of the selected article — every route to it (the reader toolbar, the
     * Article menu) on both UIs. Like [toggleReadSelected], the target is the article the cursor points
     * at, even while its body is still loading, and the result is the opposite of the star the toolbar
     * shows for it ([selectedArticleRow], which carries every optimistic star write), so a second tap
     * undoes the first.
     */
    fun toggleStarSelected() {
        val target = cursorArticle() ?: return
        val shownStarred = selectedArticleRow.value?.takeIf { it.id == target.id }?.is_starred ?: target.is_starred
        setStarred(target, starred = shownStarred == 0L)
    }

    /**
     * Marks unread articles in the current filter as read.
     *
     * Visible articles are retained optimistically until the updated data is refreshed. Under search,
     * [ArticleListMode.Starred] or the Starred filter, only the rows on screen are marked: the first two
     * narrow the filter to an explicit subset, and the Starred filter can still show an article just
     * unstarred (its pin) that no query over starred articles would reach.
     *
     * Acts once the rows on screen belong to the current filter and mode ([ListScope]): right after
     * a switch they can still be the previous list's, and marking (or pinning) those would reach
     * articles the current list does not show. If the selection moves on again before the list
     * catches up, nothing is marked — the list the action was made for is gone.
     */
    fun markAllRead() {
        val requested = currentScope()
        if (displayedScope() == requested) return markShownRead()
        viewModelScope.launch {
            // From the source flows, as currentScope() is: the derived mode/narrowing lag behind them.
            combine(
                displayedScopes, _filter, _unreadOnly, _unreadOnlyStarred, _starredMode,
            ) { shown, filter, unread, unreadStarred, starred ->
                shown == ListScope(filter, narrowingFor(articleListModeFor(filter, unread, unreadStarred, starred)))
            }.first { it }
            if (currentScope() == requested) markShownRead()
        }
    }

    /** [markAllRead] over the rows on screen, once they belong to the current [ListScope]. */
    private fun markShownRead() {
        val filter = _filter.value
        val active = searchActive.value
        // Search results and starred mode both show a subset of the filter, and the Starred filter
        // can show unstarred pins its own query no longer reaches, so all three mark exactly the rows
        // shown rather than the whole filter. Only search has a snapshot to re-run afterwards.
        val scoped = active || currentMode() == ArticleListMode.Starred || filter == ArticleFilter.Starred
        // Everything the optimistic update below needs is read here, *before* the write is
        // dispatched — not after. dbWriteDispatcher is Dispatchers.Unconfined in tests (and could
        // race a real write landing before this reads it in production), so reading `articles`
        // after the dispatch below could already observe the post-write, all-read snapshot and see
        // no unread articles left to pin at all.
        val selected = _selectedArticle.value
        val visibleUnread = currentArticles().filter { it.is_read == 0L }
        // When scoped, exactly the unread rows the update below pins: taken from the pin-resolved
        // results rather than the raw search snapshot, which can lag an optimistic "mark as unread"
        // (its re-run is debounced) and would leave such a row pinned read but never written. Pins
        // can outrun the DB, so the write itself skips any id whose row is already read or tombstoned
        // (updateReadStatusByIds).
        val idsToMark = if (scoped) {
            visibleUnread.map { it.id }.distinct()
        } else {
            emptyList()
        }
        if (scoped && idsToMark.isEmpty()) {
            // Nothing on screen needs marking read; skip both the DB write and the dependent search
            // refresh.
            return
        }
        // Dispatched before the optimistic state below, not after — see reconcilePinnedArticlesAndSelection's
        // own KDoc for why this order is load-bearing: it is what guarantees a concurrent reconcile
        // pass can never observe (and revert) this optimistic pin/selection using DB flags from
        // before this write has landed.
        viewModelScope.launch(dbWriteDispatcher) {
            if (scoped) {
                articleRepository.markArticlesAsRead(idsToMark)
                // Re-run search only after the write lands so the freshly-read state shows up.
                if (active) _searchRefreshTrigger.update { it + 1 }
            } else {
                articleRepository.markAllAsRead(filter)
            }
        }
        // An explicit "read" for everything this writes, so a selection still loading its body cannot
        // apply an earlier "mark as unread" over it (see SelectionReadIntents). When scoped that is
        // exactly idsToMark; otherwise markAllAsRead marks the whole filter, which holds every
        // visible row as well as the selection (including one still loading).
        val marked = if (scoped) idsToMark else visibleUnread.map { it.id } + listOfNotNull(selected?.id, selectionCursorId)
        marked.forEach { readIntents.record(it, read = true) }
        // Optimistic update: pin every currently-visible unread article in its read state so the list
        // doesn't collapse the instant the user presses "mark all read" under unread-only.
        // All pins are cleared on filter switch / refresh, so articles disappear naturally later.
        val nowRead = clock.nowMillis()
        val updatedSelected = selected
            ?.takeIf { !scoped || it.id in idsToMark }
            ?.copy(is_read = 1L, read_at = nowRead)
        val newPins = visibleUnread.associate { it.id to it.copy(is_read = 1L) } +
            listOfNotNull(updatedSelected).associate { it.id to it.toListRow() }
        if (updatedSelected != null) _selectedArticle.value = updatedSelected
        // A selection still loading its body is marked too: markAllAsRead covers it (it is in the
        // filter), and when scoped it is if it is among idsToMark.
        _pendingSelectedRow.update { row -> if (row != null && (!scoped || row.id in idsToMark)) row.copy(is_read = 1L) else row }
        // Applied in one update so it composes with a concurrent reconcile pass rather than writing
        // back a snapshot taken before that pass dropped a pin. The new pins stay fresh instances:
        // they are new writes, which that pass's identity check must not judge.
        _pinnedReadStates.update { it + newPins }
    }

    /**
     * Enables or disables filtering the article list to unread articles — [setMode] with
     * [ArticleListMode.Unread] or [ArticleListMode.All].
     *
     * @param value Whether to show only unread articles.
     */
    fun setUnreadOnly(value: Boolean) = setMode(if (value) ArticleListMode.Unread else ArticleListMode.All)

    /**
     * Switches the article list's [mode]. Every route (the toolbar selector, the View menu and its
     * ⌘/Ctrl+1–3 accelerators) calls this.
     *
     * [ArticleListMode.Starred] is ignored while [starredModeEnabled] is false. Choosing it drops the
     * unstarred pins left from earlier, so the list starts from exactly the starred rows. Under the
     * Starred filter, All/Unread choose among its starred articles and leave starred mode as it was:
     * Starred stays checked there, so leaving the filter returns to the mode it was entered with.
     *
     * @param target The mode to show.
     */
    fun setMode(target: ArticleListMode) {
        if (target == currentMode()) return
        if (target == ArticleListMode.Starred) {
            if (!starredModeAvailable(_filter.value)) return
            _pinnedUnstarredArticles.value = emptyMap()
            _starredMode.value = true
            return
        }
        val value = target == ArticleListMode.Unread
        if (value) {
            retrimPinnedReadStates()
        }
        when {
            _filter.value == ArticleFilter.Starred -> {
                if (_unreadOnlyStarred.value != value) {
                    _unreadOnlyStarred.value = value
                    settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnlyStarred = value) }
                }
            }
            else -> {
                _starredMode.value = false
                if (_unreadOnly.value != value) {
                    _unreadOnly.value = value
                    settingsRepository.mutateLocalSettings { it.copy(lastUnreadOnly = value) }
                }
            }
        }
    }

    /**
     * Re-trims the read-state pins: keeps every unread pin and the selection's own read pin, drops
     * the rest.
     *
     * The selection's pin is decided against the map the [MutableStateFlow.update] applies to, not
     * the one read before it, so the re-trim composes with a concurrent reconcile pass: a pin that
     * pass dropped since stays dropped, and an existing pin is kept as it is — never re-created from
     * the selection, which that pass refreshes only after the pin, and whose copy its identity check
     * would also mistake for a fresh user write (see [retrimmedSelectionPin]).
     *
     * @param verifyAlive Whether to check the selection is still alive — a DB read, run afterwards on
     *   [dbWriteDispatcher] rather than on the caller's thread, which then drops the selection's pin
     *   if it was tombstoned. A tombstone a sync merge wrote does not necessarily tick the
     *   reconcile, so the refresh/sync edges and the user's own re-trims check; mark-all-read under
     *   Starred, which never did, skips it.
     */
    private fun retrimPinnedReadStates(verifyAlive: Boolean = true) {
        val before = _pinnedReadStates.value
        val candidate = selectionReadPinCandidate(before)
        var keptPin: ArticleListRow? = null
        _pinnedReadStates.update { current ->
            val kept = current.unreadPins()
            if (candidate == null) return@update kept
            val pin = retrimmedSelectionPin(before[candidate.id], current[candidate.id], candidate)
            keptPin = pin
            if (pin == null) kept else kept + (candidate.id to pin)
        }
        val pin = keptPin
        if (!verifyAlive || candidate == null || pin == null) return
        // A selected row tombstoned by a sync merge must not stay read-pinned: the `articles` merge
        // step re-adds any read-pinned id missing from the repository result, which would put
        // deleted content back into the visible list. Checked after the re-trim rather than before
        // it, so the caller's (main) thread never waits on the DB — a write lock held by a merge
        // would stall it for as long as the merge runs. Read through dbWriteDispatcher, behind any
        // write already queued there. Only the very pin kept above is dropped: one a later
        // selection or reconcile pass replaced since belongs to that newer state.
        viewModelScope.launch {
            val alive = withContext(dbWriteDispatcher) { candidate.id in articleRepository.aliveArticleFlags(listOf(candidate.id)) }
            if (alive) return@launch
            _pinnedReadStates.update { current -> if (current[candidate.id] === pin) current - candidate.id else current }
        }
    }

    /**
     * The article the user has selected: the cursor while a newer selection is still loading its body
     * (when [_selectedArticle] is still the one being replaced), the loaded selection otherwise. The one
     * definition the re-trim and the reconcile both use, so they never protect different articles.
     * Readable off the main thread, since [selectionCursorId] is backed by a StateFlow.
     */
    private fun currentSelectionId(): String? = selectionCursorId ?: _selectedArticle.value?.id

    /**
     * The read pin a re-trim keeps for the selection, before its alive check.
     *
     * @param pins The read-state pins the re-trim starts from.
     * @return The selected article's row — or, while a newer selection is still loading, that
     *   selection's own pin or listed row — if it is read; null otherwise.
     */
    private fun selectionReadPinCandidate(pins: Map<String, ArticleListRow>): ArticleListRow? {
        val selected = _selectedArticle.value
        val cursor = currentSelectionId()
        if (cursor != null && cursor != selected?.id) {
            // A newer selection is still loading its body, so [_selectedArticle] is the article
            // being replaced. That selection is the one to keep: its own pin (selectArticle pins a
            // row that was unread) or, for a row that was already read, its row as listed — or it
            // disappears from an unread-only list for good, since its hydration never pins.
            return pins[cursor] ?: currentArticles().firstOrNull { it.id == cursor && it.is_read == 1L }
        }
        if (selected == null || selected.is_read != 1L) return null
        return selected.toListRow()
    }

    /**
     * Revalidates every optimistic pin (and the current selection's cached flags) against the DB's
     * current state, so a pin can never hide an external change forever.
     *
     * [_pinnedReadStates]/[_pinnedUnstarredArticles] intentionally show a value that outruns the
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
        val readStateSnapshot = _pinnedReadStates.value
        val unstarredSnapshot = _pinnedUnstarredArticles.value
        val selectedSnapshot = _selectedArticle.value
        if (readStateSnapshot.isEmpty() && unstarredSnapshot.isEmpty() && selectedSnapshot == null) return
        // Resolved with ONE query covering all three, outside the update lambdas below. Per-pin
        // getById was both an N+1 (each one a full row on its own connection) and inside a CAS retry
        // loop that can re-run it; "mark all read" sizes the read map to the whole visible list, and
        // this runs on every articles write.
        //
        // Read via dbWriteDispatcher, not the `dispatcher` this function itself runs on (see the
        // articleChangeSignal collector in init, below) — deliberately, and this is the ordering
        // argument every optimistic-update call site above points back to. _pinnedReadStates/
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
        val ids = readStateSnapshot.keys + unstarredSnapshot.keys + listOfNotNull(selectedSnapshot?.id)
        val flags = withContext(dbWriteDispatcher) { articleRepository.aliveArticleFlags(ids) }
        if (readStateSnapshot.isNotEmpty()) {
            _pinnedReadStates.update { pinned ->
                // A pin added or replaced since the snapshot is kept: `flags` was read before the write
                // that justified it was enqueued, so it has no verdict on it (identity, not equality — a
                // re-pin with the same value is still a write `flags` knows nothing about). Otherwise a
                // pin is dropped once the article's current is_read no longer matches the pinned value
                // (an external "mark unread"/"mark read", or a soft-delete tombstone), the same rule as
                // the starred pin below — except the selection's own pin, which while its article is
                // alive is refreshed to the current value instead: under unread-only a dropped unread
                // pin read externally would take the open article out of the list (and the pager).
                val selectedId = currentSelectionId()
                buildMap {
                    pinned.forEach { (id, pin) ->
                        val old = readStateSnapshot[id]
                        val current = flags[id]
                        when {
                            pinReplacedSince(old, pin) || current?.isRead == old?.is_read -> put(id, pin)
                            id == selectedId && current != null -> put(id, pin.copy(is_read = current.isRead))
                        }
                    }
                }
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
     * Deliberately does not touch [_pinnedReadStates] or [browsingEpoch] — unlike a filter
     * switch, a query change doesn't start a fresh browsing context, it narrows the *same* one.
     * `_pinnedReadStates` is shared between the underlying filter's own list and search results
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
        ArticleFilter.All, ArticleFilter.Starred, is ArticleFilter.Tag -> null
    }

    /**
     * The feed a newly subscribed feed should be inserted directly after, derived from the feed
     * list's current selection: the selected feed itself, whether it's filed in a folder or
     * unfiled. Any other selection (folder/all/starred/tag) yields `null`, which appends
     * the new feed to the end of its target group instead (unchanged from before this feature).
     */
    private fun afterFeedIdForNewFeed(): String? = when (val f = _filter.value) {
        is ArticleFilter.Feed -> f.feedId
        ArticleFilter.All, ArticleFilter.Starred, is ArticleFilter.Folder, is ArticleFilter.Tag -> null
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
        FeedListRowSelection.All, FeedListRowSelection.Starred,
        is FeedListRowSelection.FeedInFolderGroup, is FeedListRowSelection.Folder,
        -> null
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

    /**
     * The feeds whose row shows the refreshing indicator: those being refreshed by any route — see
     * [FeedRepository.refreshingFeedIds].
     */
    val refreshingFeedIds: StateFlow<Set<String>> =
        feedRepository.refreshingFeedIds.stateIn(viewModelScope, started, emptySet())

    /**
     * The collapsed folders whose row shows the refreshing indicator, since their feeds' own rows
     * are hidden — see [collapsedFoldersRefreshing].
     */
    val refreshingFolderIds: StateFlow<Set<String>> =
        combine(feeds, refreshingFeedIds, collapsedFolderIds, ::collapsedFoldersRefreshing)
            .distinctUntilChanged()
            .stateIn(viewModelScope, started, emptySet())

    // Refresh / sync actions live in their own class; this ViewModel only delegates to it.
    private val refreshController = HomeRefreshController(
        scope = viewModelScope,
        dispatcher = dispatcher,
        runner = refreshCycleRunner,
        activityCenter = activityCenter,
        currentFilter = { _filter.value },
        retrimPins = ::retrimPinnedReadStates,
    )

    /**
     * Refreshes the feed [feedId] names, from its current row. Takes the id rather than a row so no
     * caller can hand over a copy whose conditional-request fields lag behind (as
     * [structuralFeeds]' rows do). See [HomeRefreshController.refreshFeed].
     */
    fun refreshFeed(feedId: String) = refreshController.refreshFeed(feedId)

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

    /**
     * What the refreshes the user started (pull, Refresh All, a feed's Refresh) are doing — the iOS
     * toolbar status. See [ManualRefreshStatus].
     */
    val manualRefreshStatus: StateFlow<ManualRefreshStatus> get() = refreshController.manualRefreshStatus

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
            manualSync.runs.collect { retrimPinnedReadStates() }
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
        fallBackFromTagRow(id)
        expansion.forgetTag(id)
    }

    /** Moves a selection on a feed row nested under [tagId] to that feed's canonical row. */
    private fun fallBackFromTagRow(tagId: String) {
        val instance = _selectedRowInstance.value
        if (instance is FeedListRowSelection.FeedInTag && instance.tagId == tagId) {
            _selectedRowInstance.value = FeedListRowSelection.FeedInFolderGroup(instance.feedId)
        }
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


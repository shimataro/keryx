package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.encode
import works.merc.keryx.app.core.searchTerms
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_back
import works.merc.keryx.app.resources.home_all_feeds
import works.merc.keryx.app.resources.home_mark_all_read
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_no_articles
import works.merc.keryx.app.resources.home_no_feeds
import works.merc.keryx.app.resources.home_open_feed_list
import works.merc.keryx.app.resources.home_search_clear
import works.merc.keryx.app.resources.home_search_no_results
import works.merc.keryx.app.resources.home_search_placeholder
import works.merc.keryx.app.resources.home_search_too_short
import works.merc.keryx.app.resources.home_search_try_all_feeds
import works.merc.keryx.app.resources.home_sort_disabled_search
import works.merc.keryx.app.resources.home_search
import works.merc.keryx.app.resources.home_sort_newest
import works.merc.keryx.app.resources.home_sort_oldest
import works.merc.keryx.app.resources.home_starred
import works.merc.keryx.app.resources.home_unread_only
import works.merc.keryx.app.ui.common.FlatButton
import works.merc.keryx.app.ui.common.KeryxExpandedSearchBar
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxPaneTopBar
import works.merc.keryx.app.ui.common.ToggleChip
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Displays the article list for the current filter, narrowed by search when active.
 *
 * Search is orthogonal to [ArticleFilter] (see `HomeViewModel`'s own "Search" section): the query
 * narrows whichever filter is already selected rather than displacing it, so this pane's content
 * is always exactly one of two things — the current filter's own list, or that filter's search
 * results — decided purely by [HomeViewModel.searchActive]. There is no separate "Search pane";
 * both are rendered by the same [ArticleListPaneContent] call below, just fed different `articles`/
 * `listState`, which is what lets each keep its own independent scroll position across a query
 * being typed and cleared again — no snapshot/restore machinery needed.
 *
 * @param vm The view model providing article, feed, selection, and filter state.
 * @param focused Whether the pane currently has focus.
 * @param onActivated Called when the pane becomes active.
 * @param modifier Modifier applied to the pane.
 * @param notifVm Optional view model providing notifications for the toolbar.
 * @param onSelectionAdvance Called after an article is selected, in addition to [onActivated] —
 *   see `HomeScreen`'s pane-layout wiring. No-op at both [PaneLayout.Triple] and [PaneLayout.Dual],
 *   where every pane `visiblePanes` returns is already on screen and there is nowhere to advance
 *   to (`HomeScreen` itself gates this to fire only at [PaneLayout.Single], the one layout where
 *   selecting an article actually changes which panes are visible).
 * @param onOpenDrawer Renders the pane's own leading hamburger-button-and-title row when non-null
 *   — this pane is being shown at a narrow [PaneLayout], where the feed list is a modal navigation
 *   drawer (see `HomePaneLayout.kt`'s `feedListIsDrawer`) rather than an on-screen pane, and this is
 *   the button that opens it. `null` (the default) omits that row entirely rather than rendering it
 *   disabled, since a [PaneLayout.Triple] pane has no drawer to open at all — the row's presence
 *   therefore depends only on the layout, never on the navigation stack's current depth. Always
 *   enabled when non-null: unlike a "back" action, opening the drawer is never contextually unavailable.
 * @param onTextInputFocusChange Reports [HomeTextInput.SearchField] while this pane's own expanded
 *   search field (present at a narrow layout — [onExitSearch] non-null — while the bar is open)
 *   holds focus, `null` otherwise — same contract as `FeedListPane`'s own parameter of that name, so
 *   `HomeScreen` can suppress bare-key shortcuts while the user is typing regardless of which pane
 *   the field currently lives in.
 * @param onSearchClick Adds a search entry point to this pane's own top bar when non-null — the
 *   search icon `ui-guidelines`' "Pane structure & tonal roles" section places at the head of this
 *   pane's header row. Omitted once the expanded search bar is already open: there is nowhere
 *   further to advance to.
 * @param onExitSearch The narrow layout's own back arrow inside the expanded search bar — closes
 *   the bar rather than opening the feed-list drawer (a distinct action from [onOpenDrawer] — see
 *   `HomePaneLayout.kt`'s `homeBackAction`/`HomeBackAction.CloseSearchBar`). `null` at
 *   [PaneLayout.Triple], same boundary as [onOpenDrawer] — the field there lives permanently in
 *   `FeedListPane`'s sidebar instead, with no bar of its own to close.
 * @param onAddFeedClick Invoked from the empty state's "Add feed" button, shown instead of the
 *   usual "no articles" message when there are no feeds at all — see [ArticleListPaneContent]'s own
 *   KDoc. `null` hides the button (leaving the message on its own); every real caller supplies it.
 */
@Composable
fun ArticleListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    notifVm: NotificationCenterViewModel? = null,
    onSelectionAdvance: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    onExitSearch: (() -> Unit)? = null,
    onAddFeedClick: (() -> Unit)? = null,
    onTextInputFocusChange: (HomeTextInput?) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    returnRipplePulse: Int = 0,
) {
    val filter by vm.filter.collectAsState()
    val feeds by vm.feeds.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val title = onOpenDrawer?.let {
        articleListTitle(
            filter = filter,
            feeds = feeds,
            folders = folders,
            tags = tags,
            allLabel = stringResource(Res.string.home_all_feeds),
            starredLabel = stringResource(Res.string.home_starred),
        )
    }
    val feedTitles = feeds.associate { it.id to it.displayTitle() }
    val feedFavicons = feeds.associate { it.id to it.favicon_url }

    val query by vm.searchQuery.collectAsState()
    val searchBarVisible by vm.searchBarVisible.collectAsState()
    val searchActive by vm.searchActive.collectAsState()
    val selected by vm.selectedArticle.collectAsState()
    val unreadOnly by vm.unreadOnly.collectAsState()
    val newestFirst by vm.newestFirst.collectAsState()

    // Two independent LazyListStates, one per mode, both declared unconditionally so switching
    // between them (typing/clearing a query) never disposes either one's scroll position — see
    // this file's own module KDoc. Reset to the top when the user switches feed/tag/folder/scope,
    // so a new list never opens scrolled to the previous one's offset; only fires on an actual
    // filter change (not the first composition), so a restored last-selected article's
    // scroll-into-view isn't clobbered.
    val baseListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    var lastFilter by rememberSaveable { mutableStateOf(filter.encode()) }
    LaunchedEffect(filter) {
        val encoded = filter.encode()
        if (encoded != lastFilter) {
            baseListState.scrollToItem(0)
            searchListState.scrollToItem(0)
            lastFilter = encoded
        }
    }

    // Whether the expanded search bar (a narrow-layout-only header replacing the hamburger/title
    // row) is actually on screen right now — onExitSearch is non-null at a narrow layout
    // regardless of whether the bar happens to be open.
    val barShown = onExitSearch != null && searchBarVisible

    // Consumes HomeViewModel's pendingSearchFocus latch — only once the bar is actually rendered
    // (barShown), so the FocusRequester below is guaranteed to be attached to something on screen
    // when requestFocus() is called (see HomeViewModel.requestSearchFocus's KDoc on why this is a
    // latch rather than a one-shot event in the first place).
    val searchFocusRequester = remember { FocusRequester() }
    val pendingSearchFocus by vm.pendingSearchFocus.collectAsState()
    LaunchedEffect(pendingSearchFocus, barShown) {
        if (!barShown || !pendingSearchFocus) return@LaunchedEffect
        searchFocusRequester.requestFocus()
        vm.consumeSearchFocusRequest()
    }
    // The field reports its focus as gone whenever the bar hides (searchBarVisible flips false, so
    // KeryxExpandedSearchBar itself leaves composition) or this whole pane unmounts — a
    // LaunchedEffect merely being cancelled does not report false on its own, and a stuck `true`
    // would permanently suppress bare-key shortcuts (see HomeScreen's own textInputFocused KDoc).
    DisposableEffect(barShown) {
        onDispose { onTextInputFocusChange(null) }
    }

    val header: (@Composable () -> Unit)? = if (barShown) {
        // barShown already established onExitSearch != null; captured here as a local val so the
        // lambda below doesn't need a redundant safe call on it.
        val exitSearch = onExitSearch
        {
            val keyboardController = LocalSoftwareKeyboardController.current
            KeryxExpandedSearchBar(
                query = query,
                onQueryChange = { vm.setSearchQuery(it) },
                placeholder = stringResource(Res.string.home_search_placeholder),
                onNavigateUp = exitSearch,
                navigateUpContentDescription = stringResource(Res.string.common_back),
                clearContentDescription = stringResource(Res.string.home_search_clear),
                onSearchAction = { keyboardController?.hide() },
                modifier = Modifier.padding(top = 4.dp),
                fieldModifier = Modifier
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onTextInputFocusChange(if (it.isFocused) HomeTextInput.SearchField else null) },
            )
        }
    } else {
        null
    }

    // The two branches below differ only in these values — see ArticleListPaneContent's own
    // KDoc for exactly which. Collecting each mode's own state only inside its branch (rather
    // than both unconditionally) is deliberate: it avoids subscribing to search results/the
    // debounce state while search isn't even active, and vice versa.
    val articles: List<ArticleListRow>
    val listState: LazyListState
    val branchReturnRipplePulse: Int
    val branchOnAddFeedClick: (() -> Unit)?
    val hasNoFeeds: Boolean
    val sortEnabled: Boolean
    val titleMarkedById: Map<String, String>?
    val emptyContent: (@Composable () -> Unit)?

    if (!searchActive) {
        val baseArticles by vm.articles.collectAsState()
        articles = baseArticles
        listState = baseListState
        branchReturnRipplePulse = returnRipplePulse
        branchOnAddFeedClick = onAddFeedClick
        hasNoFeeds = feeds.isEmpty()
        sortEnabled = true
        titleMarkedById = null
        emptyContent = null
    } else {
        val results by vm.searchResults.collectAsState()
        val searching by vm.searching.collectAsState()
        // A query has usable terms once at least one word is 2+ characters (searched via the
        // trigram index at 3+, or a LIKE fallback at exactly 2 — see FtsSearch). A lone 1-character
        // word, or "a b" where every word is too short, count as no terms.
        val hasValidTerms = searchTerms(query).isNotEmpty()

        // Keep the keyboard-selected result in view (mirrors ArticleListPaneContent's own
        // scroll-to-selection effect for the base list).
        LaunchedEffect(selected?.id, results) {
            val index = results.indexOfFirst { it.article.id == selected?.id }
            if (index !in results.indices) return@LaunchedEffect
            searchListState.scrollToIndexIfNeeded(index)
        }

        articles = results.map { it.article }
        listState = searchListState
        branchReturnRipplePulse = 0
        branchOnAddFeedClick = null
        hasNoFeeds = false
        sortEnabled = false
        titleMarkedById = remember(results) { results.associate { it.article.id to it.titleMarked } }
        emptyContent = when {
            !hasValidTerms -> {
                { CenteredHint(stringResource(Res.string.home_search_too_short)) }
            }
            // Hold (blank) while the debounced search for the current query/filter is still in
            // flight, instead of flashing "no results" between keystrokes (or right after switching
            // filters) before the real results land. Any previous non-empty results keep showing
            // (the `else` branch below) until the new ones land.
            results.isEmpty() && searching -> {
                {}
            }
            results.isEmpty() -> {
                { NoSearchResultsHint(scopedBelowAllFeeds = filter != ArticleFilter.All) }
            }
            else -> null
        }
    }

    ArticleListPaneContent(
        articles = articles,
        feedTitles = feedTitles,
        feedFavicons = feedFavicons,
        selectedId = selected?.id,
        unreadOnly = unreadOnly,
        // Deliberately the real sort direction even while search disables the button — search
        // order is always FTS5 relevance rank, but sortDirectionIcon's own KDoc says the button
        // still reflects the current direction while disabled, it just can't be toggled.
        newestFirst = newestFirst,
        focused = focused,
        onToggleUnreadOnly = { vm.setUnreadOnly(!unreadOnly) },
        onToggleSort = { vm.toggleSort() },
        onMarkAllRead = { vm.markAllRead() },
        onSelectArticle = { vm.selectArticle(it); onActivated(); onSelectionAdvance() },
        onToggleRead = { vm.toggleRead(it) },
        onToggleStar = { vm.toggleStar(it) },
        modifier = modifier,
        listState = listState,
        returnRipplePulse = branchReturnRipplePulse,
        onActivated = onActivated,
        notifVm = notifVm,
        onOpenDrawer = if (barShown) null else onOpenDrawer,
        title = if (barShown) null else title,
        onSearchClick = if (barShown) null else onSearchClick,
        hasNoFeeds = hasNoFeeds,
        onAddFeedClick = branchOnAddFeedClick,
        sortEnabled = sortEnabled,
        titleMarkedById = titleMarkedById,
        emptyContent = emptyContent,
        header = header,
    )
}

/**
 * The "no matches" hint shown when a search under [scopedBelowAllFeeds] came back empty — with a
 * secondary line pointing at "All Feeds" only when the search was actually narrowed to something
 * less than that, since switching to All wouldn't change anything otherwise. The scope can be a
 * single feed, a folder, a tag, or Starred — anything but All itself.
 */
@Composable
private fun NoSearchResultsHint(scopedBelowAllFeeds: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.home_search_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scopedBelowAllFeeds) {
                Text(
                    stringResource(Res.string.home_search_try_all_feeds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Remembers a "copy URL to clipboard" action, shared by [ArticleListPaneContent]'s article rows
 * (both the current filter's own list and search results), and by [FeedListPane]'s feed rows.
 */
@Composable
internal fun rememberCopyUrlAction(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { url: String -> scope.launch { clipboard.setClipEntry(ClipboardEntries.ofText(url)) } }
    }
}

/**
 * Picks the sort button's icon for the current direction. Each direction gets its own asset rather
 * than one asset transformed at the call site: this button used to flip a single glyph vertically,
 * which reads as a direction only on an icon set whose sort glyph carries an arrow — Material
 * Symbols' does not, so the flip was invisible on Android. See `KeryxIcons`' own KDoc.
 *
 * Always reflects the current sort direction, even while disabled during a search (results stay
 * pinned to FTS5 relevance rank) — [ArticleListTopBar] conveys "disabled" through
 * `TooltipIconButton`'s own dimmed styling, not through swapping the glyph itself.
 */
internal fun sortDirectionIcon(newestFirst: Boolean): DrawableResource =
    if (newestFirst) KeryxIcons.SortDescending else KeryxIcons.SortAscending

/**
 * The top bar shared by every mode [ArticleListPaneContent] renders (the current filter's own list,
 * or its search results): unread-only toggle, notifications bell, sort, mark-all-read. When
 * [sortEnabled] is false (search is active, where the result order is fixed — FTS5 relevance rank,
 * or recency when every term is too short to be ranked, see FtsSearch), the sort button is disabled
 * and its tooltip explains why instead of showing the usual "sort by ...".
 *
 * When [onOpenDrawer] is non-null (this pane is shown at a narrow [PaneLayout], where the feed
 * list is a modal navigation drawer rather than an on-screen pane — see `ArticleListPane`'s KDoc),
 * a leading hamburger-button-and-[title] row is added above the controls row rather than folded
 * into it: the controls row is unchanged from [PaneLayout.Triple]/[PaneLayout.Dual] so the
 * unread-only toggle stays reachable at every width instead of being dropped for space. That row
 * is laid out for the whole time the pane stays at a narrow layout — unlike a "back" button,
 * opening the drawer is never contextually unavailable, so there is no enabled/disabled state to
 * track and the row's presence depends only on [onOpenDrawer]'s own nullness.
 *
 * [onSearchClick], when non-null, sits in that same leading row's trailing slot — narrow-only,
 * since [PaneLayout.Triple] has no such row at all and keeps its own editable search field in
 * `FeedListPane`'s sidebar instead (see `ArticleListPane`'s own KDoc).
 *
 * @param onSearchClick The entry point into search at a narrow layout (before this, folded into
 *   [onOpenDrawer]'s own leading row rather than the controls row's [ToolbarIconGroup] below —
 *   see this composable's own KDoc above). `null` once the expanded search bar is already open:
 *   there is nowhere further to advance to.
 */
@Composable
internal fun ArticleListTopBar(
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
    onMarkAllRead: () -> Unit,
    sortEnabled: Boolean = true,
    notifVm: NotificationCenterViewModel? = null,
    onOpenDrawer: (() -> Unit)? = null,
    title: String? = null,
    onSearchClick: (() -> Unit)? = null,
) {
    WindowDragArea(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        if (onOpenDrawer != null) {
            val openDrawerLabel = stringResource(Res.string.home_open_feed_list)
            KeryxPaneTopBar(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp),
                title = title.orEmpty(),
                navigationIcon = {
                    TooltipIconButton(tooltip = openDrawerLabel, onClick = onOpenDrawer) {
                        KeryxIcon(KeryxIcons.Menu, contentDescription = openDrawerLabel)
                    }
                },
            ) {
                if (onSearchClick != null) {
                    val searchLabel = stringResource(Res.string.home_search)
                    TooltipIconButton(tooltip = searchLabel, onClick = onSearchClick) {
                        KeryxIcon(KeryxIcons.Search, contentDescription = searchLabel)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleChip(
                label = stringResource(Res.string.home_unread_only),
                checked = unreadOnly,
                onCheckedChange = { onToggleUnreadOnly() },
            )
            Spacer(Modifier.weight(1f))
            ToolbarIconGroup {
                if (notifVm != null) {
                    NotificationsBell(notifVm)
                }
                val sortTooltip = if (sortEnabled) {
                    stringResource(if (newestFirst) Res.string.home_sort_oldest else Res.string.home_sort_newest)
                } else {
                    stringResource(Res.string.home_sort_disabled_search)
                }
                TooltipIconButton(tooltip = sortTooltip, onClick = onToggleSort, enabled = sortEnabled) {
                    KeryxIcon(sortDirectionIcon(newestFirst), contentDescription = sortTooltip)
                }
                val markAllReadTooltip = stringResource(Res.string.home_mark_all_read)
                TooltipIconButton(tooltip = markAllReadTooltip, onClick = onMarkAllRead) {
                    KeryxIcon(KeryxIcons.DoneAll, contentDescription = markAllReadTooltip)
                }
            }
        }
    }
    }
}

/**
 * The [returnRipplePulse] a specific row ([articleId]) should receive: [returnRipplePulse] itself
 * when [articleId] is the currently selected row (the one a return-from-detail flash targets),
 * `0` (no ripple) for every other row.
 */
internal fun ripplePulseFor(articleId: String, selectedId: String?, returnRipplePulse: Int): Int =
    if (articleId == selectedId) returnRipplePulse else 0

/**
 * Renders the article list with sorting, unread filtering, selection, and article actions.
 *
 * Used for both of [ArticleListPane]'s two modes — the current filter's own list, and its search
 * results — never both in the same composition. Which mode is which is entirely up to the caller's
 * choice of parameters; this composable itself has no notion of search.
 *
 * The two callers differ exactly on these parameters:
 * - **Search results** supply a separate [listState], set [sortEnabled] = `false`, pass
 *   [titleMarkedById] (FTS highlight markup), and provide a non-null [emptyContent] for the
 *   "no matching articles" / "query too short" hints.
 * - **Current filter list** passes [returnRipplePulse] (for the back-from-article-detail flash),
 *   sets [hasNoFeeds] (to swap the empty-state to the "Add feed" prompt), and leaves
 *   [titleMarkedById], [emptyContent], and [sortEnabled] at their defaults.
 *
 * @param articles The article rows to display.
 * @param feedTitles Display titles keyed by feed identifier.
 * @param feedFavicons Favicon URLs keyed by feed identifier.
 * @param selectedId The identifier of the selected article, if any.
 * @param unreadOnly Whether to show only unread articles.
 * @param newestFirst Whether to sort articles from newest to oldest.
 * @param focused Whether the list has focus.
 * @param sortEnabled Whether the sort button in [ArticleListTopBar] can be toggled — `false` while
 *   search is active, where the result order is fixed to FTS5 relevance rank.
 * @param titleMarkedById Search-highlight markup (see `FtsSearch`/`markedToAnnotatedString`) keyed
 *   by article id, applied as each row's title override instead of its plain title. `null` (the
 *   default) renders every row's own title unmodified — the current filter's own list has no
 *   search markup to show.
 * @param header Rendered above [ArticleListTopBar] inside this composable's own background/
 *   pane-activation container — [ArticleListPane]'s expanded search bar at a narrow layout while
 *   it's open, `null` everywhere else.
 * @param hasNoFeeds Swaps the empty-state message from `home_no_articles` ("no articles yet, but
 *   you're subscribed to something") to `home_no_feeds` plus an "Add feed" button ([onAddFeedClick])
 *   when there are no feeds at all — otherwise a narrow layout, where the "+" button lives inside
 *   the feed-list drawer, would leave a phone-width user with no visible way to add their first
 *   feed. Only consulted when [emptyContent] is `null`; a non-empty [articles] always wins over both.
 * @param onAddFeedClick Invoked from the "Add feed" button shown when [hasNoFeeds]. `null` (the
 *   default) omits the button, leaving just the message.
 * @param emptyContent Overrides the ordinary [hasNoFeeds]/`home_no_articles` empty-state message
 *   when [articles] is empty — search's own "too short a query"/"no matching articles" hints, which
 *   have nothing to do with whether the user has any feeds at all. `null` (the default) falls back
 *   to that ordinary message.
 */
@Composable
internal fun ArticleListPaneContent(
    articles: List<ArticleListRow>,
    feedTitles: Map<String, String>,
    feedFavicons: Map<String, String?> = emptyMap(),
    selectedId: String?,
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    onToggleSort: () -> Unit,
    newestFirst: Boolean = true,
    onMarkAllRead: () -> Unit,
    onSelectArticle: (ArticleListRow) -> Unit,
    onToggleRead: (ArticleListRow) -> Unit = {},
    onToggleStar: (ArticleListRow) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    focused: Boolean = true,
    onActivated: () -> Unit = {},
    notifVm: NotificationCenterViewModel? = null,
    onOpenDrawer: (() -> Unit)? = null,
    title: String? = null,
    onSearchClick: (() -> Unit)? = null,
    returnRipplePulse: Int = 0,
    hasNoFeeds: Boolean = false,
    onAddFeedClick: (() -> Unit)? = null,
    sortEnabled: Boolean = true,
    titleMarkedById: Map<String, String>? = null,
    header: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(listState, selectedId, articles.isNotEmpty()) {
        val index = articles.indexOfFirst { it.id == selectedId }
        if (index !in articles.indices) return@LaunchedEffect
        // Before the list's first measure pass layoutInfo is still empty, which
        // scrollToIndexIfNeeded reads as "not rendered anywhere" and answers with an animated
        // scroll — clobbering the scroll position NarrowPaneRow just restored, and pulling the
        // selected row to the top of a viewport it was already sitting comfortably inside. Only
        // ever suspends on that first frame; every later run (a genuine selection change, keyboard
        // navigation) sees a measured list and takes exactly the path it always has.
        if (listState.layoutInfo.totalItemsCount == 0) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        }
        listState.scrollToIndexIfNeeded(index)
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxSize()
            .paneActivation(onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        header?.invoke()
        ArticleListTopBar(
            unreadOnly = unreadOnly,
            onToggleUnreadOnly = onToggleUnreadOnly,
            newestFirst = newestFirst,
            onToggleSort = onToggleSort,
            onMarkAllRead = onMarkAllRead,
            sortEnabled = sortEnabled,
            notifVm = notifVm,
            onOpenDrawer = onOpenDrawer,
            title = title,
            onSearchClick = onSearchClick,
        )

        Box(Modifier.fillMaxSize().imePadding()) {
            if (articles.isEmpty()) {
                if (emptyContent != null) {
                    emptyContent()
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (hasNoFeeds) {
                            // A narrow layout's "+" button lives inside the feed-list drawer (closed by
                            // default), so this is the one reachable entry point to add a first feed —
                            // without it a phone-width user with no feeds yet would have no visible way
                            // forward. See ArticleListPaneContent's own KDoc.
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(Res.string.home_no_feeds), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (onAddFeedClick != null) {
                                    FlatButton(onClick = onAddFeedClick) { Text(stringResource(Res.string.home_add_feed)) }
                                }
                            }
                        } else {
                            Text(stringResource(Res.string.home_no_articles), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                val rowMetrics = rememberArticleRowMetrics()
                val rowStrings = rememberArticleRowStrings()
                val copyUrl = rememberCopyUrlAction()
                // contentPadding's bottom clears the navigation bar on Android's edge-to-edge
                // layout (see HomeScreen's Scaffold); zero on desktop (WindowInsets.safeDrawing).
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
                ) {
                    items(articles, key = { it.id }) { article ->
                        ArticleRow(
                            article = article,
                            feedTitle = feedTitles[article.feed_id].orEmpty(),
                            feedFavicon = feedFavicons[article.feed_id],
                            selected = article.id == selectedId,
                            focused = focused,
                            rowHeight = rowMetrics.rowHeight,
                            faviconSize = rowMetrics.faviconSize,
                            onClick = { onSelectArticle(article) },
                            onToggleRead = { onToggleRead(article) },
                            onToggleStar = { onToggleStar(article) },
                            onCopyUrl = { copyUrl(article.url) },
                            onOpenInBrowser = { BrowserOpener.open(article.url) },
                            titleOverride = titleMarkedById?.get(article.id)?.let {
                                markedToAnnotatedString(it.ifBlank { article.title })
                            },
                            strings = rowStrings,
                            ripplePulse = ripplePulseFor(article.id, selectedId, returnRipplePulse),
                        )
                    }
                }
                VerticalScrollbarIfNeeded(listState)
            }
        }
    }
}

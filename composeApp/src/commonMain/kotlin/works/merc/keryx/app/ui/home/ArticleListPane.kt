package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.ArticleFilter
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
import works.merc.keryx.app.resources.home_no_articles
import works.merc.keryx.app.resources.home_search_clear
import works.merc.keryx.app.resources.home_search_no_results
import works.merc.keryx.app.resources.home_search_placeholder
import works.merc.keryx.app.resources.home_search_too_short
import works.merc.keryx.app.resources.home_sort_disabled_search
import works.merc.keryx.app.resources.home_search
import works.merc.keryx.app.resources.home_sort_newest
import works.merc.keryx.app.resources.home_sort_oldest
import works.merc.keryx.app.resources.home_starred
import works.merc.keryx.app.resources.home_unread_only
import works.merc.keryx.app.ui.common.KeryxExpandedSearchBar
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxPaneTopBar
import works.merc.keryx.app.ui.common.ToggleChip
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Displays the article list for the current filter and routes search filters to the search list.
 *
 * @param vm The view model providing article, feed, selection, and filter state.
 * @param focused Whether the pane currently has focus.
 * @param onActivated Called when the pane becomes active.
 * @param modifier Modifier applied to the pane.
 * @param notifVm Optional view model providing notifications for the toolbar.
 * @param onSelectionAdvance Called after an article is selected, in addition to [onActivated] —
 *   see `HomeScreen`'s pane-layout wiring. No-op at [PaneLayout.Triple], where every pane is
 *   already visible and there is nowhere to advance to.
 * @param onNavigateUp Renders the pane's own leading back-button-and-title row when non-null — this
 *   pane is being shown alone or paired at a narrow [PaneLayout] and needs its own way back to the
 *   feed list. `null` (the default) omits that row entirely rather than rendering it disabled,
 *   since a [PaneLayout.Triple] pane is never navigated away from — the row's presence therefore
 *   depends only on the layout, never on the navigation stack's current depth.
 * @param navigateUpEnabled Whether going back is possible *right now* (false at [PaneLayout.Dual]
 *   while the feed list is still on screen beside this pane). Only the back button's enabled state
 *   depends on it — the row itself stays laid out either way, so nothing below it moves as the user
 *   drills in and back out (see the `ui-guidelines` skill's "Layout stability under state changes").
 * @param onTextInputFocusChange Reports whether this pane's own search field (the one hosted here
 *   when [filter] is [ArticleFilter.Search] at a narrow layout) currently holds focus — same
 *   contract as `FeedListPane`'s own parameter of that name, so `HomeScreen` can suppress bare-key
 *   shortcuts while the user is typing regardless of which pane the field currently lives in.
 * @param onSearchClick Adds a search entry point to [ArticleListPaneContent]'s own top bar when
 *   non-null — the search icon `ui-guidelines`' "Pane structure & tonal roles" section places at
 *   the head of this pane's header row. Not forwarded to [SearchListPane]: once [filter] is already
 *   [ArticleFilter.Search] there is nowhere further to advance to.
 */
@Composable
fun ArticleListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    notifVm: NotificationCenterViewModel? = null,
    onSelectionAdvance: () -> Unit = {},
    onNavigateUp: (() -> Unit)? = null,
    navigateUpEnabled: Boolean = true,
    onTextInputFocusChange: (Boolean) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
) {
    val filter by vm.filter.collectAsStateSafe(ArticleFilter.All)
    val feeds by vm.feeds.collectAsStateSafe(emptyList())
    val folders by vm.folders.collectAsStateSafe(emptyList())
    val tags by vm.tags.collectAsStateSafe(emptyList())
    val title = onNavigateUp?.let {
        articleListTitle(
            filter = filter,
            feeds = feeds,
            folders = folders,
            tags = tags,
            allLabel = stringResource(Res.string.home_all_feeds),
            starredLabel = stringResource(Res.string.home_starred),
            searchLabel = stringResource(Res.string.home_search),
        )
    }
    if (filter is ArticleFilter.Search) {
        SearchListPane(
            vm = vm,
            focused = focused,
            onActivated = onActivated,
            modifier = modifier,
            notifVm = notifVm,
            onNavigateUp = onNavigateUp,
            navigateUpEnabled = navigateUpEnabled,
            onSelectionAdvance = onSelectionAdvance,
            onTextInputFocusChange = onTextInputFocusChange,
        )
        return
    }

    val articles by vm.articles.collectAsStateSafe(emptyList())
    val selected by vm.selectedArticle.collectAsStateSafe(null)
    val unreadOnly by vm.unreadOnly.collectAsStateSafe(false)
    val newestFirst by vm.newestFirst.collectAsStateSafe(true)
    val feedTitles = feeds.associate { it.id to it.displayTitle() }
    val feedFavicons = feeds.associate { it.id to it.favicon_url }

    // Reset the list to the top when the user switches feed/tag/folder/scope, so a new list never
    // opens scrolled to the previous one's offset. Only fires on an actual filter change (not the
    // first composition), so a restored last-selected article's scroll-into-view isn't clobbered.
    val listState = rememberLazyListState()
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            listState.scrollToItem(0)
            lastFilter = filter
        }
    }

    ArticleListPaneContent(
        articles = articles,
        feedTitles = feedTitles,
        feedFavicons = feedFavicons,
        selectedId = selected?.id,
        unreadOnly = unreadOnly,
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
        onActivated = onActivated,
        notifVm = notifVm,
        onNavigateUp = onNavigateUp,
        navigateUpEnabled = navigateUpEnabled,
        title = title,
        onSearchClick = onSearchClick,
    )
}

/**
 * Displays the article search results pane with filtering, selection, and article actions.
 *
 * Search results are shown with matched terms highlighted. The pane displays appropriate hints for
 * short queries and empty results, keeps the selected result visible, and uses relevance ordering.
 *
 * At a narrow `PaneLayout` ([onNavigateUp] non-null), this pane's own top bar is
 * [KeryxExpandedSearchBar] — an editable query field with its own back arrow — rather than
 * [ArticleListTopBar]'s usual back-button-and-title row: the query field itself needs to live
 * wherever the results do (see this file's own module KDoc / the `ui-guidelines` skill's "Adaptive
 * pane layout" section for why), and the back arrow inside it replaces the title row entirely
 * rather than sitting above it, so [ArticleListTopBar] is still called but with `onNavigateUp =
 * null` to suppress its own row. At [PaneLayout.Triple] ([onNavigateUp] is `null`), this pane is
 * unchanged from before: no query field of its own, since `FeedListPane`'s field already covers it.
 *
 * @param focused Whether the pane currently has focus.
 * @param onActivated Called when the pane is activated.
 * @param onTextInputFocusChange Reports whether this pane's own query field currently holds focus
 *   — see `ArticleListPane`'s own parameter of the same name.
 */
@Composable
private fun SearchListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    notifVm: NotificationCenterViewModel? = null,
    onNavigateUp: (() -> Unit)? = null,
    navigateUpEnabled: Boolean = true,
    onSelectionAdvance: () -> Unit = {},
    onTextInputFocusChange: (Boolean) -> Unit = {},
) {
    val query by vm.searchQuery.collectAsStateSafe("")
    val results by vm.searchResults.collectAsStateSafe(emptyList())
    val searching by vm.searching.collectAsStateSafe(false)
    val selected by vm.selectedArticle.collectAsStateSafe(null)
    val unreadOnly by vm.unreadOnly.collectAsStateSafe(false)
    val feeds by vm.feeds.collectAsStateSafe(emptyList())
    val feedTitles = feeds.associate { it.id to it.displayTitle() }
    val feedFavicons = feeds.associate { it.id to it.favicon_url }
    // A query has usable terms once at least one word is long enough for the trigram index
    // (short words like a lone "ab", or "ab cd" where every word is too short, count as no terms).
    val hasValidTerms = searchTerms(query).isNotEmpty()

    val listState = rememberLazyListState()
    // Keep the keyboard-selected result in view (mirrors ArticleListPaneContent's scroll-to-selected).
    LaunchedEffect(selected?.id, results) {
        val index = results.indexOfFirst { it.article.id == selected?.id }
        if (index !in results.indices) return@LaunchedEffect
        listState.scrollToIndexIfNeeded(index)
    }

    // Consumes HomeViewModel's pendingSearchFocus latch — only while this pane's own field is
    // actually on screen (onNavigateUp != null), since at PaneLayout.Triple FeedListPane's own
    // field is the one the latch is meant for instead (see HomeViewModel.requestSearchFocus's KDoc
    // on why this is a latch rather than a one-shot event in the first place).
    val searchFocusRequester = remember { FocusRequester() }
    val pendingSearchFocus by vm.pendingSearchFocus.collectAsStateSafe(false)
    LaunchedEffect(pendingSearchFocus, onNavigateUp) {
        if (onNavigateUp == null || !pendingSearchFocus) return@LaunchedEffect
        searchFocusRequester.requestFocus()
        vm.consumeSearchFocusRequest()
    }
    // A field that unmounts (the user navigates away, or PaneLayout narrows down to Triple mid-
    // session) must report its focus as gone — a LaunchedEffect merely being cancelled does not
    // report false on its own, and a stuck `true` would permanently suppress bare-key shortcuts
    // (see HomeScreen's own textInputFocused KDoc).
    DisposableEffect(Unit) {
        onDispose { onTextInputFocusChange(false) }
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxSize()
            .paneActivation(onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        if (onNavigateUp != null) {
            val keyboardController = LocalSoftwareKeyboardController.current
            KeryxExpandedSearchBar(
                query = query,
                onQueryChange = { vm.setSearchQuery(it) },
                placeholder = stringResource(Res.string.home_search_placeholder),
                onNavigateUp = onNavigateUp,
                navigateUpEnabled = navigateUpEnabled,
                navigateUpContentDescription = stringResource(Res.string.common_back),
                clearContentDescription = stringResource(Res.string.home_search_clear),
                onSearchAction = { keyboardController?.hide() },
                fieldModifier = Modifier
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onTextInputFocusChange(it.isFocused) },
            )
        }
        ArticleListTopBar(
            unreadOnly = unreadOnly,
            onToggleUnreadOnly = { vm.setUnreadOnly(!unreadOnly) },
            newestFirst = true,
            onToggleSort = {},
            onMarkAllRead = { vm.markAllRead() },
            sortEnabled = false,
            notifVm = notifVm,
        )

        Box(Modifier.fillMaxSize().imePadding()) {
            when {
                !hasValidTerms -> CenteredHint(stringResource(Res.string.home_search_too_short))
                // Hold (blank) while the debounced search for the current query is still in flight,
                // instead of flashing "no results" between keystrokes before results arrive. Any
                // previous non-empty results keep showing (the `else` branch) until the new ones land.
                results.isEmpty() && searching -> Unit
                results.isEmpty() -> CenteredHint(stringResource(Res.string.home_search_no_results))
                else -> {
                    val rowMetrics = rememberArticleRowMetrics()
                    val rowStrings = rememberArticleRowStrings()
                    val copyUrl = rememberCopyUrlAction()
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(results, key = { it.article.id }) { result ->
                            val article = result.article
                            ArticleRow(
                                article = article,
                                feedTitle = feedTitles[article.feed_id].orEmpty(),
                                feedFavicon = feedFavicons[article.feed_id],
                                selected = article.id == selected?.id,
                                focused = focused,
                                rowHeight = rowMetrics.rowHeight,
                                faviconSize = rowMetrics.faviconSize,
                                onClick = { vm.selectArticle(article); onActivated(); onSelectionAdvance() },
                                onToggleRead = { vm.toggleRead(article) },
                                onToggleStar = { vm.toggleStar(article) },
                                onCopyUrl = { copyUrl(article.url) },
                                onOpenInBrowser = { BrowserOpener.open(article.url) },
                                titleOverride = markedToAnnotatedString(result.titleMarked.ifBlank { article.title }),
                                strings = rowStrings,
                            )
                        }
                    }
                    VerticalScrollbarIfNeeded(listState)
                }
            }
        }
    }
}

/**
 * Remembers a "copy URL to clipboard" action, shared by [SearchListPane]'s and
 * [ArticleListPaneContent]'s article rows, and by [FeedListPane]'s feed rows.
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
 * Always reflects the current sort direction, even in the search scope where the button is
 * disabled (results stay pinned to FTS5 relevance rank) — [ArticleListTopBar] conveys "disabled"
 * through `TooltipIconButton`'s own dimmed styling, not through swapping the glyph itself.
 */
internal fun sortDirectionIcon(newestFirst: Boolean): DrawableResource =
    if (newestFirst) KeryxIcons.SortDescending else KeryxIcons.SortAscending

/**
 * The top bar shared by the normal article list ([ArticleListPaneContent]) and the search scope
 * ([SearchListPane]): unread-only toggle, notifications bell, sort, mark-all-read. When
 * [sortEnabled] is false (search scope, where results stay pinned to FTS5 relevance rank), the
 * sort button is disabled and its tooltip explains why instead of showing the usual "sort by ...".
 *
 * When [onNavigateUp] is non-null (this pane is shown alone or paired at a narrow
 * [PaneLayout] — see `ArticleListPane`'s KDoc), a leading back-button-and-[title] row is added
 * above the controls row rather than folded into it: the controls row is unchanged from
 * [PaneLayout.Triple]/[PaneLayout.Dual] so the unread-only toggle stays reachable at every width
 * instead of being dropped for space.
 *
 * That row is laid out for the whole time the pane stays at a narrow layout, and only the back
 * button's `enabled` state follows [navigateUpEnabled] — at [PaneLayout.Dual] the feed list slides
 * in and out beside this pane as the user drills into an article and back, and hiding the row for
 * the half of that cycle where there is nothing to go back to would move the controls row (and the
 * whole list under it) up and down each time.
 *
 * @param onSearchClick Adds a search icon at the head of the controls row's [ToolbarIconGroup] when
 *   non-null (before notifications/sort/mark-all-read — the order the `ui-guidelines` skill's
 *   "Pane structure & tonal roles" section lists them in) — the entry point into search at a
 *   narrow layout, where the feed list's own search field is folded into a collapsed bar instead
 *   (see `FeedListPane`'s KDoc). [SearchListPane] never passes this: once already in the Search
 *   scope there is nowhere further to advance to.
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
    onNavigateUp: (() -> Unit)? = null,
    navigateUpEnabled: Boolean = true,
    title: String? = null,
    onSearchClick: (() -> Unit)? = null,
) {
    WindowDragArea(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        if (onNavigateUp != null) {
            val backLabel = stringResource(Res.string.common_back)
            KeryxPaneTopBar(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp),
                title = title.orEmpty(),
                navigationIcon = {
                    TooltipIconButton(tooltip = backLabel, onClick = onNavigateUp, enabled = navigateUpEnabled) {
                        KeryxIcon(KeryxIcons.ArrowBack, contentDescription = backLabel)
                    }
                },
            )
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
                if (onSearchClick != null) {
                    val searchLabel = stringResource(Res.string.home_search)
                    TooltipIconButton(tooltip = searchLabel, onClick = onSearchClick) {
                        KeryxIcon(KeryxIcons.Search, contentDescription = searchLabel)
                    }
                }
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
 * Renders the article list with sorting, unread filtering, selection, and article actions.
 *
 * @param articles The article rows to display.
 * @param feedTitles Display titles keyed by feed identifier.
 * @param feedFavicons Favicon URLs keyed by feed identifier.
 * @param selectedId The identifier of the selected article, if any.
 * @param unreadOnly Whether to show only unread articles.
 * @param newestFirst Whether to sort articles from newest to oldest.
 * @param focused Whether the list has focus.
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
    onNavigateUp: (() -> Unit)? = null,
    navigateUpEnabled: Boolean = true,
    title: String? = null,
    onSearchClick: (() -> Unit)? = null,
) {
    LaunchedEffect(selectedId, articles.isNotEmpty()) {
        val index = articles.indexOfFirst { it.id == selectedId }
        if (index !in articles.indices) return@LaunchedEffect
        listState.scrollToIndexIfNeeded(index)
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxSize()
            .paneActivation(onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        ArticleListTopBar(
            unreadOnly = unreadOnly,
            onToggleUnreadOnly = onToggleUnreadOnly,
            newestFirst = newestFirst,
            onToggleSort = onToggleSort,
            onMarkAllRead = onMarkAllRead,
            sortEnabled = true,
            notifVm = notifVm,
            onNavigateUp = onNavigateUp,
            navigateUpEnabled = navigateUpEnabled,
            title = title,
            onSearchClick = onSearchClick,
        )

        if (articles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.home_no_articles), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val rowMetrics = rememberArticleRowMetrics()
            val rowStrings = rememberArticleRowStrings()
            Box(Modifier.fillMaxSize()) {
                val copyUrl = rememberCopyUrlAction()
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                            strings = rowStrings,
                        )
                    }
                }
                VerticalScrollbarIfNeeded(listState)
            }
        }
    }
}

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
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
import works.merc.keryx.app.resources.home_mark_all_read
import works.merc.keryx.app.resources.home_no_articles
import works.merc.keryx.app.resources.home_search_no_results
import works.merc.keryx.app.resources.home_search_too_short
import works.merc.keryx.app.resources.home_sort_disabled_search
import works.merc.keryx.app.resources.home_sort_newest
import works.merc.keryx.app.resources.home_sort_oldest
import works.merc.keryx.app.resources.home_unread_only
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
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
 */
@Composable
fun ArticleListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    notifVm: NotificationCenterViewModel? = null,
) {
    val filter by vm.filter.collectAsStateSafe(ArticleFilter.All)
    if (filter is ArticleFilter.Search) {
        SearchListPane(vm, focused, onActivated, modifier, notifVm)
        return
    }

    val articles by vm.articles.collectAsStateSafe(emptyList())
    val feeds by vm.feeds.collectAsStateSafe(emptyList())
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
        onSelectArticle = { vm.selectArticle(it); onActivated() },
        onToggleRead = { vm.toggleRead(it) },
        onToggleStar = { vm.toggleStar(it) },
        modifier = modifier,
        listState = listState,
        onActivated = onActivated,
        notifVm = notifVm,
        unreadOnlyEnabled = isUnreadOnlyEnabled(filter),
    )
}

/**
 * Displays the article search results pane with filtering, selection, and article actions.
 *
 * Search results are shown with matched terms highlighted. The pane displays appropriate hints for
 * short queries and empty results, keeps the selected result visible, and uses relevance ordering.
 *
 * @param focused Whether the pane currently has focus.
 * @param onActivated Called when the pane is activated.
 */
@Composable
private fun SearchListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    notifVm: NotificationCenterViewModel? = null,
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

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        ArticleListTopBar(
            unreadOnly = unreadOnly,
            onToggleUnreadOnly = { vm.setUnreadOnly(!unreadOnly) },
            newestFirst = true,
            onToggleSort = {},
            onMarkAllRead = { vm.markAllRead() },
            sortEnabled = false,
            notifVm = notifVm,
        )

        Box(Modifier.fillMaxSize()) {
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
                    val clipboard = LocalClipboard.current
                    val scope = rememberCoroutineScope()
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
                                onClick = { vm.selectArticle(article); onActivated() },
                                onToggleRead = { vm.toggleRead(article) },
                                onToggleStar = { vm.toggleStar(article) },
                                onCopyUrl = {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipboardEntries.ofText(article.url))
                                    }
                                },
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

/** Whether the "unread only" toggle should be enabled for the given article filter. */
internal fun isUnreadOnlyEnabled(filter: ArticleFilter): Boolean = filter != ArticleFilter.Starred

/**
 * The top button row shared by the normal article list ([ArticleListPaneContent]) and the search
 * scope ([SearchListPane]): unread-only toggle, notifications bell, sort, mark-all-read. When
 * [sortEnabled] is false (search scope, where results stay pinned to FTS5 relevance rank), the
 * sort button is disabled and its tooltip explains why instead of showing the usual "sort by ...".
 */
@Composable
internal fun ArticleListTopBar(
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
    onMarkAllRead: () -> Unit,
    sortEnabled: Boolean = true,
    unreadOnlyEnabled: Boolean = true,
    notifVm: NotificationCenterViewModel? = null,
) {
    WindowDragArea(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleChip(
            label = stringResource(Res.string.home_unread_only),
            checked = unreadOnly,
            onCheckedChange = { onToggleUnreadOnly() },
            enabled = unreadOnlyEnabled,
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
                KeryxIcon(
                    KeryxIcons.Sort,
                    contentDescription = sortTooltip,
                    modifier = Modifier.graphicsLayer(scaleY = if (sortEnabled && !newestFirst) -1f else 1f),
                )
            }
            val markAllReadTooltip = stringResource(Res.string.home_mark_all_read)
            TooltipIconButton(tooltip = markAllReadTooltip, onClick = onMarkAllRead) {
                KeryxIcon(KeryxIcons.DoneAll, contentDescription = markAllReadTooltip)
            }
        }
    }
    }
}

/**
 * Renders the article list with filtering controls, selection state, and article actions.
 *
 * @param articles The articles to display.
 * @param feedTitles Display titles keyed by feed identifier.
 * @param feedFavicons Favicon URLs keyed by feed identifier.
 * @param selectedId The id of the currently selected article, if any.
 * @param unreadOnly Whether only unread articles are shown.
 * @param newestFirst Whether articles are sorted newest first.
 * @param focused Whether the list is focused.
 * @param unreadOnlyEnabled Whether the unread-only toggle is enabled.
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
    unreadOnlyEnabled: Boolean = true,
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        ArticleListTopBar(
            unreadOnly = unreadOnly,
            onToggleUnreadOnly = onToggleUnreadOnly,
            newestFirst = newestFirst,
            onToggleSort = onToggleSort,
            onMarkAllRead = onMarkAllRead,
            sortEnabled = true,
            unreadOnlyEnabled = unreadOnlyEnabled,
            notifVm = notifVm,
        )

        if (articles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.home_no_articles), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val rowMetrics = rememberArticleRowMetrics()
            val rowStrings = rememberArticleRowStrings()
            Box(Modifier.fillMaxSize()) {
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
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
                            onCopyUrl = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipboardEntries.ofText(article.url))
                                }
                            },
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

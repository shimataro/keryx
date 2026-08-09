package works.merc.keryx.app.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.DETAIL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.common_ok
import works.merc.keryx.app.resources.notification_detail_title
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_action
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_body
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_title
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController

enum class HomePane { FeedList, ArticleList, ArticleDetail }

/**
 * Renders the home screen and coordinates feed selection, article actions, pane focus, keyboard shortcuts, menu commands, feed subscriptions, and pending notification actions.
 */
@Composable
fun HomeScreen() {
    val vm = koinInject<HomeViewModel>()
    val notifVm = koinInject<NotificationCenterViewModel>()
    val menuController = koinInject<MenuController>()

    // Note: the selected article is deliberately NOT collected at this scope. It's only needed
    // inside the keyboard-shortcut callbacks below, which read vm.selectedArticle.value live at
    // invocation (same pattern as openSelectedInBrowser/copySelectedUrl) — collecting it here would
    // recompose the whole HomeScreen on every arrow-key selection change for no rendering benefit.
    val feeds by vm.feeds.collectAsStateSafe(emptyList())
    val tags by vm.tags.collectAsStateSafe(emptyList())
    val folders by vm.folders.collectAsStateSafe(emptyList())
    val collapsedFolderIds by vm.collapsedFolderIds.collectAsStateSafe(emptySet())
    val filter by vm.filter.collectAsStateSafe(ArticleFilter.All)
    val feedListPaneWidth by vm.feedListPaneWidth.collectAsStateSafe(FEED_LIST_PANE_WIDTH_DEFAULT.toDouble())
    val articleListPaneWidth by vm.articleListPaneWidth.collectAsStateSafe(ARTICLE_LIST_PANE_WIDTH_DEFAULT.toDouble())

    var showAddFeed by remember { mutableStateOf(false) }
    // The feed list's drag ghost is hosted here, not in FeedListPane: the chip has to be able to
    // float across the whole window (past the feed pane's right edge, over the article list), and a
    // composable inside FeedListPane would be painted before — and therefore under — its siblings.
    val dragOverlay = remember { FeedDragOverlayState() }
    // Bumped on each keyboard-shortcut copy; ArticleDetailPane watches it to flash its copy button's
    // inline ✓ (the keyboard copies the selected article, which that pane already shows).
    var copyPulse by remember { mutableStateOf(0) }
    // Bumped by the R/F2(Enter)/Delete feed-list shortcuts; FeedListPane observes these and resolves
    // the currently selected filter (feed/folder/tag) against its own already-collected rows to
    // trigger the same rename/edit and delete/unsubscribe dialogs the context menu uses.
    var feedListRenameRequestId by remember { mutableStateOf(0) }
    var feedListDeleteRequestId by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var focusedPane by remember { mutableStateOf(vm.getInitialFocusedPane()) }
    // True while the sidebar search field holds focus, so the root keyboard shortcuts step aside and
    // let typed letters/arrows reach the field (they'd otherwise be swallowed by homeKeyboardShortcuts).
    var searchFieldFocused by remember { mutableStateOf(false) }
    // Arrow keys only actually reach a pane when this window has real OS focus (not a modal dialog,
    // Settings/About, or another application) and the search field isn't the one consuming them —
    // panes must render their selection dimmed in every other case, not just when focus moved to a
    // different pane within this window.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val keyboardNavActive = windowFocused && !searchFieldFocused
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current

    fun setFocusedPane(pane: HomePane) {
        if (pane == focusedPane) return
        focusedPane = pane
        vm.setFocusedPane(pane)
    }

    // Mirrors focusedPane into MenuController (composition-local state -> StateFlow, same pattern
    // App.kt already uses for currentScreen) so AppMenuBar can gate the Feed menu's selected-feed
    // items on the feed list actually having keyboard focus.
    LaunchedEffect(focusedPane) { menuController.focusedPane.value = focusedPane }

    val orderedFilters = remember(tags, folders, feeds, collapsedFolderIds) {
        buildOrderedFilters(tags, folders, feeds, collapsedFolderIds)
    }
    fun moveFeedSelection(delta: Int) {
        nextFeedFilter(filter, orderedFilters, delta)?.let { vm.selectFilter(it) }
    }

    // Shared by the keyboard shortcuts and the menu bar (via MenuController). Read the current
    // selection at call time (vm.selectedArticle.value) so a command collected once stays correct.
    fun openSelectedInBrowser() {
        vm.selectedArticle.value?.url?.takeIf { it.isNotBlank() }?.let { BrowserOpener.open(it) }
    }
    fun copySelectedUrl() {
        vm.selectedArticle.value?.url?.takeIf { it.isNotBlank() }?.let {
            scope.launch {
                clipboard.setClipEntry(ClipboardEntries.ofText(it))
                copyPulse++
            }
        }
    }
    fun focusSearch() {
        vm.selectFilter(ArticleFilter.Search)
        setFocusedPane(HomePane.FeedList)
        vm.requestSearchFocus()
    }
    fun refreshSelectedFeedListItem() {
        val target = (filter as? ArticleFilter.Feed) ?: return
        feeds.find { it.id == target.feedId }?.let { vm.refreshFeed(it) }
    }

    // Menu commands whose target state lives in this screen's composition.
    LaunchedEffect(Unit) {
        menuController.commands.collect { command ->
            when (command) {
                MenuCommand.AddFeed -> showAddFeed = true
                MenuCommand.FocusSearch -> focusSearch()
                MenuCommand.OpenInBrowser -> openSelectedInBrowser()
                MenuCommand.CopyUrl -> copySelectedUrl()
                else -> {}
            }
        }
    }

    Scaffold { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .homeKeyboardShortcuts(
                    searchFieldFocused = searchFieldFocused,
                    onEscape = { dragOverlay.cancel() },
                    onUp = {
                        when (focusedPane) {
                            HomePane.FeedList -> moveFeedSelection(-1)
                            HomePane.ArticleList -> vm.selectPrevious()
                            // The article body scrolls inside the native WebView itself now
                            // (see plan doc html-webview-os-wobbly-hammock.md), so there's no
                            // Compose ScrollState left here to drive with the keyboard.
                            HomePane.ArticleDetail -> {}
                        }
                    },
                    onDown = {
                        when (focusedPane) {
                            HomePane.FeedList -> moveFeedSelection(1)
                            HomePane.ArticleList -> vm.selectNext()
                            HomePane.ArticleDetail -> {}
                        }
                    },
                    onLeft = {
                        when (focusedPane) {
                            HomePane.FeedList -> {}
                            HomePane.ArticleList -> setFocusedPane(HomePane.FeedList)
                            HomePane.ArticleDetail -> setFocusedPane(HomePane.ArticleList)
                        }
                    },
                    onRight = {
                        when (focusedPane) {
                            HomePane.FeedList -> {
                                if (vm.selectedArticle.value == null) vm.currentArticles().firstOrNull()?.let { vm.selectArticle(it) }
                                setFocusedPane(HomePane.ArticleList)
                            }
                            HomePane.ArticleList -> setFocusedPane(HomePane.ArticleDetail)
                            HomePane.ArticleDetail -> {}
                        }
                    },
                    onNextArticle = { vm.selectNext() },
                    onPreviousArticle = { vm.selectPrevious() },
                    onToggleRead = { if (articleActionAllowed(focusedPane)) vm.toggleReadSelected() },
                    onToggleStar = { if (articleActionAllowed(focusedPane)) vm.toggleStarSelected() },
                    onOpenInBrowser = { if (articleActionAllowed(focusedPane)) openSelectedInBrowser() },
                    onCopyUrl = { if (articleActionAllowed(focusedPane)) copySelectedUrl() },
                    onFeedListRefresh = { if (feedListActionAllowed(focusedPane)) refreshSelectedFeedListItem() },
                    onFeedListRename = { if (feedListActionAllowed(focusedPane)) feedListRenameRequestId++ },
                    onFeedListDelete = { if (feedListActionAllowed(focusedPane)) feedListDeleteRequestId++ },
                    onSearch = { focusSearch() },
                ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val dividerWidth = 8.dp
                // coerceAtLeast(0.dp): with WINDOW_MIN_WIDTH >= the pane-minimum sum, this
                // shouldn't go negative in steady state, but a transient pre-layout frame
                // (maxWidth == 0) must not produce a negative Dp, which Modifier.width() rejects.
                val availableForPanes = (maxWidth - dividerWidth * 2 - DETAIL_PANE_MIN_WIDTH.dp).coerceAtLeast(0.dp)
                val rawFeedWidth = feedListPaneWidth.dp
                val rawArticleWidth = articleListPaneWidth.dp
                val rawTotal = rawFeedWidth + rawArticleWidth
                val scale = if (rawTotal > availableForPanes && rawTotal > 0.dp) availableForPanes / rawTotal else 1f
                val displayedFeedWidth = rawFeedWidth * scale
                val displayedArticleWidth = rawArticleWidth * scale

                Row(Modifier.fillMaxSize()) {
                    FeedListPane(
                        vm,
                        focused = focusedPane == HomePane.FeedList && keyboardNavActive,
                        dragOverlay = dragOverlay,
                        onActivated = { setFocusedPane(HomePane.FeedList) },
                        modifier = Modifier.width(displayedFeedWidth),
                        onAddFeedClick = { showAddFeed = true },
                        onSearchFieldFocusChange = { searchFieldFocused = it },
                        renameSelectedRequestId = feedListRenameRequestId,
                        deleteSelectedRequestId = feedListDeleteRequestId,
                    )
                    ResizableDivider(onDrag = { deltaPx ->
                        vm.setFeedListPaneWidth(feedListPaneWidth + with(density) { deltaPx.toDp().value })
                    })
                    ArticleListPane(
                        vm,
                        focused = focusedPane == HomePane.ArticleList && keyboardNavActive,
                        onActivated = { setFocusedPane(HomePane.ArticleList) },
                        modifier = Modifier.width(displayedArticleWidth),
                        notifVm = notifVm,
                    )
                    ResizableDivider(onDrag = { deltaPx ->
                        vm.setArticleListPaneWidth(articleListPaneWidth + with(density) { deltaPx.toDp().value })
                    })
                    ArticleDetailPane(
                        vm,
                        modifier = Modifier.weight(1f),
                        onActivated = { setFocusedPane(HomePane.ArticleDetail) },
                        copyPulse = copyPulse,
                    )
                }
            }
            // Last child of the root Box, so the floating drag chip paints above every pane.
            FeedDragGhost(dragOverlay)
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    if (showAddFeed) {
        AddFeedDialog(
            vm = vm,
            feeds = feeds,
            onDismiss = { showAddFeed = false },
            // Full success closes silently — the new feed appearing in the list is the confirmation.
            // Partial/total failure keeps the dialog open (see runSubscribe) to show what failed.
            onSubscribed = { showAddFeed = false },
        )
    }

    // Notification next-actions whose target lives on this screen are resolved here — hosted at the
    // screen level, outside the bell popup which dismisses on focus loss. ShowSettingsTab is resolved
    // by App instead (the settings dialog lives there).
    notifVm.pendingAction?.let { pending ->
        when (val action = pending.action) {
            AppNotificationAction.ResetCloudData ->
                // Corrupt/incompatible cloud DB: confirm the destructive reset, then clear the
                // now-stale error notification.
                KeryxAlertDialog(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    onDismissRequest = { notifVm.clearPendingAction() },
                    title = stringResource(Res.string.settings_cloud_reset_confirm_title),
                    text = { Text(stringResource(Res.string.settings_cloud_reset_confirm_body)) },
                    confirmText = stringResource(Res.string.settings_cloud_reset_confirm_action),
                    onConfirm = {
                        vm.resetCloudData()
                        notifVm.dismiss(pending.id)
                        notifVm.clearPendingAction()
                    },
                    dismissText = stringResource(Res.string.common_cancel),
                )
            // Same effect as clicking that feed in the feed list.
            is AppNotificationAction.ShowFeedDetail -> LaunchedEffect(pending.id) {
                vm.selectFilter(ArticleFilter.Feed(action.feedId))
                setFocusedPane(HomePane.FeedList)
                notifVm.clearPendingAction()
            }
            // Explanation only (e.g. the macOS translocation warning) — no navigation, one button.
            is AppNotificationAction.ShowInfoDialog ->
                KeryxAlertDialog(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    onDismissRequest = { notifVm.clearPendingAction() },
                    title = stringResource(Res.string.notification_detail_title),
                    text = { Text(action.detail) },
                    confirmText = stringResource(Res.string.common_ok),
                    onConfirm = { notifVm.clearPendingAction() },
                )
            else -> Unit
        }
    }
}

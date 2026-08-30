package works.merc.keryx.app.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.DETAIL_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.PANE_DIVIDER_WIDTH
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.platform.BackHandler
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.isTouchPrimary
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.common_ok
import works.merc.keryx.app.resources.notification_detail_title
import works.merc.keryx.app.resources.notification_snackbar_action
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_action
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_body
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_title
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController

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
    // Whether a Search-scope snapshot is waiting to be restored — see homeBackAction's own KDoc.
    // Only its nullness is read here; _filter is written in exactly one place (selectFilter), which
    // also clears this whenever the filter moves away from Search, so a non-null entry always means
    // the filter is currently Search — no need to separately collect vm.filter (and recompose this
    // whole screen on every filter change) just to re-derive what this already implies.
    val searchScopeEntry by vm.searchScopeEntry.collectAsStateSafe(null)
    val tags by vm.tags.collectAsStateSafe(emptyList())
    val folders by vm.folders.collectAsStateSafe(emptyList())
    val collapsedFolderIds by vm.collapsedFolderIds.collectAsStateSafe(emptySet())
    val expandedTagIds by vm.expandedTagIds.collectAsStateSafe(emptySet())
    val feedTagMap by vm.feedTagMap.collectAsStateSafe(emptyMap())
    val selectedRowInstance by vm.selectedRowInstance.collectAsStateSafe(FeedListRowSelection.All)
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
    // Bumped by the F2(Enter)/Delete feed-list shortcuts; FeedListPane observes these and resolves
    // the currently selected filter (feed/folder/tag) against its own already-collected rows to
    // trigger the same rename/edit and delete/unsubscribe dialogs the context menu uses.
    var feedListRenameRequestId by remember { mutableStateOf(0) }
    var feedListDeleteRequestId by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var focusedPane by remember { mutableStateOf(vm.getInitialFocusedPane()) }
    // Two separate flags, one per pane that can host a text input — not a single shared
    // `textInputFocused` — because at PaneLayout.Dual (depth <= 2) FeedListPane and ArticleListPane
    // are both on screen at once, and a single `var` would let one pane's `false` (e.g. its field
    // unmounting) clobber the other's still-current `true`. See FeedListPane's/ArticleListPane's
    // own DisposableEffect for why a pane reliably reports `false` when it unmounts.
    var feedListTextInputFocused by remember { mutableStateOf(false) }
    var articleListTextInputFocused by remember { mutableStateOf(false) }
    // True while a text input in either pane holds focus — the feed list's search field (or a row's
    // inline name editor), or the article list's own search field at a narrow layout — so the root
    // keyboard shortcuts step aside and let typed letters/arrows reach it (they'd otherwise be
    // swallowed by homeKeyboardShortcuts).
    val textInputFocused = feedListTextInputFocused || articleListTextInputFocused
    // Mirrors BoxWithConstraints' own `paneLayoutFor(maxWidth)` so focusSearch() (Cmd+F / the menu
    // bar's "Search…") can route to whichever pane currently owns the field: FeedListPane at
    // PaneLayout.Triple, ArticleListPane's SearchListPane at a narrow layout (see FeedListPane's own
    // KDoc on why the field moves there). Initialized to Triple so desktop is already correct on
    // the very first frame, before BoxWithConstraints below has measured anything.
    var paneLayout by remember { mutableStateOf(PaneLayout.Triple) }
    // Whether HomeScreen has already clamped focusedPane for a narrow layout at least once this
    // session — see the one-shot LaunchedEffect inside BoxWithConstraints below for why this must
    // never re-fire (a mid-session narrow<->Triple flip, e.g. a window resize or an Android
    // rotation, must not yank the user off whatever article they're reading).
    var initialPaneClamped by remember { mutableStateOf(false) }
    // Arrow keys only actually reach a pane when this window has real OS focus (not a modal dialog,
    // Settings/About, or another application) and the search field isn't the one consuming them —
    // panes must render their selection dimmed in every other case, not just when focus moved to a
    // different pane within this window.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val keyboardNavActive = windowFocused && !textInputFocused
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current

    fun setFocusedPane(pane: HomePane) {
        if (pane == focusedPane) return
        focusedPane = pane
        vm.setFocusedPane(pane)
    }

    // At a narrow PaneLayout, focusedPane doubles as the navigation stack's depth cursor (see
    // HomePane's KDoc) — one step back is just the previous ordinal, with no separate depth state
    // to keep in sync. See homeBackAction's own KDoc for why exiting the Search scope is resolved
    // as a distinct action rather than always popping the pane stack.
    fun goBack() {
        when (homeBackAction(paneLayout, focusedPane.ordinal + 1, searchScopeEntry != null)) {
            HomeBackAction.ExitSearch -> vm.exitSearchScope()?.let { setFocusedPane(it) }
            HomeBackAction.PopPane -> {
                val previous = focusedPane.ordinal - 1
                if (previous >= 0) setFocusedPane(HomePane.entries[previous])
            }
            HomeBackAction.None -> {}
        }
    }

    // Mirrors that focus state into MenuController (composition-local state -> StateFlow, same
    // pattern App.kt already uses for currentScreen): a native Swing accelerator has no equivalent
    // to KeyboardNav.kt's textInputFocused suppression, so AppMenuBar needs this to disable the
    // Feed menu's bare-key items (F2/Delete) while the user is actually typing.
    LaunchedEffect(textInputFocused) { menuController.textInputFocused.value = textInputFocused }

    val orderedRows = remember(tags, folders, feeds, collapsedFolderIds, expandedTagIds, feedTagMap) {
        buildOrderedFeedListRows(tags, folders, feeds, collapsedFolderIds, expandedTagIds, feedTagMap)
    }
    fun moveFeedSelection(delta: Int) {
        nextFeedListRow(selectedRowInstance, orderedRows, delta)?.let { vm.selectFilter(it.filter, it) }
    }

    // Shared by the keyboard shortcuts and the menu bar (via MenuController). Read the current
    // selection at call time (vm.selectedArticle.value) so a command collected once stays correct.
    fun openSelectedInBrowser() {
        vm.selectedArticle.value?.url?.takeIf { hasUsableUrl(it) }?.let { BrowserOpener.open(it) }
    }
    fun copySelectedUrl() {
        vm.selectedArticle.value?.url?.takeIf { hasUsableUrl(it) }?.let {
            scope.launch {
                clipboard.setClipEntry(ClipboardEntries.ofText(it))
                copyPulse++
            }
        }
    }
    fun focusSearch() {
        // Read before setFocusedPane below overwrites it: at a narrow layout, coerced down to
        // ArticleList so triggering this from the article detail pane doesn't later restore back
        // into a detail view with no list around it (see enterSearchScope's own KDoc on
        // returnPane) — matching initialPaneFor's own clamp for the same reason.
        val returnPane = if (paneLayout == PaneLayout.Triple) focusedPane else minOf(focusedPane, HomePane.ArticleList)
        vm.enterSearchScope(returnPane)
        // At PaneLayout.Triple the field stays in FeedListPane; at a narrow layout it has moved
        // into ArticleListPane's SearchListPane instead (see FeedListPane's own KDoc) — Triple is
        // paneLayout's initial value, so desktop's behavior here is unchanged.
        setFocusedPane(if (paneLayout == PaneLayout.Triple) HomePane.FeedList else HomePane.ArticleList)
    }

    // Same live-read-at-call-time pattern as openSelectedInBrowser/copySelectedUrl, resolving the
    // selected feed the same way AppMenuBar does (filter.value against the already-collected feeds).
    fun selectedFeedForMenu(): Feeds? =
        (vm.filter.value as? ArticleFilter.Feed)?.let { f -> feeds.find { it.id == f.feedId } }
    fun copySelectedFeedUrl() {
        selectedFeedForMenu()?.url?.takeIf { hasUsableUrl(it) }?.let {
            scope.launch { clipboard.setClipEntry(ClipboardEntries.ofText(it)) }
        }
    }
    fun copySelectedFeedSiteUrl() {
        selectedFeedForMenu()?.site_url?.takeIf { hasUsableUrl(it) }?.let {
            scope.launch { clipboard.setClipEntry(ClipboardEntries.ofText(it)) }
        }
    }

    // Menu commands whose target state lives in this screen's composition.
    LaunchedEffect(Unit) {
        menuController.commands.collect { command ->
            when (command) {
                MenuCommand.AddFeed -> showAddFeed = true
                MenuCommand.FocusSearch -> focusSearch()
                MenuCommand.OpenInBrowser -> openSelectedInBrowser()
                MenuCommand.CopyUrl -> copySelectedUrl()
                MenuCommand.CopyFeedUrl -> copySelectedFeedUrl()
                MenuCommand.CopySiteUrl -> copySelectedFeedSiteUrl()
                else -> {}
            }
        }
    }

    // Desktop has no in-app snackbar convention (see LocalSnackbarHostState's own KDoc), so the
    // host is only created — and provided — on a touch-primary platform.
    val snackbarHostState = if (isTouchPrimary) remember { SnackbarHostState() } else null
    Scaffold { padding ->
        CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Box(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .homeKeyboardShortcuts(
                    textInputFocused = textInputFocused,
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
                    onFeedListRename = { if (feedListActionAllowed(focusedPane)) feedListRenameRequestId++ },
                    onFeedListDelete = { if (feedListActionAllowed(focusedPane)) feedListDeleteRequestId++ },
                    onSearch = { focusSearch() },
                ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val layout = paneLayoutFor(maxWidth)
                LaunchedEffect(layout) { paneLayout = layout }
                // Clamps focusedPane for a narrow layout exactly once, on the first frame with a
                // real (post-layout) width — a transient pre-layout frame reports maxWidth == 0.dp,
                // which paneLayoutFor resolves to Single regardless of the eventual layout, and
                // clamping against that would misfire even on desktop. See initialPaneFor's own
                // KDoc for why restoring straight into ArticleDetail at a narrow layout is wrong;
                // this LaunchedEffect composes only until initialPaneClamped flips true, and never
                // again after that, so a later resize/rotation can't re-trigger it.
                if (!initialPaneClamped && maxWidth > 0.dp) {
                    LaunchedEffect(Unit) {
                        initialPaneClamped = true
                        val clamped = initialPaneFor(layout, focusedPane)
                        if (clamped != focusedPane) setFocusedPane(clamped)
                    }
                }
                // BackHandler is always called (its own `enabled` gates the actual interception).
                // homeBackAction is false (None) at PaneLayout.Triple (visiblePanes never changes
                // there — desktop's WINDOW_MIN_WIDTH never resolves to anything else, see
                // TRIPLE_PANE_MIN_WIDTH's KDoc), so the app's default (OS back gesture /
                // Alt+F4-equivalent) is left alone there. It is also None at PaneLayout.Dual depth
                // 1->2 outside the Search scope (visiblePanes' sliding window shows the same two
                // panes at both depths), so a back press that would produce no visible change falls
                // through instead of being swallowed — but ExitSearch still applies there while
                // Search is active, since exiting it always changes what's on screen.
                val backAction = homeBackAction(layout, focusedPane.ordinal + 1, searchScopeEntry != null)
                BackHandler(enabled = backAction != HomeBackAction.None) { goBack() }

                // Single: tapping a row navigates away from it (drills into the article list, or
                // the article detail), so a lingering selection highlight there would mark a row
                // the user can no longer see — see LocalRowSelectionVisible's own KDoc. Dual/Triple
                // keep it: the selected row's pane stays on screen alongside the pane it opened.
                CompositionLocalProvider(LocalRowSelectionVisible provides (layout != PaneLayout.Single)) {
                if (layout == PaneLayout.Triple) {
                    val dividerWidth = PANE_DIVIDER_WIDTH.dp
                    // coerceAtLeast(0.dp): with WINDOW_MIN_WIDTH >= the pane-minimum sum, this
                    // shouldn't go negative in steady state, but a transient pre-layout frame
                    // (maxWidth == 0) must not produce a negative Dp, which Modifier.width() rejects.
                    val availableForPanes = (maxWidth - dividerWidth * 2 - DETAIL_PANE_MIN_WIDTH.dp).coerceAtLeast(0.dp)
                    val (displayedFeedWidth, displayedArticleWidth) =
                        triplePaneWidths(availableForPanes, feedListPaneWidth.dp, articleListPaneWidth.dp)

                    Row(Modifier.fillMaxSize()) {
                        FeedListPane(
                            vm,
                            focused = focusedPane == HomePane.FeedList && keyboardNavActive,
                            dragOverlay = dragOverlay,
                            onActivated = { setFocusedPane(HomePane.FeedList) },
                            modifier = Modifier.width(displayedFeedWidth),
                            onAddFeedClick = { showAddFeed = true },
                            onTextInputFocusChange = { feedListTextInputFocused = it },
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
                } else {
                    // Single/Dual: no resizable dividers (nothing to drag on a phone/narrow window)
                    // and no persisted pane widths — visible panes just split the width evenly.
                    // See HomePaneLayout.kt's visiblePanes for what's shown at each depth.
                    val visible = visiblePanes(layout, focusedPane.ordinal + 1)
                    Row(Modifier.fillMaxSize()) {
                        val paneModifier = if (visible.size > 1) Modifier.weight(1f) else Modifier.fillMaxSize()
                        visible.forEach { pane ->
                            when (pane) {
                                HomePane.FeedList -> FeedListPane(
                                    vm,
                                    focused = focusedPane == HomePane.FeedList && keyboardNavActive,
                                    dragOverlay = dragOverlay,
                                    onActivated = { setFocusedPane(HomePane.FeedList) },
                                    modifier = paneModifier,
                                    onAddFeedClick = { showAddFeed = true },
                                    onTextInputFocusChange = { feedListTextInputFocused = it },
                                    renameSelectedRequestId = feedListRenameRequestId,
                                    deleteSelectedRequestId = feedListDeleteRequestId,
                                    onSelectionAdvance = { setFocusedPane(HomePane.ArticleList) },
                                    // The bell lives in ArticleListPane's header everywhere it is
                                    // on screen; this pane only has to host it when it isn't —
                                    // PaneLayout.Single's depth 1. Derived from `visible` rather
                                    // than from a layout/depth check of its own, so the two panes
                                    // can never both draw one (or both skip it).
                                    notifVm = notifVm.takeIf { HomePane.ArticleList !in visible },
                                )
                                HomePane.ArticleList -> ArticleListPane(
                                    vm,
                                    focused = focusedPane == HomePane.ArticleList && keyboardNavActive,
                                    onActivated = { setFocusedPane(HomePane.ArticleList) },
                                    modifier = paneModifier,
                                    notifVm = notifVm,
                                    onSelectionAdvance = { setFocusedPane(HomePane.ArticleDetail) },
                                    // Every narrow layout gives this pane its own back-button row
                                    // (the Triple branch above passes none at all), and only the
                                    // button's enabled state tracks whether there is anywhere to go
                                    // back to — see homeBackAction's own KDoc (None at Dual depth
                                    // 1->2 outside Search, where the feed list is still on screen
                                    // beside this pane). Hiding the row instead would shift the
                                    // controls row and the whole list under it every time Dual slides.
                                    onNavigateUp = ::goBack,
                                    navigateUpEnabled = backAction != HomeBackAction.None,
                                    onTextInputFocusChange = { articleListTextInputFocused = it },
                                    // Only outside the Search scope: once already there, there is
                                    // nowhere further to advance to (see ArticleListTopBar's own
                                    // KDoc on onSearchClick). Doesn't advance the navigation stack —
                                    // the field lives on this same pane (see enterSearchScope's own
                                    // KDoc on returnPane). setFocusedPane is still required at
                                    // PaneLayout.Dual: the search icon's own onClick never reaches
                                    // paneActivation (a separate, unchained click handler — see
                                    // ArticleListPaneContent), so without this, focusedPane could
                                    // still be FeedList (both panes are on screen at Dual) and
                                    // homeBackAction would never resolve to ExitSearch.
                                    onSearchClick = {
                                        setFocusedPane(HomePane.ArticleList)
                                        vm.enterSearchScope(HomePane.ArticleList)
                                    },
                                )
                                HomePane.ArticleDetail -> ArticleDetailPane(
                                    vm,
                                    modifier = paneModifier,
                                    onActivated = { setFocusedPane(HomePane.ArticleDetail) },
                                    copyPulse = copyPulse,
                                    onNavigateUp = ::goBack,
                                )
                            }
                        }
                    }
                }
                }
            }
            // Last child of the root Box, so the floating drag chip paints above every pane.
            FeedDragGhost(dragOverlay)
        }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    // A plain Scaffold(snackbarHost = ...) composes the SnackbarHost in the same layer as every
    // other pane content — including the article reader's native WebView, which (like desktop's
    // heavyweight AWT WebView — see app-architecture.md's "Article Reader") always composites
    // above ordinary Compose content in the same window. A Popup instead renders through its own
    // separate window-level layer, the same mechanism KeryxAnchoredPanel/NotificationsBell already
    // rely on to draw above regular content, so the snackbar stays visible with an article open.
    if (snackbarHostState != null) {
        Popup(
            alignment = Alignment.BottomCenter,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        ) {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
    ForegroundAlertSnackbar(notifVm, snackbarHostState, windowFocused)

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

    PendingNotificationActionHost(vm, notifVm, paneLayout, onFocusPane = { setFocusedPane(it) })
}

/**
 * Resolves a notification's next action whose target lives on this screen — hosted at the screen
 * level, outside the bell popup which dismisses on focus loss. `ShowSettingsTab` is resolved by
 * `App` instead (the settings dialog lives there).
 */
@Composable
internal fun PendingNotificationActionHost(
    vm: HomeViewModel,
    notifVm: NotificationCenterViewModel,
    layout: PaneLayout,
    onFocusPane: (HomePane) -> Unit,
) {
    val pending = notifVm.pendingAction ?: return
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
        // Same effect as clicking that feed in the feed list — except at PaneLayout.Single,
        // where that list is a screen of its own and focusing it would navigate backwards; see
        // paneForFeedDetail's own KDoc.
        is AppNotificationAction.ShowFeedDetail -> LaunchedEffect(pending.id) {
            vm.selectFilter(ArticleFilter.Feed(action.feedId))
            onFocusPane(paneForFeedDetail(layout))
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

/**
 * Announces a warning/error in a Snackbar the moment it is raised, wherever the user happens to be.
 *
 * The bell's badge alone is a passive signal — it only reaches a user who is already looking at the
 * pane that hosts it, and these alerts are raised asynchronously by the startup tasks and the
 * background refresh worker, neither of which posts an OS notification (see `error-design.md`).
 * A Snackbar is Material 3's own answer for a non-blocking problem report, and this app's host for
 * it already renders through a `Popup`, so it stays visible above the article reader's WebView at
 * every pane layout and depth.
 *
 * Desktop passes a `null` [hostState] (it has no in-app snackbar convention — see
 * `LocalSnackbarHostState`'s KDoc), which makes this a no-op there.
 *
 * @param windowFocused Whether this window actually has OS focus. Announcing into a window nobody
 *   is looking at would burn the alert — the Snackbar would time out unseen and
 *   [NotificationCenterViewModel.markAlertsSurfaced] would stop it ever coming back. False covers
 *   the app being backgrounded, the notification shade being pulled down, and the settings dialog
 *   (a window of its own) being open; the alert simply waits, and `alertToSurface` being a
 *   `StateFlow` is what lets it still be there when focus returns.
 */
@Composable
internal fun ForegroundAlertSnackbar(
    notifVm: NotificationCenterViewModel,
    hostState: SnackbarHostState?,
    windowFocused: Boolean,
) {
    if (hostState == null) return
    val actionLabel = stringResource(Res.string.notification_snackbar_action)
    LaunchedEffect(hostState, windowFocused) {
        if (!windowFocused) return@LaunchedEffect
        notifVm.alertToSurface.filterNotNull().collectLatest { alert ->
            // collectLatest: a newer alert cancels whatever is showing and replaces it, matching
            // Material 3's one-Snackbar-at-a-time rule. The replacement's markAlertsSurfaced()
            // covers the cancelled one too, so nothing is left to re-announce itself later.
            val act = notificationRowAction(
                alert,
                onRequestHostAction = { notifVm.requestAction(alert) },
                onNavigated = {},
            )
            val result = hostState.showSnackbar(
                message = alert.message,
                actionLabel = actionLabel.takeIf { act != null },
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            // After, not before: marking first would burn an alert whose Snackbar is cancelled a
            // moment later by a lost window focus, leaving it announced but never actually seen.
            notifVm.markAlertsSurfaced()
            if (result == SnackbarResult.ActionPerformed) act?.invoke()
        }
    }
}

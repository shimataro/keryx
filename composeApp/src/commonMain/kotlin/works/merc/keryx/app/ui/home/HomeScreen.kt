package works.merc.keryx.app.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import works.merc.keryx.app.core.DRAWER_SHEET_END_INSET
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
    // Whether the expanded search bar is open — see homeBackAction's own KDoc.
    val searchBarVisible by vm.searchBarVisible.collectAsStateSafe(false)
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
    val articleSwipeNavigation = rememberArticleSwipeNavigation(vm)
    // Bumped by goBack() whenever shouldFlashReturnedArticle says so; ArticleListPane threads it
    // down to the returned-to article's own row, which plays a one-shot ripple so the user can
    // tell where they were reading (see ListRowChrome.kt's playPulseRipple).
    var articleReturnRipplePulse by remember { mutableStateOf(0) }
    // Bumped by the F2(Enter)/Delete feed-list shortcuts; FeedListPane observes these and resolves
    // the currently selected filter (feed/folder/tag) against its own already-collected rows to
    // trigger the same rename/edit and delete/unsubscribe dialogs the context menu uses.
    var feedListRenameRequestId by remember { mutableStateOf(0) }
    var feedListDeleteRequestId by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var focusedPane by remember { mutableStateOf(vm.getInitialFocusedPane()) }
    // Hoisted (not NarrowPaneRow's own internal default) so it isn't recreated across a
    // Triple<->narrow layout flip — declared outside BoxWithConstraints below, alongside
    // drawerState.
    val paneState = rememberSaveableStateHolder()
    // The feed list is a modal drawer at every narrow PaneLayout (see feedListIsDrawer). Hoisted
    // here — outside BoxWithConstraints, like paneState above — so it isn't recreated across a
    // Triple<->narrow layout flip, and deliberately NOT rememberSaveable: an overlay must not be
    // restored as "the screen you were on" after process death, unlike focusedPane (which is also
    // a persisted setting shared with PaneLayout.Triple, where there is no drawer to restore).
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Two separate flags, one per pane that can host a text input — not a single shared
    // `textInputFocused` — because at PaneLayout.Dual (depth <= 2) FeedListPane and ArticleListPane
    // are both on screen at once, and a single `var` would let one pane's `null` (e.g. its field
    // unmounting) clobber the other's still-current value. See FeedListPane's/ArticleListPane's own
    // DisposableEffect for why a pane reliably reports `null` when it unmounts. Each holds a
    // HomeTextInput? rather than a plain Boolean — see KeyboardNav.kt's own KDoc on that type —
    // because ↓/↑ need to know *which* field is focused, not just that one is.
    var feedListTextInput by remember { mutableStateOf<HomeTextInput?>(null) }
    var articleListTextInput by remember { mutableStateOf<HomeTextInput?>(null) }
    // Non-null while a text input in either pane holds focus — the feed list's search field (or a
    // row's inline name editor), or the article list's own search field at a narrow layout. Only
    // one of the two panes ever hosts a text input at PaneLayout.Triple's own dedicated search
    // field (the other is FeedListPane's inline row editor, which is mutually exclusive with a
    // narrow layout's ArticleListPane search field since the two panes host different content at
    // different layouts) — arbitrary precedence here would only matter if both were somehow
    // non-null at once, which never happens in practice.
    val focusedTextInput = feedListTextInput ?: articleListTextInput
    // True while a text input in either pane holds focus, so the root keyboard shortcuts step aside
    // and let typed letters/left-right/arrows reach it (they'd otherwise be swallowed by
    // homeKeyboardShortcuts) — ↓/↑ are the one exception; see KeyboardNav.kt's own KDoc.
    val textInputFocused = focusedTextInput != null
    // Hoists the value of `paneLayoutFor(maxWidth)` from the `BoxWithConstraints` scope below so
    // that code outside that scope — `focusSearch()`, `goBack()`, keyboard shortcuts, and
    // `PendingNotificationActionHost` — can read the current layout. Initialized to Triple so
    // desktop is already correct on the very first frame, before `BoxWithConstraints` has measured
    // anything.
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
    // Latches true on the first hardware KeyDown homeKeyboardShortcuts observes (see
    // KeyboardNav.kt's onKeyboardEngaged) and never resets — gates the Android keyboard-focus ring
    // (LocalKeyboardEngaged, provided below) so a touch-only session never shows a focus indicator
    // meant for keyboard navigation.
    var keyboardEngaged by remember { mutableStateOf(false) }
    // Whether the feed-list drawer is actually open *and* currently rendered as a drawer — plain
    // drawerState.isOpen is not enough on its own: drawerState is hoisted to survive a
    // Triple<->narrow layout flip (see its own comment above), so a drawer left open at a narrow
    // layout and then rotated into Triple would otherwise still read as "open" with nothing on
    // screen to back that up. Used wherever "is the feed list the thing the user is keyboard-
    // interacting with right now" matters: pane focus (below), and the F2/Delete feed-list
    // shortcuts.
    val feedDrawerOpen = feedListIsDrawer(paneLayout) && drawerState.isOpen
    // The single pane keyboard input (arrow-key pane navigation, J/K, F2/Delete, and the
    // focus-ring/dimming shown on its selected row) actually targets right now — see
    // HomePaneLayout.kt's keyboardPaneFor for why this, and not focusedPane or feedDrawerOpen
    // alone, is the one value every one of those call sites should read.
    val keyboardPane = keyboardPaneFor(focusedPane, feedDrawerOpen)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current

    fun setFocusedPane(pane: HomePane) {
        if (pane == focusedPane) return
        focusedPane = pane
        vm.setFocusedPane(pane)
    }

    // A pane's own onActivated always returns real Compose focus to the root Box, on top of
    // whatever it does to focusedPane — a tap on a row/button anywhere in a pane is this app's one
    // mechanism for moving focus off a text field (the search field, a row's inline name editor)
    // without a Compose-level clearFocus()/moveFocus() call (there is no equivalent for "focus the
    // nearest focusable ancestor" here — see KeyboardNav.kt's own KDoc). Reads focusRequester
    // directly rather than taking it as a parameter, since the root Box below is always composed
    // for the whole lifetime of this screen. Whichever field actually still wants focus after this
    // frame (the search field's own pendingSearchFocus latch — see FeedListPane's/
    // ArticleListPane's own LaunchedEffect) simply requests it again after this composes, which
    // wins because it runs later in the same frame.
    fun returnKeyboardFocusToRoot() = focusRequester.requestFocus()
    fun activatePane(pane: HomePane) {
        returnKeyboardFocusToRoot()
        setFocusedPane(pane)
    }

    // ↓/↑ reach here even while a text field holds focus (KeyboardNav.kt's own guard lets them
    // through — see its KDoc) — used by onUp/onDown below only when focusedTextInput is
    // HomeTextInput.SearchField. Descends into the results list rather than moving whatever
    // selection keyboardPane would otherwise resolve to: at PaneLayout.Triple the field lives in
    // FeedListPane's own sidebar, where keyboardPane still reads FeedList, but moving *that*
    // selection would move the feed-list cursor instead of the article the user is actually
    // searching through.
    fun moveArticleSelectionFromSearchField(delta: Int) {
        returnKeyboardFocusToRoot()
        setFocusedPane(HomePane.ArticleList)
        if (delta < 0) vm.selectPrevious() else vm.selectNext()
    }

    // At a narrow PaneLayout, focusedPane doubles as the navigation stack's depth cursor (see
    // HomePane's KDoc) — one step back is just the previous ordinal, with no separate depth state
    // to keep in sync. See homeBackAction's own KDoc for why closing the expanded search bar is
    // resolved as a distinct action rather than always popping the pane stack.
    fun goBack() {
        when (homeBackAction(paneLayout, focusedPane.ordinal + 1, searchBarVisible)) {
            HomeBackAction.CloseSearchBar -> vm.setSearchBarVisible(false)
            HomeBackAction.PopPane -> {
                if (shouldFlashReturnedArticle(paneLayout, focusedPane)) articleReturnRipplePulse++
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
        // Opens the bar at a narrow layout (a no-op at Triple, where HomeScreen's own
        // LaunchedEffect(layout) below keeps it open already) and moves focusedPane onto whichever
        // pane hosts the editable field, matching initialPaneFor's own narrow-layout clamp: at
        // Triple the field stays in FeedListPane's sidebar; at a narrow layout it lives in
        // ArticleListPane's own expanded search bar instead (see FeedListPane's own KDoc).
        vm.setSearchBarVisible(true)
        setFocusedPane(if (paneLayout == PaneLayout.Triple) HomePane.FeedList else HomePane.ArticleList)
        vm.requestSearchFocus()
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
    // contentWindowInsets = WindowInsets(0): each pane applies its own inset instead of one
    // consumed here — see KeryxPaneTopBar's Android `actual` (top), FeedListPane's and
    // ArticleListPane's LazyColumn `contentPadding` (bottom), and the horizontal inset applied to
    // this root Box below. This is what lets a narrow layout's modal navigation drawer scrim
    // reach all the way to the status/navigation bars instead of stopping at this padding's edge.
    Scaffold(contentWindowInsets = WindowInsets(0)) { _ ->
        CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .homeKeyboardShortcuts(
                    textInputFocused = textInputFocused,
                    onEscape = { dragOverlay.cancel() },
                    // Every handler below reads keyboardPane, not focusedPane directly — it already
                    // folds in the drawer-open precedence (see HomePaneLayout.kt's keyboardPaneFor),
                    // so there is exactly one branch to write per handler instead of a
                    // feedDrawerOpen-guarded when(focusedPane) at each one. onUp/onDown additionally
                    // branch on focusedTextInput first — see KeyboardNav.kt's own KDoc for why they
                    // alone still reach here while a text field holds focus.
                    onUp = {
                        when (focusedTextInput) {
                            HomeTextInput.SearchField -> moveArticleSelectionFromSearchField(-1)
                            HomeTextInput.RowNameEditor -> {}
                            null -> when (keyboardPane) {
                                HomePane.FeedList -> moveFeedSelection(-1)
                                HomePane.ArticleList -> vm.selectPrevious()
                                // The article body scrolls inside the native WebView itself now
                                // (see plan doc html-webview-os-wobbly-hammock.md), so there's no
                                // Compose ScrollState left here to drive with the keyboard.
                                HomePane.ArticleDetail -> {}
                            }
                        }
                    },
                    onDown = {
                        when (focusedTextInput) {
                            HomeTextInput.SearchField -> moveArticleSelectionFromSearchField(1)
                            HomeTextInput.RowNameEditor -> {}
                            null -> when (keyboardPane) {
                                HomePane.FeedList -> moveFeedSelection(1)
                                HomePane.ArticleList -> vm.selectNext()
                                HomePane.ArticleDetail -> {}
                            }
                        }
                    },
                    onLeft = {
                        // FeedList -> {} covers the drawer-open case too (keyboardPane resolves to
                        // FeedList whenever the drawer is open) — there is nothing further left to
                        // move to from the feed list either way.
                        when (keyboardPane) {
                            HomePane.FeedList -> {}
                            HomePane.ArticleList -> setFocusedPane(HomePane.FeedList)
                            HomePane.ArticleDetail -> setFocusedPane(HomePane.ArticleList)
                        }
                    },
                    onRight = {
                        if (feedDrawerOpen) {
                            // Closing the drawer *is* "advance to the article list" at a narrow
                            // layout — mirrors selectFilterFromRow's own onSelectionAdvance. Also
                            // advances focusedPane itself now (unlike before keyboardPaneFor
                            // existed): at PaneLayout.Dual, ArticleListPane's own hamburger
                            // (onOpenDrawer) can open the drawer without touching focusedPane, which
                            // can therefore still be ArticleDetail (both panes stay on screen
                            // together at Dual) from an earlier visit — closing the drawer without
                            // this would leave the article list unable to receive ↑/↓ afterwards.
                            setFocusedPane(HomePane.ArticleList)
                            scope.launch { drawerState.close() }
                        } else {
                            when (focusedPane) {
                                HomePane.FeedList -> {
                                    if (vm.selectedArticle.value == null) vm.currentArticles().firstOrNull()?.let { vm.selectArticle(it) }
                                    setFocusedPane(HomePane.ArticleList)
                                }
                                HomePane.ArticleList -> setFocusedPane(HomePane.ArticleDetail)
                                HomePane.ArticleDetail -> {}
                            }
                        }
                    },
                    onNextArticle = { vm.selectNext() },
                    onPreviousArticle = { vm.selectPrevious() },
                    onFeedListRename = { if (keyboardPane == HomePane.FeedList) feedListRenameRequestId++ },
                    onFeedListDelete = { if (keyboardPane == HomePane.FeedList) feedListDeleteRequestId++ },
                    onSearch = { focusSearch() },
                    onKeyboardEngaged = { keyboardEngaged = true },
                ),
        ) {
            // Wraps both the Triple and narrow-layout branches below (and FeedDragGhost) in one
            // place, rather than duplicating the provide call in each branch, where it would be
            // easy to add a new branch later and forget it.
            CompositionLocalProvider(LocalKeyboardEngaged provides keyboardEngaged) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val layout = paneLayoutFor(maxWidth)
                LaunchedEffect(layout) {
                    paneLayout = layout
                    // At Triple, FeedListPane's own field is permanent, so the bar is always
                    // "open" there. Dropping below Triple keeps it open only if there's a query to
                    // keep showing — otherwise an idle bar would reappear at a narrower width for
                    // no reason (see HomeViewModel.searchBarVisible's own KDoc).
                    vm.setSearchBarVisible(layout == PaneLayout.Triple || vm.searchQuery.value.isNotEmpty())
                }
                // Clamps focusedPane for a narrow layout exactly once, on the first frame with a
                // real (post-layout) width — a transient pre-layout frame reports maxWidth == 0.dp,
                // which paneLayoutFor resolves to Single regardless of the eventual layout, and
                // clamping against that would misfire even on desktop. See initialPaneFor's own
                // KDoc for why restoring straight into ArticleDetail at a narrow layout is wrong;
                // this effect fires whenever maxWidth changes, but the body is gated by
                // initialPaneClamped so it only runs once per composition instance.
                LaunchedEffect(maxWidth) {
                    if (!initialPaneClamped && maxWidth > 0.dp) {
                        initialPaneClamped = true
                        val clamped = initialPaneFor(layout, focusedPane)
                        if (clamped != focusedPane) setFocusedPane(clamped)
                        // A one-time nudge into the drawer for the dead end it exists to remove —
                        // see shouldAutoOpenFeedDrawer's own KDoc. Runs in the same once-per-session
                        // effect as the clamp above, so a later resize/rotation/feed deletion can't
                        // re-trigger it.
                        if (shouldAutoOpenFeedDrawer(layout, vm.cloudConnected.value, vm.hasAnyFeed())) {
                            drawerState.open()
                        }
                    }
                }
                // BackHandler is always called (its own `enabled` gates the actual interception).
                // homeBackAction is false (None) at PaneLayout.Triple (visiblePanes never changes
                // there — desktop's WINDOW_MIN_WIDTH never resolves to anything else, see
                // TRIPLE_PANE_MIN_WIDTH's KDoc), so the app's default (OS back gesture /
                // Alt+F4-equivalent) is left alone there. It is also None at PaneLayout.Dual depth
                // 1->2 while the search bar is closed (visiblePanes' sliding window shows the same
                // two panes at both depths), so a back press that would produce no visible change
                // falls through instead of being swallowed — but CloseSearchBar still applies there
                // while the bar is open, since closing it always changes what's on screen.
                val backAction = homeBackAction(layout, focusedPane.ordinal + 1, searchBarVisible)
                // The drawer's own back handling lives in ModalDrawerSheet(drawerState = ...) ->
                // PredictiveBackHandler(enabled = drawerState.isOpen) — this gate makes this
                // BackHandler stand down while it's open, independent of registration order, so
                // the drawer always wins a back press over whatever's behind it (the only case
                // where the two could otherwise race is Dual + the search bar open + the drawer
                // open).
                BackHandler(enabled = backAction != HomeBackAction.None && !drawerState.isOpen) { goBack() }

                if (layout == PaneLayout.Triple) {
                    // Dual/Triple keep the selection highlight: the selected row's pane stays on
                    // screen alongside the pane it opened. (Single's narrow-only suppression lives
                    // in the drawer branch below, alongside its own drawer-content override.)
                    CompositionLocalProvider(LocalRowSelectionVisible provides true) {
                    val dividerWidth = PANE_DIVIDER_WIDTH.dp
                    // coerceAtLeast(0.dp): with WINDOW_MIN_WIDTH >= TRIPLE_PANE_MIN_WIDTH, this
                    // shouldn't go negative in steady state, but a transient pre-layout frame
                    // (maxWidth == 0) must not produce a negative Dp, which Modifier.width() rejects.
                    val availableForPanes = (maxWidth - dividerWidth * 2 - DETAIL_PANE_MIN_WIDTH.dp).coerceAtLeast(0.dp)
                    val (displayedFeedWidth, displayedArticleWidth) =
                        triplePaneWidths(availableForPanes, feedListPaneWidth.dp, articleListPaneWidth.dp)

                    Row(Modifier.fillMaxSize()) {
                        FeedListPane(
                            vm,
                            focused = keyboardPane == HomePane.FeedList && keyboardNavActive,
                            dragOverlay = dragOverlay,
                            onActivated = { activatePane(HomePane.FeedList) },
                            modifier = Modifier.width(displayedFeedWidth),
                            onAddFeedClick = { showAddFeed = true },
                            onTextInputFocusChange = { feedListTextInput = it },
                            renameSelectedRequestId = feedListRenameRequestId,
                            deleteSelectedRequestId = feedListDeleteRequestId,
                        )
                        ResizableDivider(onDrag = { deltaPx ->
                            vm.setFeedListPaneWidth(feedListPaneWidth + with(density) { deltaPx.toDp().value })
                        })
                        ArticleListPane(
                            vm,
                            focused = keyboardPane == HomePane.ArticleList && keyboardNavActive,
                            onActivated = { activatePane(HomePane.ArticleList) },
                            modifier = Modifier.width(displayedArticleWidth),
                            notifVm = notifVm,
                            onAddFeedClick = { showAddFeed = true },
                        )
                        ResizableDivider(onDrag = { deltaPx ->
                            vm.setArticleListPaneWidth(articleListPaneWidth + with(density) { deltaPx.toDp().value })
                        })
                        ArticleDetailPane(
                            vm,
                            modifier = Modifier.weight(1f),
                            onActivated = { activatePane(HomePane.ArticleDetail) },
                            copyPulse = copyPulse,
                        )
                    }
                    }
                } else {
                    // Single/Dual: the feed list is a modal navigation drawer rather than an
                    // on-screen pane (see HomePaneLayout.kt's feedListIsDrawer) — no resizable
                    // dividers (nothing to drag on a phone/narrow window) and so no persisted pane
                    // widths for the two panes NarrowPaneRow does show. Where both are on screen
                    // (Dual) it sizes them the same way the Triple branch above does: the article
                    // list at a fixed width capped at its own default (dualPaneArticleListWidth),
                    // the reader taking whatever is left. See HomePaneLayout.kt's visiblePanes for
                    // what those panes are at each depth, and NarrowPaneRow for why they're emitted
                    // from fixed positions there rather than iterated over (it is what preserves
                    // each pane's scroll position across the stack's comings and goings).
                    val visible = visiblePanes(layout, focusedPane.ordinal + 1)
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        // ModalNavigationDrawer claims the whole root Box for an edge-swipe-to-open
                        // gesture, which would otherwise fight the reader's own swipe-to-navigate
                        // (ArticleSwipeNav) for the same horizontal drag. Open: always enabled, so
                        // a swipe-to-dismiss and a scrim tap both still close it. Closed: only
                        // where the reader isn't on screen to swipe (Single's article list depth) —
                        // Dual and Single's article-detail depth open the drawer via the hamburger
                        // button only. dragOverlay.item == null additionally guards a feed drag in
                        // progress inside the drawer from being misread as this same gesture.
                        gesturesEnabled = dragOverlay.item == null &&
                            (drawerState.isOpen || HomePane.ArticleDetail !in visible),
                        drawerContent = {
                            // The drawer's whole purpose is showing *where you are* — the current
                            // feed/folder/tag/quick-filter selection must stay highlighted while
                            // it's open, exactly like Gmail highlighting the current label. The
                            // Single-only suppression above doesn't apply here: this is an overlay
                            // the user is looking at right now, not a screen navigated away from.
                            CompositionLocalProvider(LocalRowSelectionVisible provides true) {
                                ModalDrawerSheet(
                                    drawerState = drawerState,
                                    modifier = Modifier.width(
                                        (maxWidth - DRAWER_SHEET_END_INSET.dp).coerceAtLeast(0.dp),
                                    ),
                                ) {
                                    FeedListPane(
                                        vm,
                                        focused = keyboardPane == HomePane.FeedList && keyboardNavActive,
                                        dragOverlay = dragOverlay,
                                        onActivated = { returnKeyboardFocusToRoot() },
                                        onAddFeedClick = { showAddFeed = true },
                                        onTextInputFocusChange = { feedListTextInput = it },
                                        renameSelectedRequestId = feedListRenameRequestId,
                                        deleteSelectedRequestId = feedListDeleteRequestId,
                                        onSelectionAdvance = {
                                            // Also advances focusedPane, not just the drawer: at
                                            // PaneLayout.Dual, closing the drawer without this could
                                            // leave focusedPane stuck at whatever it was before the
                                            // drawer opened (e.g. ArticleDetail), and the article
                                            // list unable to receive ↑/↓ afterwards. Single always
                                            // resolves ArticleList already (the drawer is only
                                            // reachable from that depth there), so this is a no-op there.
                                            setFocusedPane(HomePane.ArticleList)
                                            scope.launch { drawerState.close() }
                                        },
                                    )
                                }
                            }
                        },
                    ) {
                        // Single: tapping a row navigates away from it (drills into the article
                        // list, or the article detail), so a lingering selection highlight there
                        // would mark a row the user can no longer see — see
                        // LocalRowSelectionVisible's own KDoc. Dual keeps it: both panes it shows
                        // stay on screen throughout.
                        CompositionLocalProvider(LocalRowSelectionVisible provides (layout != PaneLayout.Single)) {
                        NarrowPaneRow(visible, maxWidth, Modifier.fillMaxSize(), paneState) { pane, paneModifier ->
                            when (pane) {
                                HomePane.FeedList ->
                                    error("The feed list is a drawer at a narrow PaneLayout, never a NarrowPaneRow pane.")
                                HomePane.ArticleList -> ArticleListPane(
                                    vm,
                                    focused = keyboardPane == HomePane.ArticleList && keyboardNavActive,
                                    onActivated = { activatePane(HomePane.ArticleList) },
                                    modifier = paneModifier,
                                    notifVm = notifVm,
                                    // No-op at PaneLayout.Dual — mirrors ArticleListPane's own
                                    // KDoc on this parameter ("No-op at PaneLayout.Triple, where
                                    // every pane is already visible and there is nowhere to advance
                                    // to"), which applies here just as much: Dual's visiblePanes
                                    // never changes with depth (both panes are already on screen),
                                    // so advancing focusedPane to ArticleDetail here would only
                                    // leave the tapped article-list row unable to receive ↑/↓
                                    // afterwards with nothing to show for it on screen.
                                    onSelectionAdvance = {
                                        if (layout == PaneLayout.Single) setFocusedPane(HomePane.ArticleDetail)
                                    },
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    onExitSearch = ::goBack,
                                    onTextInputFocusChange = { articleListTextInput = it },
                                    // Only while the bar is closed: once already open, there is
                                    // nowhere further to advance to (see ArticleListTopBar's own
                                    // KDoc on onSearchClick). Doesn't advance the navigation stack —
                                    // the field lives on this same pane. setFocusedPane is still
                                    // required at PaneLayout.Dual: the search icon's own onClick
                                    // never reaches paneActivation (a separate, unchained click
                                    // handler — see ArticleListPaneContent), so without this,
                                    // focusedPane could still be ArticleDetail (both panes are on
                                    // screen at Dual) and homeBackAction would never resolve to
                                    // CloseSearchBar.
                                    onSearchClick = {
                                        setFocusedPane(HomePane.ArticleList)
                                        vm.setSearchBarVisible(true)
                                        vm.requestSearchFocus()
                                    },
                                    returnRipplePulse = articleReturnRipplePulse,
                                    onAddFeedClick = { showAddFeed = true },
                                )
                                HomePane.ArticleDetail -> ArticleDetailPane(
                                    vm,
                                    modifier = paneModifier,
                                    onActivated = { activatePane(HomePane.ArticleDetail) },
                                    copyPulse = copyPulse,
                                    // Only where the article list isn't on screen beside this one
                                    // to return to — PaneLayout.Single's article-detail depth. At
                                    // Dual the reader is a permanent neighbor of the article list,
                                    // like Gmail's own tablet reading pane, with no back button —
                                    // swipeNavigation below is what still lets it move between
                                    // articles there.
                                    onNavigateUp = if (HomePane.ArticleList !in visible) ::goBack else null,
                                    swipeNavigation = articleSwipeNavigation,
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

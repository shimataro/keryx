import KeryxShared
import SwiftUI

/// The 3-pane Home screen: sidebar (`FeedListView`) / article list (`ArticleListView`) / reader
/// (`ArticleDetailView`). Desktop/macOS is unconditionally the 3-pane steady state (`external-spec.md`
/// §9), so — unlike the Compose app, which also serves narrower Android widths — this container has
/// no narrower-layout branch to reproduce.
struct HomeView: View {
    let home: HomeObservable
    let sidebarDialogs: SidebarDialogState
    let notifications: NotificationCenterObservable
    let preferences: PreferencesObservable

    @FocusState private var focusedPane: HomeFocusedPane?
    @State private var feedListWidthSaveTask: Task<Void, Never>?
    @State private var articleListWidthSaveTask: Task<Void, Never>?
    #if os(macOS)
    @State private var contextMenuSelectionTracker = ContextMenuSelectionTracker()
    #endif

    var body: some View {
        NavigationSplitView {
            FeedListView(home: home, dialogs: sidebarDialogs, focusedPane: $focusedPane)
                .navigationSplitViewColumnWidth(
                    min: CGFloat(ConstantsKt.FEED_LIST_PANE_MIN_WIDTH),
                    ideal: CGFloat(preferences.localSettings.feedListPaneWidth),
                    max: CGFloat(ConstantsKt.FEED_LIST_PANE_MAX_WIDTH)
                )
                .onSizeChanged { size in
                    // TODO: Replace onSizeChanged with .onGeometryChange when deployment target is macOS 15+.
                    debounceSave(&feedListWidthSaveTask) { preferences.controller.setFeedListPaneWidth(width: Double(size.width)) }
                }
        } content: {
            ArticleListView(home: home, notifications: notifications, dialogs: sidebarDialogs, focusedPane: $focusedPane)
                .navigationSplitViewColumnWidth(
                    min: CGFloat(ConstantsKt.ARTICLE_LIST_PANE_MIN_WIDTH),
                    ideal: CGFloat(preferences.localSettings.articleListPaneWidth),
                    max: CGFloat(ConstantsKt.ARTICLE_LIST_PANE_MAX_WIDTH)
                )
                .onSizeChanged { size in
                    // TODO: Replace onSizeChanged with .onGeometryChange when deployment target is macOS 15+.
                    debounceSave(&articleListWidthSaveTask) { preferences.controller.setArticleListPaneWidth(width: Double(size.width)) }
                }
        } detail: {
            ArticleDetailView(home: home, preferences: preferences, focusedPane: $focusedPane)
        }
        .onKeyPress { press in handleKeyPress(press) }
        .task {
            await home.startObserving()
        }
        .focusedSceneValue(\.homeFocusedPane, focusedPane)
        #if os(macOS)
        .environment(\.contextMenuSelectionTracker, contextMenuSelectionTracker)
        #endif
        .onAppear {
            if focusedPane == nil {
                focusedPane = HomeView.focusedPane(fromRaw: preferences.localSettings.lastFocusedPane)
            }
            #if os(macOS)
            contextMenuSelectionTracker.startMonitoring()
            #endif
        }
        #if os(macOS)
        .onDisappear {
            contextMenuSelectionTracker.stopMonitoring()
        }
        #endif
        // Mirrors into `HomeObservable` so `HomeCommands.menuState` (a different `View` entirely,
        // with no `@FocusState` of its own) can gate the Feed/Article menu's bare-key accelerators
        // and `feedActionsEnabled`-style items the same way `HomeShortcutsKt.homeShortcutFor` does.
        .onChange(of: focusedPane, initial: true) { _, pane in
            home.textInputFocused = pane == .search
        }
        // Restored on next launch by the `.onAppear` above — matches Compose's own
        // `HomeLayoutViewModel.getInitialFocusedPane`/`setFocusedPane`. `.search` has no Compose
        // `HomePane` counterpart (the field lives in the sidebar, not a pane of its own here), so it
        // is never persisted — the previously saved real pane is simply left in place instead.
        .onChange(of: focusedPane) { _, pane in
            if let raw = HomeView.rawValue(for: pane) {
                preferences.controller.setLastFocusedPane(pane: raw)
            }
        }
    }

    /// A debounced save — cancels any pending save for the same pane and starts a fresh one, so
    /// only the width the divider settles on for `PANE_WIDTH_PERSIST_DEBOUNCE_MS` actually gets
    /// written, matching Compose's own `HomeLayoutViewModel` (`debounce(PANE_WIDTH_PERSIST_DEBOUNCE_MS)`).
    private func debounceSave(_ task: inout Task<Void, Never>?, _ save: @escaping () -> Void) {
        task?.cancel()
        task = Task {
            try? await Task.sleep(for: .milliseconds(Int(ConstantsKt.PANE_WIDTH_PERSIST_DEBOUNCE_MS)))
            guard !Task.isCancelled else { return }
            save()
        }
    }

    /// Compose's own `HomePane` names (`FeedList`/`ArticleList`/`ArticleDetail`) — the raw values
    /// `lastFocusedPane` is stored as, shared with desktop's `HomeLayoutViewModel`. `.search` has no
    /// counterpart, since Compose's search field lives inside the sidebar pane rather than being a
    /// distinct pane of the 3-pane layout.
    private static func rawValue(for pane: HomeFocusedPane?) -> String? {
        switch pane {
        case .feedList: return "FeedList"
        case .articleList: return "ArticleList"
        case .reader: return "ArticleDetail"
        case .search, nil: return nil
        }
    }

    private static func focusedPane(fromRaw raw: String?) -> HomeFocusedPane {
        switch raw {
        case "FeedList": return .feedList
        case "ArticleList": return .articleList
        case "ArticleDetail": return .reader
        default: return .articleList
        }
    }

    private func handleKeyPress(_ press: KeyPress) -> KeyPress.Result {
        guard let (key, modifiers) = mapKey(press) else { return .ignored }
        guard let shortcut = HomeShortcutsKt.homeShortcutFor(
            key: key,
            modifiers: modifiers,
            // The sidebar's search field is a plain `TextField` reporting its focus through this
            // same `focusedPane`, not `.searchable` (which cannot report its focus before macOS
            // 15) — see `FeedListView.searchField`.
            textInputFocused: focusedPane == .search,
            // Ctrl+Shift+R belongs to the Feed menu's "Refresh selected feed" item on desktop
            // Compose (`AppMenuTree.kt`), not the sidebar-refresh key touch-only platforms bind it
            // to — see `HomeCommands.swift`'s `refreshSelectedFeed`.
            refreshListAvailable: false,
            isMacOs: true
        ) else { return .ignored }

        switch shortcut {
        case .escape:
            // Cancelling an in-progress drag (matching Compose's own `onEscape`, `HomeScreen.kt`)
            // isn't wired up here — that state (`draggingItem`/`activeBoundary`) lives in
            // `FeedListView`, not `HomeView` — so this never actually does anything yet. Reporting
            // `.ignored` (rather than `.handled` for a key that does nothing) lets the key still
            // reach macOS's own responder chain, e.g. to exit full screen. Does not hide search
            // results either way: a hidden bar with the query still in the field would show stale
            // results reappearing on the next keystroke.
            return .ignored
        case .up:
            switch focusedPane {
            case .feedList: moveFeedListSelection(by: -1)
            // The native WebView reader handles its own scrolling once it holds real focus (see
            // "Article Reader (native WebView)" in app-architecture.md) — never change the
            // selection out from under it.
            case .reader: return .ignored
            // Descends into the results list rather than moving the sidebar's own selection, even
            // though the field visually sits in the sidebar — matches Compose's own
            // `moveArticleSelectionFromSearchField` (`HomeScreen.kt`).
            case .search: moveArticleSelectionFromSearchField(by: -1)
            default: home.viewModel.selectPrevious()
            }
        case .down:
            switch focusedPane {
            case .feedList: moveFeedListSelection(by: 1)
            case .reader: return .ignored
            case .search: moveArticleSelectionFromSearchField(by: 1)
            default: home.viewModel.selectNext()
            }
        case .nextArticle:
            home.viewModel.selectNext()
        case .previousArticle:
            home.viewModel.selectPrevious()
        case .left:
            switch focusedPane {
            case .articleList: focusedPane = .feedList
            case .reader: focusedPane = .articleList
            default: return .ignored
            }
        case .right:
            switch focusedPane {
            case .feedList:
                if home.selectedArticle == nil, let first = home.viewModel.currentArticles().first {
                    home.viewModel.selectArticle(article: first)
                }
                focusedPane = .articleList
            case .articleList:
                focusedPane = .reader
            default:
                return .ignored
            }
        case .pageUp, .pageDown, .home, .end:
            // Compose only routes these to its own Compose-drawn fallback reader (Linux arm64 with
            // no native web view). The Apple app always has a native WebView, which scrolls itself.
            return .ignored
        case .search:
            home.viewModel.setSearchBarVisible(visible: true)
            home.viewModel.requestSearchFocus()
        case .renameFeedListItem:
            guard focusedPane == .feedList else { return .ignored }
            requestRename()
        case .deleteFeedListItem:
            guard focusedPane == .feedList else { return .ignored }
            requestDelete()
        case .refreshList:
            home.viewModel.pullToRefresh()
        }
        return .handled
    }

    /// Moves the article selection from the search field (↓/↑ reach here even while it holds
    /// focus — see `HomeShortcutsKt.homeShortcutFor`'s own `textInputFocused` branch) and hands
    /// keyboard focus to the article list, so the results can keep being browsed with J/K
    /// afterwards. Mirrors Compose's own `moveArticleSelectionFromSearchField` (`HomeScreen.kt`).
    private func moveArticleSelectionFromSearchField(by delta: Int) {
        focusedPane = .articleList
        if delta < 0 { home.viewModel.selectPrevious() } else { home.viewModel.selectNext() }
    }

    /// Moves the sidebar's own selection by `delta` positions in `buildOrderedFeedListRows`'
    /// visual order (`FeedListModel.kt`), the same order the sidebar itself renders in.
    private func moveFeedListSelection(by delta: Int) {
        let orderedRows = FeedListModelKt.buildOrderedFeedListRows(
            tags: home.tags,
            folders: home.folders,
            feeds: home.feeds,
            collapsedFolderIds: home.collapsedFolderIds,
            expandedTagIds: home.expandedTagIds,
            feedTagMap: home.feedTagMap
        )
        guard let next = FeedListModelKt.nextFeedListRow(
            current: home.selectedRowInstance,
            orderedRows: orderedRows,
            delta: Int32(delta)
        ) else { return }
        home.viewModel.selectFilter(filter: next.filter, instance: next)
    }

    private func requestRename() {
        guard let target = FeedListModelKt.resolveFeedListSelectionTarget(
            filter: home.filter, feeds: home.feeds, folders: home.folders, tags: home.tags
        ) else { return }
        switch onEnum(of: target) {
        case .feed(let f): sidebarDialogs.renamingFeed = f.feed
        case .folder(let f): sidebarDialogs.renamingFolder = f.folder
        case .tag(let t): sidebarDialogs.renamingTag = t.tag
        }
    }

    private func requestDelete() {
        guard let target = FeedListModelKt.resolveFeedListSelectionTarget(
            filter: home.filter, feeds: home.feeds, folders: home.folders, tags: home.tags
        ) else { return }
        switch onEnum(of: target) {
        case .feed(let f): sidebarDialogs.unsubscribingFeed = f.feed
        case .folder(let f): sidebarDialogs.deletingFolder = f.folder
        case .tag(let t): sidebarDialogs.deletingTag = t.tag
        }
    }
}

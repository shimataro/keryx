import KeryxShared
import SwiftUI

/// The 3-pane Home screen: sidebar (`FeedListView`) / article list (`ArticleListView`) / reader
/// (`ArticleDetailView`). Desktop/macOS is unconditionally the 3-pane steady state (`external-spec.md`
/// §9), so — unlike the Compose app, which also serves narrower Android widths — this container has
/// no narrower-layout branch of its own. On an iPhone the split view collapses into the system's own
/// stack (sidebar → article list → reader), navigated through `compactColumn`.
struct HomeView: View {
    let home: HomeObservable
    let sidebarDialogs: SidebarDialogState
    let notifications: NotificationCenterObservable
    let settingsNavigation: SettingsNavigation
    let preferences: PreferencesObservable

    @FocusState private var focusedPane: HomeFocusedPane?
    @State private var feedListWidthSaveTask: Task<Void, Never>?
    @State private var articleListWidthSaveTask: Task<Void, Never>?
    @State private var initialFocusApplied = false
    /// The column shown while the split view is collapsed into a single stack (iPhone, compact
    /// width). Driven explicitly rather than left to the sidebar `List`'s own selection: an article
    /// row is a plain button, which never navigates by itself, and re-tapping the current sidebar row
    /// is no selection change at all. The system writes it back on a back button / edge swipe.
    @State private var compactColumn: NavigationSplitViewColumn = .sidebar
    #if os(macOS)
    @State private var contextMenuSelectionTracker = ContextMenuSelectionTracker()
    #else
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    /// Whether the collapsed stack is showing the sidebar itself — never on macOS or at a regular
    /// width, where every column is on screen together.
    private var sidebarIsTopmost: Bool {
        #if os(iOS)
        horizontalSizeClass == .compact && compactColumn == .sidebar
        #else
        false
        #endif
    }

    /// Whether the collapsed stack is showing the article list itself — see `sidebarIsTopmost`.
    private var articleListIsTopmost: Bool {
        #if os(iOS)
        horizontalSizeClass == .compact && compactColumn == .content
        #else
        false
        #endif
    }

    /// The article whose row briefly flashes gray after the reader is popped back to the collapsed
    /// article list — the iOS idiom of a list row fading out its highlight on return, and the role
    /// Android's own neutral ripple pulse (`ripplePulseFor`) plays there. `nil` otherwise.
    @State private var returnFlashId: String?
    private static let returnFlashFadeSeconds = 0.35

    var body: some View {
        NavigationSplitView(preferredCompactColumn: $compactColumn) {
            FeedListView(
                home: home,
                dialogs: sidebarDialogs,
                settingsNavigation: settingsNavigation,
                focusedPane: $focusedPane,
                sidebarIsTopmost: sidebarIsTopmost,
                onOpenArticleList: { compactColumn = .content }
            )
                .navigationSplitViewColumnWidth(
                    min: CGFloat(ConstantsKt.FEED_LIST_PANE_MIN_WIDTH),
                    ideal: CGFloat(preferences.feedListPaneWidth),
                    max: CGFloat(ConstantsKt.FEED_LIST_PANE_MAX_WIDTH)
                )
                .onSizeChanged { size in
                    // TODO: Replace onSizeChanged with .onGeometryChange when deployment target is macOS 15+.
                    debounceSave(&feedListWidthSaveTask) { preferences.controller.setFeedListPaneWidth(width: Double(size.width)) }
                }
        } content: {
            ArticleListView(
                home: home,
                notifications: notifications,
                settingsNavigation: settingsNavigation,
                dialogs: sidebarDialogs,
                focusedPane: $focusedPane,
                articleListIsTopmost: articleListIsTopmost,
                returnFlashId: returnFlashId,
                onOpenArticle: { compactColumn = .detail }
            )
                .navigationSplitViewColumnWidth(
                    min: CGFloat(ConstantsKt.ARTICLE_LIST_PANE_MIN_WIDTH),
                    ideal: CGFloat(preferences.articleListPaneWidth),
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
        .onChange(of: compactColumn) { old, new in
            guard old == .detail, new == .content, articleListIsTopmost,
                  let id = home.selectedArticleId else { return }
            flashReturnedRow(id)
        }
        .modifier(HiddenSplitViewTitle())
        .task {
            await home.startObserving()
        }
        .task {
            await applyInitialFocus()
        }
        #if os(iOS)
        // A search-focus request (⌘F, from the menu bar or `handleKeyPress`) at a compact width
        // first brings forward the article list, where iOS keeps the search field; the list then
        // takes the focus itself (`ArticleListView`'s own `pendingSearchFocus` handler).
        .onChange(of: home.pendingSearchFocus) { _, pending in
            guard pending,
                  let column = CompactSearchNavigation.column(
                      isCompact: horizontalSizeClass == .compact,
                      current: HomeView.compactSearchColumn(compactColumn)
                  ) else { return }
            compactColumn = HomeView.splitViewColumn(column)
        }
        // The one place the URL-copy confirmation is drawn, over every column, so it shows whichever
        // route copied and whatever is on screen.
        .overlay(alignment: .bottom) {
            TransientToast(state: home.copyToast, liftedAbovePill: home.newArticlesPillAtBottom)
        }
        // Mounted on every layout (the detail column is not, at a compact width until an article is
        // opened), so WebKit can start while the app is idle rather than on the first article.
        .task { await ReaderWebViewWarmUp.scheduleOnce() }
        #endif
        .focusedSceneValue(\.homeFocusedPane, focusedPane)
        #if os(macOS)
        .environment(\.contextMenuSelectionTracker, contextMenuSelectionTracker)
        #endif
        #if os(macOS)
        .onAppear {
            contextMenuSelectionTracker.startMonitoring()
        }
        #endif
        #if os(macOS)
        .onDisappear {
            contextMenuSelectionTracker.stopMonitoring()
        }
        #endif
        // Restored on next launch by `applyInitialFocus` — matches Compose's own
        // `HomeLayoutViewModel.getInitialFocusedPane`/`setFocusedPane`. `.search` has no Compose
        // `HomePane` counterpart (the field lives inside another pane, not a pane of its own here), so it
        // is never persisted — the previously saved real pane is simply left in place instead.
        .onChange(of: focusedPane) { _, pane in
            if let raw = HomeView.rawValue(for: pane) {
                preferences.controller.setLastFocusedPane(pane: raw)
            }
        }
    }

    /// Shows `id`'s row in the gray highlight at once, then fades it out.
    private func flashReturnedRow(_ id: String) {
        returnFlashId = id
        Task {
            // Fully on for a moment, while the pop transition settles, before it starts to fade.
            try? await Task.sleep(for: .milliseconds(150))
            withAnimation(.easeOut(duration: HomeView.returnFlashFadeSeconds)) {
                returnFlashId = nil
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
    /// counterpart, since the search field lives inside another pane (the sidebar on macOS and in
    /// Compose, the article list on iOS) rather than being a distinct pane of the 3-pane layout.
    private static func rawValue(for pane: HomeFocusedPane?) -> String? {
        switch pane {
        case .feedList: return "FeedList"
        case .articleList: return "ArticleList"
        case .reader: return "ArticleDetail"
        case .search, nil: return nil
        }
    }

    #if os(iOS)
    /// `NavigationSplitViewColumn` → `CompactSearchNavigation.Column`, which the test bundle can use.
    private static func compactSearchColumn(_ column: NavigationSplitViewColumn) -> CompactSearchNavigation.Column {
        switch column {
        case .sidebar: return .sidebar
        case .content: return .content
        default: return .detail
        }
    }

    private static func splitViewColumn(_ column: CompactSearchNavigation.Column) -> NavigationSplitViewColumn {
        switch column {
        case .sidebar: return .sidebar
        case .content: return .content
        case .detail: return .detail
        }
    }
    #endif

    private static func focusedPane(for pane: InitialHomePane) -> HomeFocusedPane {
        switch pane {
        case .feedList: return .feedList
        case .articleList: return .articleList
        case .articleDetail: return .reader
        }
    }

    /// How long the article list / reader may take to show the restored article before the initial
    /// focus gives up on it and lands on the feed list instead.
    private static let initialFocusTimeout: Duration = .seconds(2)

    /// Gives the pane `HomeViewModel.initialHomePane` picked keyboard focus once it can actually take
    /// it. Assigning `focusedPane` while the target is not yet focusable — the article list still
    /// showing its empty state because `startObserving()` has not delivered the first real rows — is
    /// silently dropped by SwiftUI, which is what left launch with no pane focused at all.
    private func applyInitialFocus() async {
        guard !initialFocusApplied else { return }
        initialFocusApplied = true
        var target = HomeView.focusedPane(for: home.viewModel.initialHomePane)
        // The DB's own answer, since `home.hasFeeds` starts false before its first real emission.
        let expectsFeeds = (try? await home.viewModel.hasAnyFeed())?.boolValue
        var deadline = ContinuousClock.now + HomeView.initialFocusTimeout
        while !initialFocusReady(target, expectsFeeds: expectsFeeds) {
            // Time spent with a sheet up is the user's, not the restored article's: it must not
            // push the target onto the feed-list fallback.
            guard let waited = await waitWhileSheetPresented() else { return }
            deadline += waited
            if ContinuousClock.now >= deadline {
                // The feed list always has a selected row to focus; the restored article never showed up.
                if target == .feedList { break }
                target = .feedList
                continue
            }
            try? await Task.sleep(for: .milliseconds(16))
            if Task.isCancelled { return }
        }
        // The target's view may only be laid out on the next pass, so re-assign until it sticks. A
        // pane that still refuses it (the reader's WebView is an AppKit view, whose focus SwiftUI does
        // not always track — see `RenameTextField`) falls back towards the feed list, so launch never
        // ends with no pane focused.
        let fallbacks: [HomeFocusedPane] = [.reader, .articleList, .feedList]
        for candidate in fallbacks.drop(while: { $0 != target }) {
            for _ in 0..<3 {
                // Checked before every assignment, since a sheet can open across the `yield`.
                guard await waitWhileSheetPresented() != nil else { return }
                focusedPane = candidate
                await Task.yield()
                if focusedPane == candidate { return }
            }
        }
    }

    /// Waits for any sidebar sheet/alert (`SidebarDialogState.isPresenting`) to close, returning how
    /// long that took, or `nil` if the task was cancelled meanwhile. Launch's pane focus must not be
    /// assigned under a sheet: pressing ⌘N while `applyInitialFocus` is still waiting opens
    /// `AddFeedSheet`, and a late `focusedPane` assignment then pulls focus out of its URL field for
    /// a moment before the sheet's window takes it back. Deferring rather than giving up keeps launch
    /// from ending with no pane focused once the sheet is dismissed.
    private func waitWhileSheetPresented() async -> Duration? {
        let start = ContinuousClock.now
        while sidebarDialogs.isPresenting {
            try? await Task.sleep(for: .milliseconds(16))
            if Task.isCancelled { return nil }
        }
        return ContinuousClock.now - start
    }

    private func initialFocusReady(_ target: HomeFocusedPane, expectsFeeds: Bool?) -> Bool {
        switch target {
        case .articleList, .reader:
            guard let id = home.selectedArticleId else { return false }
            return home.articleRows.indexById[id] != nil
        default:
            return expectsFeeds == false || home.hasFeeds
        }
    }

    /// Whether a text input holds keyboard focus: the search field, or a row's in-place name editor
    /// (`isEditingInline` — on macOS that editor's focus is not visible through `focusedPane`, see
    /// `RenameTextField`).
    private var textInputFocused: Bool {
        focusedPane == .search || sidebarDialogs.isEditingInline
    }

    private func handleKeyPress(_ press: KeyPress) -> KeyPress.Result {
        guard let (key, modifiers) = mapKey(press) else { return .ignored }
        guard let shortcut = HomeShortcutsKt.homeShortcutFor(
            key: key,
            modifiers: modifiers,
            // The sidebar's `.searchable` field reports its focus through this same `focusedPane`
            // via `.searchFocused` on macOS 15 / iOS 18+; before that its focus cannot be seen, so
            // this stays false there — see `SearchFocusModifier`.
            textInputFocused: textInputFocused,
            // Ctrl+Shift+R belongs to the Feed menu's "Refresh selected feed" item on desktop
            // Compose (`AppMenuTree.kt`), not the sidebar-refresh key touch-only platforms bind it
            // to — see `HomeCommands.swift`'s `refreshSelectedFeed`.
            refreshListAvailable: false,
            isMacOs: true
        ) else { return .ignored }

        switch shortcut {
        case .escape:
            // An in-progress sidebar drag is a system drag session, which cancels itself on Escape
            // before this handler sees the key (Compose's own `onEscape`, `HomeScreen.kt`, has to
            // do that by hand), so there is nothing to do here. Reporting
            // `.ignored` (rather than `.handled` for a key that does nothing) lets the key still
            // reach macOS's own responder chain, e.g. to exit full screen. Does not hide search
            // results either way: a hidden bar with the query still in the field would show stale
            // results reappearing on the next keystroke.
            return .ignored
        case .up:
            // A row's name editor is a single-line field: ↑/↓ have no use there, and must not move
            // the selection out from under it (`homeShortcutFor` still reports them while a text
            // input is focused, for the search field's hand-off below).
            if sidebarDialogs.isEditingInline { return .ignored }
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
            if sidebarDialogs.isEditingInline { return .ignored }
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
                moveFocusFromFeedListToArticleList(home: home, focusedPane: $focusedPane)
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
            // Focusing the system search field needs `.searchFocused` (macOS 15 / iOS 18+).
            guard #available(macOS 15, iOS 18, *) else { return .ignored }
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
        guard let next = FeedListModelKt.nextFeedListRow(
            current: home.selectedRowInstance,
            orderedRows: home.sidebar.orderedRows,
            delta: Int32(delta)
        ) else { return }
        home.selectFilter(next.filter, instance: next)
    }

    private func requestRename() {
        sidebarDialogs.startRename(home.selectedRowInstance)
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

/// Compose's window shows no title over the panes; `NavigationSplitView` draws the window's name as
/// a toolbar item of its own, which `NSWindow.titleVisibility` does not reach. The window itself
/// keeps its name (`KeryxApp`'s `Window(L("app_name"))`).
private struct HiddenSplitViewTitle: ViewModifier {
    func body(content: Content) -> some View {
        #if os(macOS)
        if #available(macOS 15, *) {
            content.toolbar(removing: .title)
        } else {
            content.navigationTitle("")
        }
        #else
        content
        #endif
    }
}

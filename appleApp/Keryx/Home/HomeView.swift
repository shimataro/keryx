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

    @FocusState private var focusedPane: HomeFocusedPane?

    var body: some View {
        NavigationSplitView {
            FeedListView(home: home, dialogs: sidebarDialogs, focusedPane: $focusedPane)
        } content: {
            ArticleListView(home: home, notifications: notifications, focusedPane: $focusedPane)
        } detail: {
            ArticleDetailView(home: home, focusedPane: $focusedPane)
        }
        .onKeyPress { press in handleKeyPress(press) }
        .task {
            await home.startObserving()
        }
        .onAppear {
            if focusedPane == nil { focusedPane = .articleList }
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
            // Reserved for cancelling an in-progress drag, matching Compose's own `onEscape`
            // (`HomeScreen.kt`) — see the drag-and-drop batch. Does not hide search results: a
            // hidden bar with the query still in the field would show stale results reappearing on
            // the next keystroke.
            break
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
            if focusedPane == .feedList { requestRename() }
        case .deleteFeedListItem:
            if focusedPane == .feedList { requestDelete() }
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

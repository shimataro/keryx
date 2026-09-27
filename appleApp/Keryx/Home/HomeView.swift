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
            // `.searchFocused(_:)` needs macOS 15 (this project targets 14+), so there is no way to
            // observe the system search field's own focus state here yet — every key press is
            // treated as if no text field were focused. Revisit once the deployment target moves to
            // 15, or a custom (non-`.searchable`) search field replaces the system one.
            textInputFocused: false,
            refreshListAvailable: true,
            isMacOs: true
        ) else { return .ignored }

        switch shortcut {
        case .escape:
            if home.searchBarVisible {
                home.viewModel.setSearchBarVisible(visible: false)
            }
        case .up:
            if focusedPane == .feedList {
                moveFeedListSelection(by: -1)
            } else {
                home.viewModel.selectPrevious()
            }
        case .down:
            if focusedPane == .feedList {
                moveFeedListSelection(by: 1)
            } else {
                home.viewModel.selectNext()
            }
        case .nextArticle:
            home.viewModel.selectNext()
        case .previousArticle:
            home.viewModel.selectPrevious()
        case .left, .right, .pageUp, .pageDown, .home, .end:
            break
        case .search:
            home.viewModel.setSearchBarVisible(visible: true)
            home.viewModel.requestSearchFocus()
        case .renameFeedListItem:
            requestRename()
        case .deleteFeedListItem:
            requestDelete()
        case .refreshList:
            home.viewModel.pullToRefresh()
        }
        return .handled
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

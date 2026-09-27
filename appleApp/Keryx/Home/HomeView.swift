import KeryxShared
import SwiftUI

/// The 3-pane Home screen: sidebar (`FeedListView`) / article list (`ArticleListView`) / reader
/// (`ArticleDetailView`). Desktop/macOS is unconditionally the 3-pane steady state (`external-spec.md`
/// §9), so — unlike the Compose app, which also serves narrower Android widths — this container has
/// no narrower-layout branch to reproduce.
struct HomeView: View {
    let home: HomeObservable

    @FocusState private var focusedPane: HomeFocusedPane?

    var body: some View {
        NavigationSplitView {
            FeedListView(home: home, focusedPane: $focusedPane)
        } content: {
            ArticleListView(home: home, focusedPane: $focusedPane)
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
            // Sidebar keyboard navigation (moving among feed-list rows) is not yet wired — see
            // FeedListView's own TODO. Everywhere else, Up/Down move the article cursor exactly
            // like J/K, matching ordinary list-navigation expectations.
            if focusedPane != .feedList { home.viewModel.selectPrevious() }
        case .down:
            if focusedPane != .feedList { home.viewModel.selectNext() }
        case .nextArticle:
            home.viewModel.selectNext()
        case .previousArticle:
            home.viewModel.selectPrevious()
        case .left, .right, .pageUp, .pageDown, .home, .end:
            break
        case .search:
            home.viewModel.setSearchBarVisible(visible: true)
            home.viewModel.requestSearchFocus()
        case .renameFeedListItem, .deleteFeedListItem:
            break // feed rename/delete UI is M3 scope
        case .refreshList:
            home.viewModel.pullToRefresh()
        }
        return .handled
    }
}

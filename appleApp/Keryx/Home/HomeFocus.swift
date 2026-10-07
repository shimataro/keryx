import KeryxShared
import SwiftUI

/// Which pane currently owns keyboard focus, for `@FocusState` in `HomeView` — mirrors the desktop
/// Compose app's own notion of pane focus (`app-architecture.md`'s keyboard-navigation section),
/// which `HomeViewModel` itself does not track (it is UI-owned state).
enum HomeFocusedPane: Hashable {
    case feedList
    case articleList
    case reader
    case search
}

/// Publishes `HomeView`'s own `@FocusState` into `FocusedValues`, so `HomeCommands` — a separate
/// `Commands` builder with no `@FocusState` of its own — can read it too, and gate the Feed menu's
/// bare-key Return/Delete accelerators to only when the sidebar itself holds focus (see
/// `HomeCommands.bareKeysActive`). `Value` is `HomeFocusedPane?` (not `HomeFocusedPane`) so "the
/// scene is focused, but no pane currently is" can be published as `.some(nil)`, distinct from "not
/// published at all" (`nil`) — both read the same to `HomeCommands` (bare keys inactive), but the
/// distinction keeps this key's semantics exact.
private struct HomeFocusedPaneKey: FocusedValueKey {
    typealias Value = HomeFocusedPane?
}

extension FocusedValues {
    var homeFocusedPane: HomeFocusedPane?? {
        get { self[HomeFocusedPaneKey.self] }
        set { self[HomeFocusedPaneKey.self] = newValue }
    }
}

/// Moves keyboard focus from the sidebar into the article list, selecting the first article when
/// none is selected yet so the list isn't entered with nothing to act on — the sidebar's → key.
/// Shared by `HomeView.handleKeyPress` and `FeedListView`'s own key handler, which has to take
/// → before the sidebar outline's own expand/collapse does.
@MainActor
func moveFocusFromFeedListToArticleList(home: HomeObservable, focusedPane: FocusState<HomeFocusedPane?>.Binding) {
    if home.selectedArticle == nil, let first = home.viewModel.currentArticles().first {
        home.selectArticle(first)
    }
    focusedPane.wrappedValue = .articleList
}

/// Binds a `.searchable` field — the sidebar's on macOS, the article list's on iOS — to
/// `focusedPane`'s `.search` case where the system supports it (`.searchFocused(_:equals:)` is
/// macOS 15 / iOS 18+); a no-op before that.
struct SearchFocusModifier: ViewModifier {
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    func body(content: Content) -> some View {
        if #available(macOS 15, iOS 18, *) {
            content.searchFocused(focusedPane, equals: .search)
        } else {
            content
        }
    }
}

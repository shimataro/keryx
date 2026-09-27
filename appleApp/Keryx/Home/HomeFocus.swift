import Foundation

/// Which pane currently owns keyboard focus, for `@FocusState` in `HomeView` — mirrors the desktop
/// Compose app's own notion of pane focus (`app-architecture.md`'s keyboard-navigation section),
/// which `HomeViewModel` itself does not track (it is UI-owned state).
enum HomeFocusedPane: Hashable {
    case feedList
    case articleList
    case reader
    case search
}

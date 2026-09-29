/// How the sidebar's native `List` selection behaves while `NavigationSplitView` is collapsed into a
/// single stack (iPhone, compact width) — kept free of SwiftUI so the standalone `KeryxTests` bundle
/// can compile it directly.
///
/// Collapsed, a sidebar row is a navigation link into the article list rather than a persistent
/// selection. Showing the shared selection while the sidebar is the topmost column would leave that
/// row selected after popping back, so tapping it again would not register as a change and would
/// not navigate at all.
enum CompactSidebarSelection {
    /// The key the native `List` should show as selected: none while the collapsed sidebar is the
    /// topmost column (iOS draws no selection on a list the user just popped back to), otherwise
    /// the shared selection itself.
    static func displayedKey(selectedKey: String, sidebarIsTopmost: Bool) -> String? {
        sidebarIsTopmost ? nil : selectedKey
    }

    /// What a tap on the row keyed `key` does: the filter changes only when a different row was
    /// tapped, but the article list is always opened — re-tapping the current row included.
    static func tap(key: String, selectedKey: String) -> (changesFilter: Bool, navigates: Bool) {
        (changesFilter: key != selectedKey, navigates: true)
    }
}

/// When a phone-width split view rebuilds its article list (`ArticleListView.remountGeneration`) —
/// kept free of SwiftUI so the standalone `KeryxTests` bundle can compile it directly.
///
/// Only when the collapsed stack brings the list forward from the sidebar: SwiftUI drops the list's
/// refresh control once its column is popped and never re-attaches it, so a list kept from before
/// would refresh with no spinner (see "iOS: the article list's pull-to-refresh spinner is missing
/// after returning from the sidebar" in `docs/known-issues.md`). A rebuilt list starts at the top,
/// so no other route rebuilds it: returning from the reader keeps the control, and with it the
/// list's scroll position and the return flash.
enum ArticleListRemount {
    /// Whether moving the collapsed stack from `old` to `new` rebuilds the article list. The columns
    /// are `CompactSearchNavigation.Column`, the test bundle's stand-in for `NavigationSplitViewColumn`.
    static func isNeeded(
        isCompact: Bool,
        from old: CompactSearchNavigation.Column,
        to new: CompactSearchNavigation.Column
    ) -> Bool {
        isCompact && old == .sidebar && new == .content
    }
}

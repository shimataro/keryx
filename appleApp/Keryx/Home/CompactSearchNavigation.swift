/// Where a search-focus request (⌘F, from the menu bar or the key handler) takes the split view
/// while it is collapsed into a single stack (iPhone, compact width) — kept free of SwiftUI so the
/// standalone `KeryxTests` bundle can compile it directly.
///
/// On iOS the search field lives on the article list, the column whose contents it narrows (as in
/// Mail, and as Android's narrower layouts do), so a request made while the sidebar or the reader is
/// the topmost column first brings the article list forward. At a regular width every column is on
/// screen together and nothing needs to move.
enum CompactSearchNavigation {
    /// The collapsed stack's columns — mirrors `NavigationSplitViewColumn`, which the test bundle
    /// cannot use without SwiftUI.
    enum Column: Equatable {
        case sidebar
        case content
        case detail
    }

    /// The column to bring forward for a search-focus request, or `nil` to stay where it is.
    static func column(isCompact: Bool, current: Column) -> Column? {
        guard isCompact, current != .content else { return nil }
        return .content
    }
}

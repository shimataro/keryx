/// An action a sidebar row offers when swiped from its trailing edge.
enum SidebarSwipeAction: Equatable, Sendable {
    case rename
    /// Opens the unsubscribe confirmation of a feed row.
    case unsubscribe
    /// Opens the delete confirmation of a folder or tag row.
    case delete
}

/// Which swipe actions each kind of sidebar row offers, in the order they appear from the trailing
/// edge inward (the destructive one first, as in the system apps). Kept free of UIKit so the
/// standalone `KeryxTests` bundle can compile it directly.
enum SidebarSwipeActions {
    static func available(for item: SidebarItemID) -> [SidebarSwipeAction] {
        switch item {
        case .feed, .feedInTag: return [.unsubscribe, .rename]
        case .folder, .tag: return [.delete, .rename]
        // All, Starred and the headers have neither a name to edit nor anything to delete.
        case .all, .starred, .sectionHeader, .noFolderHeader: return []
        }
    }
}

import CoreGraphics

/// The row at the top of the article list's viewport, and how far the viewport has scrolled past
/// that row's top edge.
struct ScrollAnchor: Equatable {
    let id: String
    let offset: CGFloat
}

/// Where the macOS article list scrolls to when its rows change (`ArticleTableView`).
///
/// The rows all have one fixed height, so a position is a row index times that height — no row has
/// to be measured. That is the point of the table: SwiftUI's `List` measured every row inserted
/// above the top visible one to keep that row in place, which froze the app for as long as thousands
/// of read articles took to lay out when "unread only" was turned off.
enum ArticleTableLayout {
    /// The vertical scroll offset that keeps `anchor` where it was on screen.
    ///
    /// The top visible row stays put by id, so rows inserted above it (turning "unread only" off, new
    /// articles at the fresh end) land out of view — the "new articles" pill relies on exactly that
    /// (`freshSideUnseenCount`). When the anchor row itself is gone, the first row after it in
    /// `oldIds` that is still present takes its place, then the nearest one before it, then the top.
    ///
    /// - Parameters:
    ///   - oldIds: The rows before the change, in display order.
    ///   - newIndexById: Each new row's position (`ArticleRowList.indexById`).
    ///   - newCount: The number of new rows.
    ///   - anchor: The top visible row before the change, or `nil` for an empty list.
    ///   - resetToTop: Scroll to the top regardless of the anchor (a filter switch).
    /// - Returns: The offset, clamped to the scrollable range.
    static func scrollOffset(
        oldIds: [String],
        newIndexById: [String: Int],
        newCount: Int,
        anchor: ScrollAnchor?,
        rowHeight: CGFloat,
        viewportHeight: CGFloat,
        resetToTop: Bool
    ) -> CGFloat {
        guard !resetToTop, let anchor else { return 0 }
        let offset: CGFloat
        if let index = newIndexById[anchor.id] {
            offset = CGFloat(index) * rowHeight + anchor.offset
        } else if let index = survivingNeighbor(of: anchor.id, oldIds: oldIds, newIndexById: newIndexById) {
            offset = CGFloat(index) * rowHeight
        } else {
            offset = 0
        }
        let maxOffset = max(0, CGFloat(newCount) * rowHeight - viewportHeight)
        return min(max(0, offset), maxOffset)
    }

    /// The new index of the first row after `id` in `oldIds` that is still present, else of the
    /// nearest one before it.
    private static func survivingNeighbor(of id: String, oldIds: [String], newIndexById: [String: Int]) -> Int? {
        guard let oldIndex = oldIds.firstIndex(of: id) else { return nil }
        for candidate in oldIds[(oldIndex + 1)...] {
            if let index = newIndexById[candidate] { return index }
        }
        for candidate in oldIds[..<oldIndex].reversed() {
            if let index = newIndexById[candidate] { return index }
        }
        return nil
    }
}

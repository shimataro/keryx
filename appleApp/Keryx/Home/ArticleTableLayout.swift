import CoreGraphics

/// The row at the top of the article list's viewport, and how far the viewport has scrolled past
/// that row's top edge.
struct ScrollAnchor: Equatable {
    let id: String
    let offset: CGFloat
}

/// Where the article list scrolls to when its rows change or something asks for a scroll: the macOS
/// `ArticleTableView` and the iOS `ArticleCollectionView` both keep one fixed row height, so the same
/// arithmetic serves both.
///
/// Every offset here is measured from the first row's top edge, which sits at the top of the visible
/// area when the list is scrolled to its start — what each list's own scroll view translates to and
/// from its content offset (a toolbar or a pinned header takes `viewportHeight`'s share, not the
/// offset's).
///
/// The rows all have one fixed height, so a position is a row index times that height — no row has
/// to be measured. That is the point of the table: SwiftUI's `List` measured every row inserted
/// above the top visible one to keep that row in place, which froze the app for as long as thousands
/// of read articles took to lay out when "unread only" was turned off.
enum ArticleTableLayout {
    /// How long the visible rows settle before they are reported, coalescing a scroll's worth of changes.
    static let visibleRowsReportDelay: Duration = .milliseconds(50)
    /// The space above and below each row's content (the `List`'s row insets).
    static let rowVerticalInset: CGFloat = 2

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
        return clamped(offset, count: newCount, rowHeight: rowHeight, viewportHeight: viewportHeight)
    }

    /// The farthest the list can scroll: its last row's bottom edge at the bottom of the viewport, or
    /// the top when every row fits.
    static func maxOffset(count: Int, rowHeight: CGFloat, viewportHeight: CGFloat) -> CGFloat {
        max(0, CGFloat(count) * rowHeight - viewportHeight)
    }

    /// `offset`, kept within the scrollable range.
    static func clamped(_ offset: CGFloat, count: Int, rowHeight: CGFloat, viewportHeight: CGFloat) -> CGFloat {
        min(max(0, offset), maxOffset(count: count, rowHeight: rowHeight, viewportHeight: viewportHeight))
    }

    /// The offset of the list's fresh end — the top when newest first, the bottom otherwise.
    static func freshEndOffset(newestFirst: Bool, count: Int, rowHeight: CGFloat, viewportHeight: CGFloat) -> CGFloat {
        newestFirst ? 0 : maxOffset(count: count, rowHeight: rowHeight, viewportHeight: viewportHeight)
    }

    /// The top visible row and how far past its top edge the list is scrolled, or `nil` for an empty
    /// list. A list pulled past its start (a negative `visibleTop`) counts as at its start.
    static func anchor(count: Int, id: (Int) -> String, visibleTop: CGFloat, rowHeight: CGFloat) -> ScrollAnchor? {
        guard count > 0, rowHeight > 0 else { return nil }
        let top = max(0, visibleTop)
        let index = min(Int(top / rowHeight), count - 1)
        return ScrollAnchor(id: id(index), offset: top - CGFloat(index) * rowHeight)
    }

    /// The rows with any part inside the viewport, as indices into the list.
    static func visibleRows(count: Int, rowHeight: CGFloat, visibleTop: CGFloat, viewportHeight: CGFloat) -> Range<Int> {
        guard count > 0, rowHeight > 0, viewportHeight > 0 else { return 0..<0 }
        let first = Int(max(0, visibleTop) / rowHeight)
        let end = min(count, Int(((visibleTop + viewportHeight) / rowHeight).rounded(.up)))
        return first < end ? first..<end : 0..<0
    }

    /// The offset that brings row `index` into the viewport with the least scrolling, or `nil` when
    /// no scrolling is needed: the row is already in view, or — with `anyPartCounts` — partly in view.
    /// A row above the viewport ends up at its top edge, one below it at its bottom edge.
    static func revealOffset(
        index: Int,
        count: Int,
        rowHeight: CGFloat,
        visibleTop: CGFloat,
        viewportHeight: CGFloat,
        anyPartCounts: Bool
    ) -> CGFloat? {
        guard index >= 0, index < count else { return nil }
        let rowTop = CGFloat(index) * rowHeight
        let rowBottom = rowTop + rowHeight
        let visibleBottom = visibleTop + viewportHeight
        if rowTop >= visibleTop, rowBottom <= visibleBottom { return nil }
        if anyPartCounts, rowBottom > visibleTop, rowTop < visibleBottom { return nil }
        let offset = rowTop < visibleTop ? rowTop : rowBottom - viewportHeight
        return clamped(offset, count: count, rowHeight: rowHeight, viewportHeight: viewportHeight)
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

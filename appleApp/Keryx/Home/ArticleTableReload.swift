#if os(macOS)
import AppKit

/// Reloading and scrolling the macOS article list's table (`ArticleTableView`), kept apart from the
/// coordinator so the AppKit ordering it depends on can be tested on a bare table.
@MainActor
enum ArticleTableReload {
    /// Reloads `table` and scrolls to the offset `offset` computes once the reload has taken effect.
    ///
    /// `reloadData()` defers shrinking the table's frame to its new row count. Scrolling before that
    /// lands puts the clip view where it should be only until the frame shrinks, which then shifts
    /// its bounds again — leaving the list scrolled above its first row, with blank space where the
    /// rows were (the remaining rows floating mid-list). Laying the table out first applies the new
    /// frame, so the offset is computed against the real viewport and stays where it was put.
    ///
    /// - Parameter offset: The content offset to scroll to (see `scroll(_:toContentOffset:)`).
    static func reload(_ table: NSTableView, in scrollView: NSScrollView, thenScrollTo offset: () -> CGFloat) {
        table.reloadData()
        table.layoutSubtreeIfNeeded()
        scroll(scrollView, toContentOffset: offset())
    }

    /// Scrolls so that `offset` (measured from the first row's top edge) sits right under the
    /// toolbar.
    static func scroll(_ scrollView: NSScrollView, toContentOffset offset: CGFloat) {
        let clipView = scrollView.contentView
        clipView.scroll(to: NSPoint(x: clipView.bounds.origin.x, y: offset - scrollView.contentInsets.top))
        scrollView.reflectScrolledClipView(clipView)
    }
}
#endif

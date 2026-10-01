#if os(macOS)
import AppKit
import Testing

/// Covers `ArticleTableReload.reload`: a reload that changes the row count leaves the list scrolled
/// exactly to the requested offset, rather than shifted by the table's deferred frame change.
@Suite
@MainActor
struct ArticleTableReloadTests {
    private static let rowHeight: CGFloat = 50

    /// A fixed-height table in a window-hosted scroll view, scrolled to `startOffset`.
    @MainActor
    private final class Fixture: NSObject, NSTableViewDataSource, NSTableViewDelegate {
        let window: NSWindow
        let scrollView: NSScrollView
        let table: NSTableView
        var rowCount: Int

        init(rowCount: Int, startOffset: CGFloat) {
            self.rowCount = rowCount
            window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 300, height: 500),
                styleMask: [.titled],
                backing: .buffered,
                defer: false
            )
            window.isReleasedWhenClosed = false
            table = NSTableView()
            table.addTableColumn(NSTableColumn(identifier: NSUserInterfaceItemIdentifier("c")))
            table.headerView = nil
            table.usesAutomaticRowHeights = false
            table.rowHeight = ArticleTableReloadTests.rowHeight
            table.intercellSpacing = .zero
            scrollView = NSScrollView(frame: window.contentView!.bounds)
            scrollView.documentView = table
            super.init()
            table.dataSource = self
            table.delegate = self
            window.contentView!.addSubview(scrollView)
            table.reloadData()
            table.layoutSubtreeIfNeeded()
            ArticleTableReload.scroll(scrollView, toContentOffset: startOffset)
        }

        /// The offset currently scrolled to, measured as `ArticleTableReload.scroll` takes it.
        var contentOffset: CGFloat { scrollView.contentView.bounds.minY + scrollView.contentInsets.top }

        func numberOfRows(in tableView: NSTableView) -> Int { rowCount }

        func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
            NSView()
        }
    }

    private func reload(from oldCount: Int, scrolledTo start: CGFloat, to newCount: Int, target: CGFloat) -> CGFloat {
        let fixture = Fixture(rowCount: oldCount, startOffset: start)
        defer { fixture.window.close() }
        #expect(fixture.contentOffset == start)
        fixture.rowCount = newCount
        ArticleTableReload.reload(fixture.table, in: fixture.scrollView) { target }
        fixture.window.contentView!.layoutSubtreeIfNeeded()
        return fixture.contentOffset
    }

    @Test
    func shrinkingToOneRowWhileScrolledLandsAtTheTop() {
        #expect(reload(from: 40, scrolledTo: 150, to: 1, target: 0) == 0)
    }

    @Test
    func shrinkingWhileScrolledLandsOnTheTarget() {
        #expect(reload(from: 40, scrolledTo: 900, to: 20, target: 300) == 300)
    }

    @Test
    func growingLandsOnTheTarget() {
        #expect(reload(from: 40, scrolledTo: 150, to: 80, target: 1500) == 1500)
    }
}
#endif

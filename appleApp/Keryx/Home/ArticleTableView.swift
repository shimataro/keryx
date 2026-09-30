#if os(macOS)
import AppKit
import KeryxShared
import SwiftUI

/// The new-articles pill's jump to the fresh end of the list — the top when newest first, the bottom
/// otherwise — carried out by `ArticleTableView` once per `generation`.
struct ArticleTableScrollRequest: Equatable {
    var generation = 0
    var newestFirst = true
}

/// The macOS article list: an `NSTableView` with one fixed row height, rather than SwiftUI's `List`.
///
/// `List` sizes its rows automatically, and applying a change measures every row inserted above the
/// top visible one (`_doAutomaticRowHeightsForInsertedAndVisibleRows`) to keep that row in place.
/// Turning "unread only" off inserts thousands of read articles at once, which blocked the main
/// thread for minutes — see "Article list (macOS)" in `docs/app-architecture.md`. Every article row
/// has the same height (its title always reserves two lines), so the table measures one row once,
/// reloads on a change, and keeps the top visible row in place from its index alone
/// (`ArticleTableLayout`).
///
/// The rows are the same `ArticleRowView`s the iOS `List` shows, hosted one per visible cell.
/// Selection, clicks and context menus stay the row's own. The table takes the first responder for
/// the pane focus, as the `List`'s own table did, but passes every key on, so `HomeView`'s key
/// handling remains SwiftUI's (`ArticleNSTableView`).
struct ArticleTableView: NSViewRepresentable {
    let rows: ArticleRowList
    let selectedId: String?
    /// Bumped on every filter switch: the list goes back to the top, including for the new filter's
    /// rows once they arrive.
    let resetGeneration: Int
    let scrollRequest: ArticleTableScrollRequest
    /// Passed into each cell explicitly — a hosted cell does not inherit SwiftUI's environment.
    let contextMenuSelectionTracker: ContextMenuSelectionTracker
    let makeRow: (ArticleRowModel) -> ArticleRowView
    /// A click on the table outside any row.
    let onBackgroundClick: () -> Void
    /// The ids of the rows on screen, in display order, whenever they change (debounced).
    let onVisibleIdsChanged: ([String]) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(resetGeneration: resetGeneration, scrollGeneration: scrollRequest.generation)
    }

    func makeNSView(context: Context) -> NSScrollView {
        let table = ArticleNSTableView()
        let column = NSTableColumn(identifier: ArticleCellView.identifier)
        column.resizingMask = .autoresizingMask
        table.addTableColumn(column)
        table.headerView = nil
        table.style = .plain
        table.usesAutomaticRowHeights = false
        table.intercellSpacing = .zero
        table.gridStyleMask = []
        table.selectionHighlightStyle = .none
        table.focusRingType = .none
        table.backgroundColor = .clear
        table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        table.dataSource = context.coordinator
        table.delegate = context.coordinator

        let scrollView = NSScrollView()
        scrollView.documentView = table
        scrollView.hasVerticalScroller = true
        scrollView.autohidesScrollers = true
        scrollView.drawsBackground = false
        // The view extends under the toolbar (`ignoresSafeArea` at the call site); AppKit insets the
        // content by the toolbar's height, so rows scroll under it as they did in the `List`.
        scrollView.automaticallyAdjustsContentInsets = true
        context.coordinator.attach(scrollView: scrollView, table: table)
        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        context.coordinator.update(with: self)
    }

    static func dismantleNSView(_ scrollView: NSScrollView, coordinator: Coordinator) {
        coordinator.detach()
    }

    @MainActor
    final class Coordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
        private var parent: ArticleTableView?
        private weak var scrollView: NSScrollView?
        private weak var table: ArticleNSTableView?

        private var rows = ArticleRowList.empty
        private var rowHeightMeasured = false
        private var lastResetGeneration: Int
        private var lastScrollGeneration: Int
        private var lastSelectedId: String?
        private var hasUpdated = false
        /// Set by a filter switch until the new rows arrive (or the user scrolls): they are shown
        /// from the top instead of anchored to whatever row the previous filter had on screen.
        private var resetPending = false
        private var reportTask: Task<Void, Never>?
        private var lastReportedIds: [String]?

        init(resetGeneration: Int, scrollGeneration: Int) {
            lastResetGeneration = resetGeneration
            lastScrollGeneration = scrollGeneration
        }

        func attach(scrollView: NSScrollView, table: ArticleNSTableView) {
            self.scrollView = scrollView
            self.table = table
            table.onBackgroundClick = { [weak self] in self?.parent?.onBackgroundClick() }
            let clipView = scrollView.contentView
            clipView.postsBoundsChangedNotifications = true
            NotificationCenter.default.addObserver(
                self, selector: #selector(boundsDidChange), name: NSView.boundsDidChangeNotification, object: clipView
            )
            NotificationCenter.default.addObserver(
                self, selector: #selector(willStartLiveScroll), name: NSScrollView.willStartLiveScrollNotification, object: scrollView
            )
        }

        func detach() {
            NotificationCenter.default.removeObserver(self)
            reportTask?.cancel()
        }

        @objc private func boundsDidChange(_ notification: Notification) {
            scheduleReport()
        }

        @objc private func willStartLiveScroll(_ notification: Notification) {
            resetPending = false
        }

        // MARK: - Updates

        func update(with newParent: ArticleTableView) {
            parent = newParent
            guard let table else { return }
            if newParent.resetGeneration != lastResetGeneration {
                lastResetGeneration = newParent.resetGeneration
                resetPending = true
                scroll(toContentOffset: 0)
            }
            if !rowHeightMeasured {
                table.rowHeight = measureRowHeight()
                rowHeightMeasured = true
            }

            let old = rows
            rows = newParent.rows
            // The same generation is the same rows in the same order (`ArticleRowList.generation`),
            // so only a new one needs its ids compared.
            if old.generation != rows.generation,
               old.rows.count != rows.rows.count || !old.rows.elementsEqual(rows.rows, by: { $0.id == $1.id }) {
                applyStructureChange(from: old)
            } else {
                refreshVisibleCells()
            }

            if newParent.selectedId != lastSelectedId {
                lastSelectedId = newParent.selectedId
                if hasUpdated {
                    revealSelection()
                } else {
                    // The first update runs before the table has a size, so the selection restored
                    // from the previous session is brought into view once it has been laid out.
                    Task { [weak self] in
                        await Task.yield()
                        self?.revealSelection()
                    }
                }
            }
            if newParent.scrollRequest.generation != lastScrollGeneration {
                lastScrollGeneration = newParent.scrollRequest.generation
                scrollToFreshEnd(newestFirst: newParent.scrollRequest.newestFirst)
            }
            hasUpdated = true
            scheduleReport()
        }

        /// Reloads for a new set or order of rows, keeping the top visible row where it was on
        /// screen — or going to the top after a filter switch.
        private func applyStructureChange(from old: ArticleRowList) {
            guard let table else { return }
            let anchor = currentAnchor(in: old)
            table.reloadData()
            let offset = ArticleTableLayout.scrollOffset(
                oldIds: old.rows.map(\.id),
                newIndexById: rows.indexById,
                newCount: rows.rows.count,
                anchor: anchor,
                rowHeight: table.rowHeight,
                viewportHeight: viewportHeight,
                resetToTop: resetPending
            )
            scroll(toContentOffset: offset)
            resetPending = false
        }

        /// Re-renders only the rows on screen whose content changed (a read or star toggle, the
        /// selection moving) — nothing off screen has a cell to update.
        private func refreshVisibleCells() {
            guard let table, let parent else { return }
            let visible = table.rows(in: table.visibleRect)
            for index in visible.location..<(visible.location + visible.length) where index < rows.rows.count {
                guard let cell = table.view(atColumn: 0, row: index, makeIfNecessary: false) as? ArticleCellView else { continue }
                cell.show(parent.makeRow(rows.rows[index]), tracker: parent.contextMenuSelectionTracker)
            }
        }

        private func scrollToFreshEnd(newestFirst: Bool) {
            guard let table else { return }
            let end = max(0, CGFloat(rows.rows.count) * table.rowHeight - viewportHeight)
            scroll(toContentOffset: newestFirst ? 0 : end)
        }

        /// Scrolls the selected row fully into view, as little as possible — a row already on
        /// screen (e.g. one just clicked) does not move.
        private func revealSelection() {
            guard let table, let id = parent?.selectedId, let index = rows.indexById[id] else { return }
            let rowRect = table.rect(ofRow: index)
            guard !contentVisibleRect.contains(rowRect) else { return }
            table.scrollRowToVisible(index)
        }

        /// Sizes one row once: every row has this height, since the title always reserves two lines
        /// and the feed/timestamp line one.
        ///
        /// Measured on a sample row rather than a real one: a line of Japanese text or an emoji can
        /// be taller than a Latin one, so a row measured on Latin text could be too short for a
        /// Japanese title's two lines. The sample fills both title lines (and the feed line) with
        /// that mix, at the narrowest width the pane allows so they do wrap.
        private func measureRowHeight() -> CGFloat {
            guard let parent else { return 0 }
            let tallText = String(repeating: "Keryx 漢字かなカナ 😀 ", count: 12)
            let sample = ArticleRowModel(
                row: ArticleListRow(
                    id: "", feed_id: "", title: tallText, url: "", published_at: KotlinLong(value: 0),
                    created_at: 0, is_read: 0, is_starred: 1
                ),
                markedTitle: nil,
                feedTitle: tallText,
                faviconUrl: nil,
                zone: Kotlinx_datetimeTimeZone.Companion.shared.currentSystemDefault()
            )
            let host = NSHostingView(rootView: ArticleCellContent(row: parent.makeRow(sample), tracker: parent.contextMenuSelectionTracker))
            // Width pinned, height left to the content: an unbounded height proposal would let the
            // row's flexible star slot grow without limit.
            host.widthAnchor.constraint(equalToConstant: CGFloat(ConstantsKt.ARTICLE_LIST_PANE_MIN_WIDTH)).isActive = true
            return ceil(host.fittingSize.height)
        }

        // MARK: - Scroll geometry

        /// The part of the clip view not under the toolbar, in table coordinates.
        private var contentVisibleRect: CGRect {
            guard let scrollView else { return .zero }
            var rect = scrollView.contentView.bounds
            let insets = scrollView.contentInsets
            rect.origin.y += insets.top
            rect.size.height -= insets.top + insets.bottom
            return rect
        }

        private var viewportHeight: CGFloat { contentVisibleRect.height }

        /// The top visible row and how far past its top edge the list is scrolled.
        private func currentAnchor(in list: ArticleRowList) -> ScrollAnchor? {
            guard let table, !list.rows.isEmpty, table.rowHeight > 0 else { return nil }
            let top = max(0, contentVisibleRect.minY)
            let index = min(Int(top / table.rowHeight), list.rows.count - 1)
            return ScrollAnchor(id: list.rows[index].id, offset: top - CGFloat(index) * table.rowHeight)
        }

        /// Scrolls so that `offset` (measured from the first row's top edge) sits right under the
        /// toolbar.
        private func scroll(toContentOffset offset: CGFloat) {
            guard let scrollView else { return }
            let clipView = scrollView.contentView
            clipView.scroll(to: NSPoint(x: clipView.bounds.origin.x, y: offset - scrollView.contentInsets.top))
            scrollView.reflectScrolledClipView(clipView)
        }

        // MARK: - Visible rows

        /// Coalesces a scroll's worth of changes into one report, like the `List`'s own
        /// `onAppear`/`onDisappear` debounce on iOS.
        private func scheduleReport() {
            reportTask?.cancel()
            reportTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(50))
                guard !Task.isCancelled else { return }
                self?.reportVisibleIds()
            }
        }

        private func reportVisibleIds() {
            guard let table else { return }
            let visible = table.rows(in: contentVisibleRect)
            let ids = (visible.location..<(visible.location + visible.length))
                .filter { $0 < rows.rows.count }
                .map { rows.rows[$0].id }
            guard ids != lastReportedIds else { return }
            lastReportedIds = ids
            parent?.onVisibleIdsChanged(ids)
        }

        // MARK: - NSTableViewDataSource / NSTableViewDelegate

        func numberOfRows(in tableView: NSTableView) -> Int {
            rows.rows.count
        }

        func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
            guard let parent, row < rows.rows.count else { return nil }
            let cell = tableView.makeView(withIdentifier: ArticleCellView.identifier, owner: self) as? ArticleCellView
                ?? ArticleCellView()
            cell.show(parent.makeRow(rows.rows[row]), tracker: parent.contextMenuSelectionTracker)
            return cell
        }

        func tableView(_ tableView: NSTableView, shouldSelectRow row: Int) -> Bool {
            false
        }
    }
}

/// The table itself. Like the `NSTableView` inside SwiftUI's own `List`, it is what takes the first
/// responder when the article list has the pane focus, but it handles no key itself: every key goes
/// on up the responder chain to the hosting view, whose SwiftUI key handling (`HomeView`'s
/// `onKeyPress`) runs as for any other pane — the table's own arrow-key selection and type-select
/// never see them. A click outside any row, which no hosted row sees, is reported so the pane can
/// still take focus.
final class ArticleNSTableView: NSTableView {
    var onBackgroundClick: (() -> Void)?

    override func mouseDown(with event: NSEvent) {
        onBackgroundClick?()
    }

    override func keyDown(with event: NSEvent) {
        nextResponder?.keyDown(with: event)
    }

    override func keyUp(with event: NSEvent) {
        nextResponder?.keyUp(with: event)
    }
}

/// One reusable cell, hosting the row's SwiftUI view.
private final class ArticleCellView: NSTableCellView {
    static let identifier = NSUserInterfaceItemIdentifier("ArticleCell")

    private var host: ArticleRowHostingView?
    private var shown: ArticleRowView?

    init() {
        super.init(frame: .zero)
        identifier = Self.identifier
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// Shows `row`, re-rendering only when it differs from what the cell already shows.
    func show(_ row: ArticleRowView, tracker: ContextMenuSelectionTracker) {
        if let shown, shown == row { return }
        shown = row
        let content = ArticleCellContent(row: row, tracker: tracker)
        if let host {
            host.rootView = content
        } else {
            let host = ArticleRowHostingView(rootView: content)
            host.sizingOptions = []
            host.frame = bounds
            host.autoresizingMask = [.width, .height]
            addSubview(host)
            self.host = host
        }
    }
}

/// A row's hosting view never takes the first responder: a click on a row must leave the key events
/// with `HomeView`, which the row's own action then focuses on the article list.
private final class ArticleRowHostingView: NSHostingView<ArticleCellContent> {
    override var acceptsFirstResponder: Bool { false }
}

/// A row as a cell shows it: the `List`'s own margins around it (8pt on either side — see
/// `ArticleListView`'s row insets — and 2pt above and below), and the context-menu tracker a hosted
/// view cannot inherit.
private struct ArticleCellContent: View {
    let row: ArticleRowView
    let tracker: ContextMenuSelectionTracker

    var body: some View {
        row
            .equatable()
            // Laid out at its own ideal height, as the `List` laid it out: given the cell's fixed
            // height as a proposal instead, a title that fits in exactly two lines is set on one.
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxHeight: .infinity, alignment: .top)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .environment(\.contextMenuSelectionTracker, tracker)
            // A cell scrolled under the toolbar must not give up any of its fixed height to the
            // window's safe area.
            .ignoresSafeArea()
    }
}
#endif

#if os(macOS)
/// The soft scroll edge effect SwiftUI's own `List` gets under the toolbar on macOS 26 — a hosted
/// `NSScrollView` has none by default, so rows scrolled under the toolbar would stay sharp.
struct SoftTopScrollEdge: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 26, *) {
            content.scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            content
        }
    }
}
#endif

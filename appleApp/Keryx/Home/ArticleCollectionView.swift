#if os(iOS)
import KeryxShared
import SwiftUI
import UIKit

/// The iOS article list: a `UICollectionView` list rather than SwiftUI's `List`, as the sidebar is.
///
/// - A scroll runs at once against the rows the list holds, or waits — only the latest — until the
///   list is in a window; nothing is left pending inside UIKit against rows that may have changed
///   ("iOS: going back to the article list crashed in `UICollectionView`" in `docs/known-issues.md`).
/// - The refresh control is the list's own, so it is never lost when the column is popped and pushed
///   again.
/// - The long-press menu goes through the collection view's delegate, whose highlight is
///   `ContextMenuPressHighlight`'s clear view ("iOS: a row jumps when a swipe starts").
///
/// The rows are `ArticleRowView`s, hosted one per visible cell (`UIHostingConfiguration`): selection
/// drawing and taps are theirs.
struct ArticleCollectionView: UIViewControllerRepresentable {
    let rows: ArticleRowList
    let scrollRequest: ArticleListScrollRequest
    /// Whether pulling the list down refreshes (`PullToRefreshAvailability`).
    let canPullToRefresh: Bool
    /// Runs while the pull's indicator is up (`HomeObservable.pullToRefresh`).
    let onRefresh: () async -> Void
    let makeRow: (ArticleRowModel) -> ArticleRowView
    /// The ids of the rows on screen, in display order, whenever they change (debounced); empty while
    /// the list is not on screen.
    let onVisibleIdsChanged: ([String]) -> Void

    func makeUIViewController(context: Context) -> ArticleCollectionViewController {
        let controller = ArticleCollectionViewController(scrollGeneration: scrollRequest.generation)
        controller.apply(self)
        return controller
    }

    func updateUIViewController(_ controller: ArticleCollectionViewController, context: Context) {
        controller.apply(self)
    }

    static func dismantleUIViewController(_ controller: ArticleCollectionViewController, coordinator: ()) {
        controller.tearDown()
    }
}

/// A row as a cell shows it: the list's 2pt above and below, and the return flash's fade, which the
/// hosted row cannot get from `HomeView`'s `withAnimation`.
private struct ArticleCellContent: View {
    let row: ArticleRowView

    var body: some View {
        row
            .equatable()
            .padding(.vertical, 2)
            // Fully on at once, fading out when it ends: the list's own idiom (`HomeView.returnFlashId`).
            .animation(row.isReturnFlashing ? nil : .easeOut(duration: HomeView.returnFlashFadeSeconds), value: row.isReturnFlashing)
    }
}

/// One reusable cell, hosting the row's SwiftUI view.
private final class ArticleCell: UICollectionViewListCell {
    private var shown: ArticleRowView?

    /// Shows `row`, re-rendering only when it differs from what the cell already shows.
    func show(_ row: ArticleRowView) {
        if let shown, shown == row { return }
        shown = row
        contentConfiguration = UIHostingConfiguration { ArticleCellContent(row: row) }.margins(.all, 0)
    }
}

/// Tells its controller when it is put into a window or taken out of one, and stays at the top while
/// its insets settle.
private final class ArticleListCollectionView: RefreshableListCollectionView {
    var onWindowChange: (() -> Void)?
    private var lastTopInset: CGFloat = 0

    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChange?()
    }

    /// A list resting at the top stays there when its top inset changes — the navigation bar's height
    /// is settled only after the list is first laid out, and UIKit leaves the offset where it was, so
    /// the first rows would otherwise sit under the bar.
    override func adjustedContentInsetDidChange() {
        let wasAtTop = contentOffset.y <= -lastTopInset + 0.5
        super.adjustedContentInsetDidChange()
        lastTopInset = adjustedContentInset.top
        if wasAtTop, !isDragging, !isDecelerating {
            contentOffset.y = -adjustedContentInset.top
        }
    }

    /// Scrolls to the very top, above the first row's inset.
    func scrollToTop(animated: Bool) {
        setContentOffset(CGPoint(x: contentOffset.x, y: -adjustedContentInset.top), animated: animated)
    }
}

@MainActor
final class ArticleCollectionViewController: UIViewController, UICollectionViewDelegate {
    private var input: ArticleCollectionView?
    private var rows = ArticleRowList.empty
    private var collectionView: ArticleListCollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var scrollGeneration: Int
    /// A scroll asked for while the list could not carry it out (not in a window, or not laid out
    /// yet): only the latest is kept.
    private var pendingScroll: ArticleListScrollRequest.Kind?

    private var reportTask: Task<Void, Never>?
    private let pullToRefresh = ListPullToRefresh()
    private var lastReportedIds: [String]?

    private let pressHighlight = ContextMenuPressHighlight()
    /// The cell whose menu is opening or open, hidden behind the menu's preview.
    private weak var contextMenuCell: UICollectionViewCell?

    init(scrollGeneration: Int) {
        self.scrollGeneration = scrollGeneration
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = ArticleListCollectionView(frame: view.bounds, collectionViewLayout: makeLayout())
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .clear
        // Arrow keys and J/K are `HomeView`'s, not UIKit's focus engine; a row's own button takes the tap.
        collectionView.allowsFocus = false
        collectionView.allowsSelection = false
        collectionView.delegate = self
        collectionView.onWindowChange = { [weak self] in self?.windowChanged() }
        pullToRefresh.scrollView = collectionView
        dataSource = makeDataSource()
        view.addSubview(collectionView)
    }

    override func didMove(toParent parent: UIViewController?) {
        super.didMove(toParent: parent)
        // Lets the navigation bar (and its search field) follow this list's scrolling.
        parent?.setContentScrollView(collectionView)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        hasAppeared = true
        performPendingScroll()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        hasAppeared = false
    }

    func tearDown() {
        reportTask?.cancel()
        pullToRefresh.cancel()
    }

    // MARK: - Layout and cells

    private func makeLayout() -> UICollectionViewLayout {
        UICollectionViewCompositionalLayout { [weak self] _, environment in
            var configuration = UICollectionLayoutListConfiguration(appearance: .plain)
            configuration.showsSeparators = false
            configuration.backgroundColor = .clear
            configuration.leadingSwipeActionsConfigurationProvider = { [weak self] indexPath in
                self?.swipeConfiguration(at: indexPath, leading: true)
            }
            configuration.trailingSwipeActionsConfigurationProvider = { [weak self] indexPath in
                self?.swipeConfiguration(at: indexPath, leading: false)
            }
            return NSCollectionLayoutSection.list(using: configuration, layoutEnvironment: environment)
        }
    }

    private func makeDataSource() -> UICollectionViewDiffableDataSource<Int, String> {
        let registration = UICollectionView.CellRegistration<ArticleCell, String> { [weak self] cell, _, id in
            cell.backgroundConfiguration = .clear()
            self?.configure(cell, id: id)
        }
        return UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { collectionView, indexPath, id in
            collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: id)
        }
    }

    /// Shows the row `id` in `cell`, if the list still has it.
    private func configure(_ cell: ArticleCell, id: String) {
        guard let input, let index = rows.indexById[id] else { return }
        cell.show(input.makeRow(rows.rows[index]))
    }

    private func row(at indexPath: IndexPath) -> ArticleRowView? {
        guard let input, let id = dataSource.itemIdentifier(for: indexPath), let index = rows.indexById[id] else { return nil }
        return input.makeRow(rows.rows[index])
    }

    // MARK: - Applying state

    /// Brings the list in line with `new`: the pull control, the rows, then any scroll asked for —
    /// always in that order, so a scroll is worked out against the rows the list has by then.
    func apply(_ new: ArticleCollectionView) {
        loadViewIfNeeded()
        input = new
        if new.canPullToRefresh {
            pullToRefresh.setAction { [weak self] in await self?.input?.onRefresh() }
        } else {
            pullToRefresh.setAction(nil)
        }

        let old = rows
        rows = new.rows
        // The same generation is the same rows in the same order (`ArticleRowList.generation`).
        if old.generation != rows.generation,
           old.rows.count != rows.rows.count || !old.rows.elementsEqual(rows.rows, by: { $0.id == $1.id }) {
            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            // A diffable snapshot rejects a repeated identifier; the list shows each article once.
            var seen = Set<String>()
            snapshot.appendItems(rows.rows.map(\.id).filter { seen.insert($0).inserted })
            dataSource.apply(snapshot, animatingDifferences: false)
        }
        refreshVisibleCells()

        if new.scrollRequest.generation != scrollGeneration {
            scrollGeneration = new.scrollRequest.generation
            if let kind = new.scrollRequest.kind {
                pendingScroll = kind
                performPendingScroll()
            }
        }
        scheduleReport()
    }

    /// Re-renders the rows on screen whose content changed (a read or star toggle, the selection
    /// moving) — nothing off screen has a cell to update.
    private func refreshVisibleCells() {
        for case let cell as ArticleCell in collectionView.visibleCells {
            guard let indexPath = collectionView.indexPath(for: cell),
                  let id = dataSource.itemIdentifier(for: indexPath) else { continue }
            configure(cell, id: id)
        }
    }

    // MARK: - Scrolling

    /// Whether the list is on screen with its insets settled — the only time a scroll means anything.
    /// Before it has appeared, the navigation bar's height is not yet part of its insets, so a row
    /// scrolled "into view" would land under the bar.
    private var isActive: Bool { hasAppeared && collectionView.window != nil && collectionView.bounds.height > 0 }
    private var hasAppeared = false

    /// Carries out the pending scroll if the list can, against the rows it holds now. A scroll to a
    /// row the list no longer has is dropped.
    private func performPendingScroll() {
        guard let kind = pendingScroll, isActive else { return }
        pendingScroll = nil
        collectionView.layoutIfNeeded()
        let count = collectionView.numberOfItems(inSection: 0)
        guard count > 0 else { return }
        switch kind {
        case .reveal(let target):
            guard let id = rows.indexById[target.rowId].map({ rows.rows[$0].id }),
                  let indexPath = dataSource.indexPath(for: id), indexPath.item < count else { return }
            let visible = collectionView.indexPathsForVisibleItems
            if visible.contains(where: { $0 == indexPath && isFullyVisible($0) }) {
                return
            }
            if target.onlyIfOffScreen, visible.contains(indexPath) { return }
            if indexPath.item == 0 {
                collectionView.scrollToTop(animated: false)
                return
            }
            let above = visible.map(\.item).min().map { indexPath.item < $0 } ?? true
            scrollToItem(indexPath, at: above ? .top : .bottom)
        case .freshEnd(true):
            collectionView.scrollToTop(animated: true)
        case .freshEnd(false):
            scrollToItem(IndexPath(item: count - 1, section: 0), at: .bottom)
        }
    }

    /// Scrolls `indexPath` to `position`, then once more after the rows it brought on screen have
    /// sized themselves: rows never shown have estimated heights, so the first scroll can land short.
    private func scrollToItem(_ indexPath: IndexPath, at position: UICollectionView.ScrollPosition) {
        collectionView.scrollToItem(at: indexPath, at: position, animated: false)
        collectionView.layoutIfNeeded()
        collectionView.scrollToItem(at: indexPath, at: position, animated: false)
    }

    private func isFullyVisible(_ indexPath: IndexPath) -> Bool {
        guard let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame else { return false }
        let inset = collectionView.adjustedContentInset
        let visible = collectionView.bounds.inset(by: UIEdgeInsets(top: inset.top, left: 0, bottom: inset.bottom, right: 0))
        return visible.contains(frame)
    }

    // MARK: - Window and visible rows

    /// The list was put into a window or taken out of one: out of one (under the reader), it reports no
    /// rows on screen, so an article arriving under the reader is not counted as seen.
    private func windowChanged() {
        if collectionView.window != nil {
            refreshVisibleCells()
        }
        scheduleReport()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        scheduleReport()
    }

    /// Coalesces a scroll's worth of changes into one report.
    private func scheduleReport() {
        reportTask?.cancel()
        reportTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(50))
            guard let self, !Task.isCancelled else { return }
            reportVisibleIds()
        }
    }

    private func reportVisibleIds() {
        let ids: [String]
        if collectionView.window == nil {
            ids = []
        } else {
            ids = collectionView.indexPathsForVisibleItems
                .sorted()
                .compactMap { dataSource.itemIdentifier(for: $0) }
        }
        guard ids != lastReportedIds else { return }
        lastReportedIds = ids
        input?.onVisibleIdsChanged(ids)
    }

    // MARK: - Swipe actions

    /// One action per edge — read/unread from the leading edge, star from the trailing one
    /// (`ArticleSwipeActionStyle`). Neither selects the row; both only change reading state, so a full
    /// swipe runs them without asking.
    private func swipeConfiguration(at indexPath: IndexPath, leading: Bool) -> UISwipeActionsConfiguration? {
        guard let row = row(at: indexPath) else { return nil }
        let model = row.model
        let action = leading ? ArticleSwipeActionStyle.leading(model) : ArticleSwipeActionStyle.trailing(model)
        let contextual = UIContextualAction(style: .normal, title: ArticleSwipeActionStyle.title(action)) { _, _, completion in
            row.viewModel.performSwipeAction(article: model.row, action: action)
            completion(true)
        }
        // Icon only, as on macOS: the title would widen the button; it stays the VoiceOver label.
        contextual.image = UIImage(systemName: ArticleSwipeActionStyle.systemImage(action))
        contextual.backgroundColor = ArticleSwipeActionStyle.uiColor(action)
        let configuration = UISwipeActionsConfiguration(actions: [contextual])
        configuration.performsFirstActionWithFullSwipe = true
        return configuration
    }

    // MARK: - Context menus

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfigurationForItemsAt indexPaths: [IndexPath],
        point: CGPoint
    ) -> UIContextMenuConfiguration? {
        guard indexPaths.count == 1, let row = row(at: indexPaths[0]) else { return nil }
        let cell = collectionView.cellForItem(at: indexPaths[0])
        contextMenuCell = cell
        return ArticleRowMenu.configuration(
            for: row.model,
            isSelected: row.isSelected,
            paneFocused: row.paneFocused,
            previewSize: cell?.bounds.size ?? .zero,
            viewModel: row.viewModel,
            onCopyUrl: row.onCopyUrl
        )
    }

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfiguration configuration: UIContextMenuConfiguration,
        highlightPreviewForItemAt indexPath: IndexPath
    ) -> UITargetedPreview? {
        collectionView.cellForItem(at: indexPath).flatMap(pressHighlight.preview(in:))
    }

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfiguration configuration: UIContextMenuConfiguration,
        dismissalPreviewForItemAt indexPath: IndexPath
    ) -> UITargetedPreview? {
        (collectionView.cellForItem(at: indexPath) ?? contextMenuCell).flatMap(pressHighlight.preview(in:))
    }

    /// The row is drawn entirely in the cell's content view (the selection fill included).
    func collectionView(
        _ collectionView: UICollectionView,
        willDisplayContextMenu configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = contextMenuCell?.contentView else { return }
        ContextMenuPressHighlight.hideRow(content, animator: animator)
    }

    func collectionView(
        _ collectionView: UICollectionView,
        willEndContextMenuInteraction configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = contextMenuCell?.contentView else { return }
        ContextMenuPressHighlight.showRow(content, animator: animator)
    }
}
#endif

#if os(iOS)
import KeryxShared
import SwiftUI
import UIKit

/// The iOS article list: a `UICollectionView` with one fixed row height, rather than SwiftUI's
/// `List`, so that no scroll is ever left pending against rows that may have changed by the time it
/// runs.
///
/// A `List` scrolls through `ScrollViewProxy.scrollTo`, which SwiftUI carries out in the list's next
/// layout — and when the rows have changed by then, `UICollectionView` raises an exception on the
/// target index path (v0.27.0 crashed that way going back from the reader; see "iOS: going back to
/// the article list crashed in `UICollectionView`" in `docs/known-issues.md`). Here a scroll is a
/// content offset worked out by `ArticleTableLayout` from the rows the list holds at that moment, set
/// synchronously, and clamped to the scrollable range: nothing waits, and no API that validates an
/// index path is used. Like the macOS `ArticleTableView`, every row has the same height (its title
/// always reserves two lines), so one sample row measures them all and a position is a row index
/// times that height.
///
/// The rows are `ArticleRowView`s (as in the macOS table), hosted one per visible cell
/// (`UIHostingConfiguration`): selection, taps and context menus are theirs. See "Article list (iOS)"
/// in `docs/app-architecture.md`.
struct ArticleCollectionView: UIViewControllerRepresentable {
    let rows: ArticleRowList
    let header: ArticleListHeader
    let scrollRequest: ArticleListScrollRequest
    /// Bumped each time the search query changes: the results go back to the top, including for the
    /// new results once they arrive.
    let resetGeneration: Int
    /// Whether pulling the list down refreshes (`PullToRefreshAvailability`).
    let canPullToRefresh: Bool
    /// Runs while the pull's indicator is up (`HomeObservable.pullToRefresh`).
    let onRefresh: () async -> Void
    let makeRow: (ArticleRowModel) -> ArticleRowView
    /// The ids of the rows on screen, in display order, whenever they change (debounced); empty while
    /// the list is not on screen.
    let onVisibleIdsChanged: ([String]) -> Void

    func makeUIViewController(context: Context) -> ArticleCollectionViewController {
        let controller = ArticleCollectionViewController(
            scrollGeneration: scrollRequest.generation, resetGeneration: resetGeneration
        )
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

/// A row as a cell shows it: `ArticleTableLayout.rowVerticalInset` above and below, the flash fade the
/// hosted row cannot get from `HomeView`'s `withAnimation`, and nothing taken from the window's safe
/// area, so a cell scrolled under the navigation bar keeps its fixed height.
struct ArticleCollectionCellContent: View {
    let row: ArticleRowView

    var body: some View {
        row
            .equatable()
            // Laid out at its own ideal height: given the cell's fixed
            // height as a proposal instead, a title that fits in exactly two lines is set on one.
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxHeight: .infinity, alignment: .top)
            .padding(.vertical, ArticleTableLayout.rowVerticalInset)
            // Fully on at once, fading out when it ends: the list's own idiom (`HomeView.returnFlashId`).
            .animation(row.isReturnFlashing ? nil : .easeOut(duration: HomeView.returnFlashFadeSeconds), value: row.isReturnFlashing)
            .ignoresSafeArea()
    }
}

@MainActor
final class ArticleCollectionViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegate {
    private var input: ArticleCollectionView?
    private var rows = ArticleRowList.empty
    private var collectionView: ArticleScrollingCollectionView!
    private var lastResetGeneration: Int
    private var scrollRequests: ScrollRequestTracker

    /// The height of every row, measured once the list is on screen; until then a stand-in.
    private var rowHeight: CGFloat = ArticleCollectionViewController.fallbackRowHeight
    private var rowHeightPolicy = RowHeightMeasurePolicy()
    private static let fallbackRowHeight: CGFloat = 90
    private static let estimatedHeaderHeight: CGFloat = 44
    /// How far the offset may sit from `logicalTop` before it counts as moved.
    private static let offsetTolerance: CGFloat = 0.5

    /// A scroll asked for before the list could carry it out (no size yet, or not in a window): only
    /// the latest is kept.
    private var pendingScroll: ArticleListScrollRequest.Kind?
    /// Set by a search query change until the new results arrive (or the user scrolls): they are shown
    /// from the top instead of anchored to whatever row the previous results had on screen.
    private var resetPending = false

    /// Where the list is, in `ArticleTableLayout`'s terms, as the user (or this controller) last
    /// left it. A list that is off screen — under the reader — has no window, so UIKit's own idea of
    /// its safe area, and with it the offset, is not to be trusted there; this is what it is put back
    /// to when it returns.
    private var logicalTop: CGFloat = 0
    /// Whether the list has been laid out, and its position put back, since it last came into a window.
    /// Until then UIKit moves the offset on its own (the insets change with the window), and such a move
    /// must not overwrite `logicalTop`.
    private var positionSettled = false
    /// The size and top inset of the last active layout pass: a scroll at any other size or inset is
    /// UIKit adjusting the offset to a resize, not a position to keep.
    private var lastLayoutSize: CGSize = .zero
    private var lastLayoutInsetTop: CGFloat = 0
    /// What a row change that landed while the list was off screen has to be positioned against once
    /// it is back: the position it had before the first such change.
    private var deferredPosition = DeferredPositionKeeper()

    private var reportTask: Task<Void, Never>?
    private var lastReportedIds: [String]?
    private var refreshTask: Task<Void, Never>?
    private var shownHeader: (title: String, icon: SidebarRowIcon)?

    init(scrollGeneration: Int, resetGeneration: Int) {
        scrollRequests = ScrollRequestTracker(generation: scrollGeneration)
        lastResetGeneration = resetGeneration
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = ArticleScrollingCollectionView(frame: view.bounds, collectionViewLayout: makeLayout())
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .clear
        // Arrow keys and J/K are `HomeView`'s, not UIKit's focus engine; a row's own button takes the tap.
        collectionView.allowsFocus = false
        collectionView.allowsSelection = false
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.onWindowChange = { [weak self] in self?.windowChanged() }
        makeRegistrations()
        view.addSubview(collectionView)
        registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (self: Self, _) in
            self.rowHeightMayHaveChanged()
        }
    }

    override func didMove(toParent parent: UIViewController?) {
        super.didMove(toParent: parent)
        // Lets the navigation bar (and its search field) follow this list's scrolling.
        parent?.setContentScrollView(collectionView)
    }

    func tearDown() {
        reportTask?.cancel()
        refreshTask?.cancel()
    }

    // MARK: - Layout

    private func makeLayout() -> UICollectionViewLayout {
        UICollectionViewCompositionalLayout { [weak self] _, _ in
            let size = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1), heightDimension: .absolute(self?.rowHeight ?? Self.fallbackRowHeight)
            )
            let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [NSCollectionLayoutItem(layoutSize: size)])
            let section = NSCollectionLayoutSection(group: group)
            // Pinned while the rows scroll under it.
            let header = NSCollectionLayoutBoundarySupplementaryItem(
                layoutSize: NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .estimated(Self.estimatedHeaderHeight)),
                elementKind: UICollectionView.elementKindSectionHeader,
                alignment: .top
            )
            header.pinToVisibleBounds = true
            header.zIndex = 2
            section.boundarySupplementaryItems = [header]
            return section
        }
    }

    /// Made in `viewDidLoad`: UIKit rejects a registration first created while a cell is being dequeued.
    /// (A `lazy var` would be created first inside `cellForItemAt`, which crashes.)
    private var cellRegistration: UICollectionView.CellRegistration<ArticleCell, Void>!
    private var headerRegistration: UICollectionView.SupplementaryRegistration<ArticleHeaderView>!

    private func makeRegistrations() {
        cellRegistration = UICollectionView.CellRegistration<ArticleCell, Void> { [weak self] cell, indexPath, _ in
            self?.configure(cell, at: indexPath.item)
        }
        headerRegistration = UICollectionView.SupplementaryRegistration<ArticleHeaderView>(
            elementKind: UICollectionView.elementKindSectionHeader
        ) { [weak self] view, _, _ in
            if let header = self?.input?.header { view.show(header) }
        }
    }

    // MARK: - Applying state

    /// Brings the list in line with `new`: the pull control and header, a search reset, the rows (the
    /// position kept by row id), and then any scroll asked for — always in that order, so a scroll is
    /// worked out against the rows the list has by then.
    func apply(_ new: ArticleCollectionView) {
        loadViewIfNeeded()
        input = new
        updateRefreshControl(enabled: new.canPullToRefresh)
        updateHeader(new.header)

        if new.resetGeneration != lastResetGeneration {
            lastResetGeneration = new.resetGeneration
            resetPending = true
            if isActive {
                setVisibleTop(0)
            } else {
                logicalTop = 0
            }
        }

        let old = rows
        rows = new.rows
        // The same generation is the same rows in the same order (`ArticleRowList.generation`), so
        // only a new one needs its ids compared.
        if old.generation != rows.generation,
           old.rows.count != rows.rows.count || !old.rows.elementsEqual(rows.rows, by: { $0.id == $1.id }) {
            applyStructureChange(from: old)
        } else {
            refreshVisibleCells()
        }

        if let kind = scrollRequests.take(new.scrollRequest) {
            pendingScroll = kind
            performPendingScroll()
        }
        scheduleReport()
    }

    /// Reloads for a new set or order of rows, keeping the top visible row where it was on screen — or
    /// going to the top after a search reset. Off screen, the position is put back once the list returns.
    private func applyStructureChange(from old: ArticleRowList) {
        deferredPosition.record(
            anchor: ArticleTableLayout.anchor(
                count: old.rows.count, id: { old.rows[$0].id }, visibleTop: currentTop, rowHeight: rowHeight
            ),
            oldIds: old.rows.map(\.id),
            reset: resetPending
        )
        collectionView.reloadData()
        if isActive, let change = deferredPosition.take() {
            collectionView.layoutIfNeeded()
            position(after: change)
        }
        resetPending = false
    }

    private func position(after change: DeferredPosition) {
        setVisibleTop(ArticleTableLayout.scrollOffset(
            oldIds: change.oldIds,
            newIndexById: rows.indexById,
            newCount: rows.rows.count,
            anchor: change.anchor,
            rowHeight: rowHeight,
            viewportHeight: viewportHeight(),
            resetToTop: change.reset
        ))
    }

    /// Re-renders only the rows on screen whose content changed (a read or star toggle, the selection
    /// moving) — nothing off screen has a cell to update.
    private func refreshVisibleCells() {
        for case let cell as ArticleCell in collectionView.visibleCells {
            guard let indexPath = collectionView.indexPath(for: cell) else { continue }
            configure(cell, at: indexPath.item)
        }
    }

    /// Shows the row at `index` in `cell`, if there is one.
    private func configure(_ cell: ArticleCell, at index: Int) {
        guard let input, rows.rows.indices.contains(index) else { return }
        cell.show(input.makeRow(rows.rows[index]))
    }

    private func updateHeader(_ header: ArticleListHeader) {
        defer { shownHeader = (header.title, header.icon) }
        guard let shown = shownHeader, shown.title != header.title || shown.icon != header.icon else { return }
        for case let view as ArticleHeaderView in collectionView.visibleSupplementaryViews(ofKind: UICollectionView.elementKindSectionHeader) {
            view.show(header)
        }
    }

    // MARK: - Pull to refresh

    private func updateRefreshControl(enabled: Bool) {
        guard enabled else {
            collectionView.refreshControl = nil
            // The control is gone, so a refresh it started has no spinner left to end.
            refreshTask?.cancel()
            refreshTask = nil
            return
        }
        guard collectionView.refreshControl == nil else { return }
        let control = UIRefreshControl()
        control.addAction(
            UIAction { [weak self] action in self?.pullToRefresh(from: action.sender as? UIRefreshControl) },
            for: .valueChanged
        )
        collectionView.refreshControl = control
    }

    /// The indicator of `control` — the one that was pulled — stays up for as long as the refresh runs,
    /// and ends with it, also when the wait is abandoned because another item was selected
    /// (`AbandonableRefresh` cancels only its inner wait, not this task). A task cancelled by a newer
    /// pull or by the control's removal ends nothing: the spinner is no longer this task's.
    private func pullToRefresh(from control: UIRefreshControl?) {
        refreshTask?.cancel()
        refreshTask = Task { @MainActor [weak self] in
            guard let self else { return }
            await input?.onRefresh()
            guard !Task.isCancelled else { return }
            control?.endRefreshing()
        }
    }

    // MARK: - Geometry

    /// Whether the list is in a window with a size — the only time its offsets and insets mean anything.
    private var isActive: Bool { collectionView.window != nil && collectionView.bounds.height > 0 }

    /// The top of the visible rows, measured from the first row's top edge (`ArticleTableLayout`): the
    /// content offset past the top inset, with the pinned header counted out of the viewport below.
    private var currentTop: CGFloat {
        isActive ? collectionView.contentOffset.y + collectionView.adjustedContentInset.top : logicalTop
    }

    /// The pinned header's height, read off the header on screen — the layout is never asked for its
    /// attributes, which raises an exception before the first rows have loaded. The last height seen
    /// stands in while there is no header on screen.
    private func refreshHeaderHeight() -> CGFloat {
        if let view = collectionView.visibleSupplementaryViews(ofKind: UICollectionView.elementKindSectionHeader).first,
           view.bounds.height > 0 {
            lastHeaderHeight = view.bounds.height
        }
        return lastHeaderHeight
    }
    private var lastHeaderHeight: CGFloat = 0

    /// The height of the rows' share of the screen: under the pinned header and the bars, not counted.
    private func viewportHeight() -> CGFloat {
        let inset = collectionView.adjustedContentInset
        return max(0, collectionView.bounds.height - inset.top - inset.bottom - refreshHeaderHeight())
    }

    private func setVisibleTop(_ top: CGFloat) {
        logicalTop = top
        let y = top - collectionView.adjustedContentInset.top
        guard collectionView.contentOffset.y != y else { return }
        collectionView.setContentOffset(CGPoint(x: collectionView.contentOffset.x, y: y), animated: false)
    }

    /// Measures a row on the sample row (`ArticleRowModel.measurementSample`) at `category`. The size is
    /// handed to the sample explicitly: inside a trait-change callback, a view freshly added to the
    /// hierarchy still inherits the previous content size category, so the sample would otherwise be
    /// measured one Dynamic Type step behind.
    private func measureRowHeight(category: UIContentSizeCategory) -> CGFloat? {
        guard let input else { return nil }
        let sample = ArticleRowModel.measurementSample(zone: Kotlinx_datetimeTimeZone.Companion.shared.currentSystemDefault())
        let dynamicTypeSize = DynamicTypeSize(category) ?? .large
        let probe = UIHostingConfiguration {
            ArticleCollectionCellContent(row: input.makeRow(sample))
                .environment(\.dynamicTypeSize, dynamicTypeSize)
        }
            .margins(.all, 0)
            .makeContentView()
        probe.traitOverrides.preferredContentSizeCategory = category
        probe.translatesAutoresizingMaskIntoConstraints = false
        collectionView.insertSubview(probe, at: 0)
        defer { probe.removeFromSuperview() }
        // Width pinned, height left to the content: an unbounded height proposal would let the row's
        // flexible star slot grow without limit.
        let width = CGFloat(ConstantsKt.ARTICLE_LIST_PANE_MIN_WIDTH)
        probe.widthAnchor.constraint(equalToConstant: width).isActive = true
        let size = probe.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        // Up to a whole pixel, not a whole point: a row a fraction of a point too tall adds up over a long list.
        let scale = collectionView.traitCollection.displayScale
        return size.height > 0 ? ceil(size.height * scale) / scale : nil
    }

    /// Measures the row height on first use and again when Dynamic Type changes, keeping the top visible
    /// row where it was on screen.
    private func rowHeightMayHaveChanged() {
        guard isActive else {
            rowHeightPolicy.windowLost()
            return
        }
        let sizeCategory = traitCollection.preferredContentSizeCategory
        let category = sizeCategory.rawValue
        guard rowHeightPolicy.needsMeasure(isActive: true, category: category),
              let height = measureRowHeight(category: sizeCategory) else { return }
        rowHeightPolicy.didMeasure(category: category)
        guard height != rowHeight else { return }
        let anchor = ArticleTableLayout.anchor(
            count: rows.rows.count, id: { rows.rows[$0].id }, visibleTop: currentTop, rowHeight: rowHeight
        )
        rowHeight = height
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()
        position(after: DeferredPosition(anchor: anchor, oldIds: rows.rows.map(\.id), reset: false))
    }

    // MARK: - Scrolling

    private func performPendingScroll() {
        guard let kind = pendingScroll, isActive, rowHeightPolicy.measured else { return }
        pendingScroll = nil
        collectionView.layoutIfNeeded()
        let offset: CGFloat?
        switch kind {
        case .reveal(let target):
            guard let index = rows.indexById[target.rowId] else { return }
            offset = ArticleTableLayout.revealOffset(
                index: index,
                count: rows.rows.count,
                rowHeight: rowHeight,
                visibleTop: currentTop,
                viewportHeight: viewportHeight(),
                anyPartCounts: target.onlyIfOffScreen
            )
        case .freshEnd(let newestFirst):
            offset = ArticleTableLayout.freshEndOffset(
                newestFirst: newestFirst, count: rows.rows.count, rowHeight: rowHeight, viewportHeight: viewportHeight()
            )
        }
        if let offset { setVisibleTop(offset) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard isActive else { return }
        rowHeightMayHaveChanged()
        if let deferred = deferredPosition.take() {
            position(after: deferred)
        } else if abs(currentTop - logicalTop) > Self.offsetTolerance, !collectionView.isTracking, !collectionView.isDecelerating {
            // UIKit moved the offset on its own when the list came back (its insets changed with the
            // window); put it back where the list was left, within the range the list can scroll at
            // its current size — a position left at another size (before a rotation) can lie past the end.
            setVisibleTop(ArticleTableLayout.clamped(
                max(0, logicalTop), count: rows.rows.count, rowHeight: rowHeight, viewportHeight: viewportHeight()
            ))
        }
        performPendingScroll()
        positionSettled = true
        // A resize (rotation, an iPad window resize, a bar changing height) can change which rows are on
        // screen without moving the offset, so no scroll callback would report them.
        if collectionView.bounds.size != lastLayoutSize || collectionView.adjustedContentInset.top != lastLayoutInsetTop {
            scheduleReport()
        }
        lastLayoutSize = collectionView.bounds.size
        lastLayoutInsetTop = collectionView.adjustedContentInset.top
    }

    /// The list was put into a window or taken out of one: out of one (under the reader), it reports no
    /// rows on screen, so an article arriving under the reader is not counted as seen.
    private func windowChanged() {
        lastReportedIds = nil
        if collectionView.window == nil {
            rowHeightPolicy.windowLost()
        } else {
            positionSettled = false
            // A list coming back keeps its size, so nothing would lay it out again: ask, since the row
            // height is measured, the position put back and a pending scroll carried out in that pass.
            view.setNeedsLayout()
        }
        scheduleReport()
    }

    // MARK: - UIScrollViewDelegate

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        resetPending = false
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        // The user's own scrolling always moves the remembered position. Any other scroll (UIKit's
        // `scrollRectToVisible` for VoiceOver focus, say) moves it only once the list is settled at the
        // size and inset of its last layout: UIKit's own offset changes while the list is attached,
        // detached or resized are not a position to keep.
        let userScrolled = scrollView.isTracking || scrollView.isDecelerating
        let settledAtLastLayout = positionSettled && scrollView.bounds.size == lastLayoutSize
            && scrollView.adjustedContentInset.top == lastLayoutInsetTop
        if userScrolled || settledAtLastLayout { rememberCurrentTop() }
        scheduleReport()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        rememberCurrentTop()
    }

    func scrollViewDidScrollToTop(_ scrollView: UIScrollView) {
        rememberCurrentTop()
    }

    /// Records where the list is now as the position to keep — only while it is in a window, where its
    /// offset means anything (`isActive`).
    private func rememberCurrentTop() {
        if isActive { logicalTop = currentTop }
    }

    // MARK: - Visible rows

    /// Coalesces a scroll's worth of changes into one report (`ArticleTableLayout.visibleRowsReportDelay`).
    private func scheduleReport() {
        reportTask?.cancel()
        reportTask = Task { [weak self] in
            try? await Task.sleep(for: ArticleTableLayout.visibleRowsReportDelay)
            guard !Task.isCancelled else { return }
            self?.reportVisibleIds()
        }
    }

    private func reportVisibleIds() {
        let ids: [String]
        if isActive, rowHeightPolicy.measured {
            ids = ArticleTableLayout.visibleRows(
                count: rows.rows.count, rowHeight: rowHeight, visibleTop: currentTop, viewportHeight: viewportHeight()
            ).map { rows.rows[$0].id }
        } else {
            ids = []
        }
        guard ids != lastReportedIds else { return }
        lastReportedIds = ids
        input?.onVisibleIdsChanged(ids)
    }

    // MARK: - UICollectionViewDataSource

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        rows.rows.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: ())
    }

    func collectionView(
        _ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath
    ) -> UICollectionReusableView {
        collectionView.dequeueConfiguredReusableSupplementary(using: headerRegistration, for: indexPath)
    }
}

/// Tells its controller when it is put into a window or taken out of one.
private final class ArticleScrollingCollectionView: UICollectionView {
    var onWindowChange: (() -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChange?()
    }
}

/// One reusable cell, hosting the row's SwiftUI view.
private final class ArticleCell: UICollectionViewCell {
    private var shown: ArticleRowView?

    /// Shows `row`, re-rendering only when it differs from what the cell already shows.
    func show(_ row: ArticleRowView) {
        if let shown, shown == row { return }
        shown = row
        contentConfiguration = UIHostingConfiguration { ArticleCollectionCellContent(row: row) }.margins(.all, 0)
    }
}

/// The pinned header: `ArticleListHeader` on the list's own opaque background, which the rows scroll
/// under.
private final class ArticleHeaderView: UICollectionReusableView {
    private var content: (UIView & UIContentView)?

    func show(_ header: ArticleListHeader) {
        let configuration = UIHostingConfiguration {
            header
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .systemBackground))
                .foregroundStyle(.primary)
        }
        .margins(.all, 0)
        if let content {
            content.configuration = configuration
            return
        }
        let view = configuration.makeContentView()
        view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(view)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: topAnchor),
            view.bottomAnchor.constraint(equalTo: bottomAnchor),
            view.leadingAnchor.constraint(equalTo: leadingAnchor),
            view.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        content = view
    }
}
#endif

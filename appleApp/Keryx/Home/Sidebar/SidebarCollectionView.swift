#if os(iOS)
import KeryxShared
import SwiftUI
import UIKit

/// Everything the iOS sidebar renders, as plain values — rebuilt by `FeedListView.body` on every
/// change it observes and handed to the collection view, which works out what actually changed.
struct SidebarRenderState: Equatable {
    let outline: SidebarOutline
    let contents: [SidebarItemID: SidebarRowContent]
    /// The row shown as selected — `nil` while the collapsed sidebar is the topmost column (see
    /// `CompactSidebarSelection.displayedKey`).
    let selectedItem: SidebarItemID?
    let renamingKey: String?
    let colorPickingTagId: String?
}

/// What the collection view asks of `FeedListView` — the same operations the macOS source list
/// performs through its bindings and SwiftUI menus.
@MainActor
struct SidebarCollectionActions {
    /// A row was tapped.
    var select: (SidebarItemID) -> Void
    /// A disclosure was toggled by the user.
    var setExpanded: (SidebarItemID, Bool) -> Void
    var menu: (SidebarItemID) -> UIMenu?
    /// A row's context menu is about to show.
    var menuWillOpen: (SidebarItemID) -> Void
    /// The row's in-place name editor, while it is being renamed.
    var editor: (SidebarItemID) -> InlineRenameField?
    var showColorPicker: (_ tagId: String) -> Void
    var pickColor: (_ tagId: String, _ hex: String?) -> Void
    var dismissColorPicker: () -> Void
    /// The shared lookup tables the drop rules resolve against.
    var dropIndex: () -> FeedListDropIndex
    /// Applies a drop the shared rules resolved (`applyFeedListDropAction`).
    var applyDrop: (FeedListDropAction) -> Void
}

/// The iOS sidebar: a `UICollectionView` list rather than SwiftUI's `List`, so the sidebar owns its
/// collection view's delegates — see "Sidebar (iOS)" in `docs/app-architecture.md`.
struct SidebarCollectionView: UIViewControllerRepresentable {
    let state: SidebarRenderState
    let actions: SidebarCollectionActions

    func makeUIViewController(context: Context) -> SidebarCollectionViewController {
        let controller = SidebarCollectionViewController(actions: actions)
        controller.apply(state)
        return controller
    }

    func updateUIViewController(_ controller: SidebarCollectionViewController, context: Context) {
        controller.actions = actions
        controller.apply(state)
    }
}

final class SidebarCollectionViewController: UIViewController, UICollectionViewDelegate,
    UIPopoverPresentationControllerDelegate {
    var actions: SidebarCollectionActions
    private(set) var state: SidebarRenderState?

    private(set) var collectionView: UICollectionView!
    private(set) var dataSource: UICollectionViewDiffableDataSource<SidebarSection, SidebarItemID>!

    /// Disclosures the user just toggled, until the shared state catches up — the view model
    /// publishes the new folder/tag state asynchronously, and an update in between (an unread count
    /// ticking) must not collapse the row back.
    private var pendingExpansion: [SidebarItemID: (expanded: Bool, since: ContinuousClock.Instant)] = [:]
    private static let pendingExpansionTimeout: Duration = .seconds(2)

    private weak var colorPicker: UIViewController?

    /// Whether a drag from this list is under way — see `apply(_:force:)`.
    var dragInProgress = false
    /// The latest state handed over mid-drag, applied when the drag ends.
    private(set) var deferredState: SidebarRenderState?

    init(actions: SidebarCollectionActions) {
        self.actions = actions
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: makeLayout())
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Arrow keys are `HomeView`'s (the same shortcuts as macOS), not UIKit's focus engine.
        collectionView.allowsFocus = false
        collectionView.delegate = self
        collectionView.dragDelegate = self
        collectionView.dropDelegate = self
        // Off by default on iPhone.
        collectionView.dragInteractionEnabled = true
        view.addSubview(collectionView)
        dataSource = makeDataSource()
        registerForTraitChanges([UITraitHorizontalSizeClass.self]) { (self: Self, _) in
            // The list appearance follows the width (inset grouped on iPhone, sidebar on iPad).
            self.collectionView.collectionViewLayout.invalidateLayout()
            self.reconfigureVisibleCells()
        }
    }

    override func didMove(toParent parent: UIViewController?) {
        super.didMove(toParent: parent)
        // Lets the navigation bar (and its search field) follow this list's scrolling, as it does
        // for a SwiftUI `List`.
        parent?.setContentScrollView(collectionView)
    }

    private var isCompact: Bool { traitCollection.horizontalSizeClass == .compact }

    // MARK: - Layout and cells

    private func makeLayout() -> UICollectionViewLayout {
        UICollectionViewCompositionalLayout { [weak self] sectionIndex, environment in
            let section = self?.dataSource?.sectionIdentifier(for: sectionIndex)
            var configuration = UICollectionLayoutListConfiguration(
                appearance: environment.traitCollection.horizontalSizeClass == .compact ? .insetGrouped : .sidebar
            )
            // A header is the section's first item, so it can be a drop destination; All/Starred
            // have none.
            configuration.headerMode = section == .smart || section == nil ? .none : .firstItemInSection
            return NSCollectionLayoutSection.list(using: configuration, layoutEnvironment: environment)
        }
    }

    private func makeDataSource() -> UICollectionViewDiffableDataSource<SidebarSection, SidebarItemID> {
        let header = UICollectionView.CellRegistration<UICollectionViewListCell, SidebarItemID> { [weak self] cell, _, item in
            self?.configureHeader(cell, item)
        }
        let row = UICollectionView.CellRegistration<UICollectionViewListCell, SidebarItemID> { [weak self] cell, _, item in
            self?.configureRow(cell, item)
        }
        let dataSource = UICollectionViewDiffableDataSource<SidebarSection, SidebarItemID>(collectionView: collectionView) { collectionView, indexPath, item in
            switch item {
            case .sectionHeader, .noFolderHeader:
                return collectionView.dequeueConfiguredReusableCell(using: header, for: indexPath, item: item)
            default:
                return collectionView.dequeueConfiguredReusableCell(using: row, for: indexPath, item: item)
            }
        }
        dataSource.sectionSnapshotHandlers.willExpandItem = { [weak self] item in self?.userToggled(item, expanded: true) }
        dataSource.sectionSnapshotHandlers.willCollapseItem = { [weak self] item in self?.userToggled(item, expanded: false) }
        return dataSource
    }

    private func configureHeader(_ cell: UICollectionViewListCell, _ item: SidebarItemID) {
        var content = isCompact ? UIListContentConfiguration.groupedHeader() : UIListContentConfiguration.sidebarHeader()
        content.text = state?.contents[item]?.title
        cell.contentConfiguration = content
        cell.accessories = item == .noFolderHeader ? [] : [.outlineDisclosure(options: .init(style: .header))]
        cell.accessibilityIdentifier = Self.accessibilityIdentifier(item)
        cell.configurationUpdateHandler = { cell, cellState in
            var background = UIBackgroundConfiguration.clear()
            if Self.isDropTarget(cellState) {
                background.backgroundColor = Self.selectionColor
                background.cornerRadius = 8
            }
            cell.backgroundConfiguration = background
        }
    }

    private func configureRow(_ cell: UICollectionViewListCell, _ item: SidebarItemID) {
        cell.accessibilityIdentifier = Self.accessibilityIdentifier(item)
        cell.configurationUpdateHandler = { [weak self] cell, cellState in
            guard let self, let cell = cell as? UICollectionViewListCell else { return }
            self.updateRow(cell, item, cellState)
        }
        cell.setNeedsUpdateConfiguration()
    }

    /// Keryx's teal for a selection (and a drop target) — darker than `AccentColor` in dark mode, so
    /// white text on it stays readable.
    private static let selectionColor = UIColor(named: "SelectionColor") ?? .tintColor

    /// Paints the row for its current state: the selection or a drop target (white on
    /// `SelectionColor`), the echo of the selected filter's other copies, or neither.
    private func updateRow(_ cell: UICollectionViewListCell, _ item: SidebarItemID, _ cellState: UICellConfigurationState) {
        guard let content = state?.contents[item] else { return }
        let dropTarget = Self.isDropTarget(cellState)
        let filled = dropTarget || cellState.isSelected
        var background = cell.defaultBackgroundConfiguration().updated(for: cellState)
        if filled {
            background.backgroundColor = Self.selectionColor
        } else if content.highlight == .echo, !cellState.isSelected, !cellState.isHighlighted {
            background.backgroundColor = UIColor.tintColor.withAlphaComponent(SidebarRowHighlight.echoAlpha)
        }
        cell.backgroundConfiguration = background

        // White on the teal fill; otherwise the text color a system list cell would use in this
        // state. Icons take it too, as the SwiftUI `List` sidebar drew them, rather than the accent
        // tint a plain `UIListContentConfiguration` would give them.
        let system = cell.defaultContentConfiguration().updated(for: cellState)
        let textColor = filled ? UIColor.white : system.textProperties.resolvedColor()

        var accessories: [UICellAccessory] = [
            .label(
                text: String(content.unreadCount),
                options: .init(isHidden: content.unreadCount == 0, tintColor: filled ? .white : nil)
            ),
        ]
        switch item {
        case .folder, .tag:
            // `.cell`: tapping the row selects it, only the chevron expands/collapses.
            accessories.append(.outlineDisclosure(options: .init(style: .cell, tintColor: filled ? .white : nil)))
        default:
            break
        }
        cell.accessories = accessories
        let editor = content.isRenaming ? actions.editor(item) : nil
        let onIconTap: (() -> Void)?
        if case .tag(let tagId) = item {
            onIconTap = { [weak self] in self?.actions.showColorPicker(tagId) }
        } else {
            onIconTap = nil
        }
        cell.contentConfiguration = UIHostingConfiguration {
            SidebarCellContent(
                content: content,
                editor: editor,
                color: Color(uiColor: textColor),
                onIconTap: onIconTap
            )
        }
    }

    private static func isDropTarget(_ cellState: UICellConfigurationState) -> Bool {
        cellState.cellDropState == .targeted
    }

    /// The row's selection key, or a fixed name for a header — for UI tests.
    static func accessibilityIdentifier(_ item: SidebarItemID) -> String {
        switch item {
        case .sectionHeader(let section): return "header:\(section.rawValue)"
        case .noFolderHeader: return "header:noFolder"
        default: return item.selectionKey ?? ""
        }
    }

    // MARK: - Applying state

    /// Brings the list in line with `state` in three steps: the structure (only the section
    /// snapshots that differ), the contents (reconfiguring changed cells in place, which keeps their
    /// scroll position and the SwiftUI state inside them, e.g. a half-typed name), then the
    /// selection.
    func apply(_ newState: SidebarRenderState, force: Bool = false) {
        // Mid-drag, UIKit's own placeholder and gap own the layout; the drop's result (and anything
        // else that changed meanwhile) is applied once the drag ends.
        if dragInProgress, !force {
            deferredState = newState
            return
        }
        deferredState = nil
        loadViewIfNeeded()
        let old = state
        guard force || newState != old else { return }
        state = newState

        applyStructure(newState.outline, animated: old != nil)
        reconfigure(SidebarRowContent.changedItems(from: old?.contents ?? [:], to: newState.contents))
        applySelection(newState.selectedItem, scroll: newState.selectedItem != old?.selectedItem)
        if newState.renamingKey != old?.renamingKey, let key = newState.renamingKey,
           let item = newState.outline.allItems.first(where: { $0.selectionKey == key }) {
            scrollIntoView(item)
        }
        if newState.colorPickingTagId != old?.colorPickingTagId {
            // Presenting from inside a SwiftUI update is not allowed; do it right after.
            DispatchQueue.main.async { [weak self] in self?.updateColorPicker(newState.colorPickingTagId) }
        }
    }

    private func applyStructure(_ outline: SidebarOutline, animated: Bool) {
        let sections = outline.sections.map(\.section)
        if dataSource.snapshot().sectionIdentifiers != sections {
            var snapshot = NSDiffableDataSourceSnapshot<SidebarSection, SidebarItemID>()
            snapshot.appendSections(sections)
            dataSource.apply(snapshot, animatingDifferences: false)
        }
        let now = ContinuousClock.now
        let expanded = Set(outline.sections.flatMap(\.expanded))
        pendingExpansion = pendingExpansion.filter { item, pending in
            now - pending.since < Self.pendingExpansionTimeout && expanded.contains(item) != pending.expanded
        }
        // Applied in `outline.sections` order, the order the drop resolution numbers sections in.
        for section in outline.sections {
            let snapshot = sectionSnapshot(section)
            guard !Self.sameStructure(dataSource.snapshot(for: section.section), snapshot) else { continue }
            dataSource.apply(snapshot, to: section.section, animatingDifferences: animated)
        }
    }

    private func sectionSnapshot(_ section: SidebarOutline.Section) -> NSDiffableDataSourceSectionSnapshot<SidebarItemID> {
        var snapshot = NSDiffableDataSourceSectionSnapshot<SidebarItemID>()
        func append(_ nodes: [SidebarOutline.Node], to parent: SidebarItemID?) {
            snapshot.append(nodes.map(\.id), to: parent)
            for node in nodes where !node.children.isEmpty {
                append(node.children, to: node.id)
            }
        }
        append(section.nodes, to: nil)
        var expanded = section.expanded
        for (item, pending) in pendingExpansion {
            if pending.expanded { expanded.insert(item) } else { expanded.remove(item) }
        }
        snapshot.expand(snapshot.items.filter(expanded.contains))
        return snapshot
    }

    private static func sameStructure(
        _ a: NSDiffableDataSourceSectionSnapshot<SidebarItemID>,
        _ b: NSDiffableDataSourceSectionSnapshot<SidebarItemID>
    ) -> Bool {
        a.items == b.items && a.visibleItems == b.visibleItems
            && a.items.allSatisfy { a.parent(of: $0) == b.parent(of: $0) && a.isExpanded($0) == b.isExpanded($0) }
    }

    private func reconfigure(_ items: Set<SidebarItemID>) {
        for item in items {
            guard let indexPath = dataSource.indexPath(for: item),
                  let cell = collectionView.cellForItem(at: indexPath) as? UICollectionViewListCell else { continue }
            configure(cell, item)
        }
    }

    private func reconfigureVisibleCells() {
        for indexPath in collectionView.indexPathsForVisibleItems {
            guard let item = dataSource.itemIdentifier(for: indexPath),
                  let cell = collectionView.cellForItem(at: indexPath) as? UICollectionViewListCell else { continue }
            configure(cell, item)
        }
    }

    private func configure(_ cell: UICollectionViewListCell, _ item: SidebarItemID) {
        switch item {
        case .sectionHeader, .noFolderHeader: configureHeader(cell, item)
        default: configureRow(cell, item)
        }
    }

    private func applySelection(_ item: SidebarItemID?, scroll: Bool) {
        let target = item.flatMap(dataSource.indexPath(for:))
        let selected = collectionView.indexPathsForSelectedItems ?? []
        if selected != target.map({ [$0] }) ?? [] {
            for indexPath in selected where indexPath != target {
                collectionView.deselectItem(at: indexPath, animated: false)
            }
            if let target { collectionView.selectItem(at: target, animated: false, scrollPosition: []) }
        }
        // Only when the selection actually moved (arrow keys, a restored selection) — an
        // already-visible row, e.g. one just tapped, never jumps.
        if scroll, let item { scrollIntoView(item) }
    }

    private func scrollIntoView(_ item: SidebarItemID) {
        guard let indexPath = dataSource.indexPath(for: item),
              let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame else { return }
        let visible = collectionView.bounds.inset(by: collectionView.adjustedContentInset)
        guard !visible.contains(frame) else { return }
        collectionView.scrollToItem(at: indexPath, at: .centeredVertically, animated: true)
    }

    private func userToggled(_ item: SidebarItemID, expanded: Bool) {
        pendingExpansion[item] = (expanded, .now)
        actions.setExpanded(item, expanded)
    }

    /// Opens a collapsed folder mid-drag (spring loading) — shown at once, without waiting for the
    /// drag to end.
    func springOpen(_ folderId: String) {
        userToggled(.folder(folderId), expanded: true)
        if let latest = deferredState ?? state { apply(latest, force: true) }
    }

    // MARK: - Selection

    func collectionView(_ collectionView: UICollectionView, shouldSelectItemAt indexPath: IndexPath) -> Bool {
        guard let item = dataSource.itemIdentifier(for: indexPath) else { return false }
        switch item {
        // Tapping a header toggles it (its disclosure is header-style) rather than selecting it.
        case .sectionHeader, .noFolderHeader: return true
        default: return state?.contents[item]?.isRenaming != true
        }
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard let item = dataSource.itemIdentifier(for: indexPath) else { return }
        guard item.selectionKey != nil else {
            // A header: keep the shared selection where it was.
            applySelection(state?.selectedItem, scroll: false)
            return
        }
        // Ends an in-place rename in another row, which commits it — as clicking another row does
        // on macOS.
        view.endEditing(true)
        actions.select(item)
    }

    // MARK: - Context menus

    /// A long press opens the menu; moving the finger instead lifts the row for a drag.
    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfigurationForItemsAt indexPaths: [IndexPath],
        point: CGPoint
    ) -> UIContextMenuConfiguration? {
        guard indexPaths.count == 1,
              let item = dataSource.itemIdentifier(for: indexPaths[0]),
              state?.contents[item]?.isRenaming != true,
              let key = item.selectionKey,
              let menu = actions.menu(item) else { return nil }
        return UIContextMenuConfiguration(identifier: key as NSString, previewProvider: nil) { _ in menu }
    }

    /// Opening the menu selects the row first, matching Compose's own
    /// `onOpen = { if (!selected) onClick() }` (`FeedListDragAndDrop.kt`) — only once the menu
    /// really shows, not when UIKit merely asks for it at the start of a press that may turn into a
    /// drag.
    func collectionView(
        _ collectionView: UICollectionView,
        willDisplayContextMenu configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let key = configuration.identifier as? String,
              let item = state?.outline.allItems.first(where: { $0.selectionKey == key }) else { return }
        actions.menuWillOpen(item)
    }

    // MARK: - Tag color popover

    private func updateColorPicker(_ tagId: String?) {
        if let colorPicker {
            colorPicker.dismiss(animated: true)
            self.colorPicker = nil
        }
        guard let tagId, let state else { return }
        let item = SidebarItemID.tag(tagId)
        guard let indexPath = dataSource.indexPath(for: item) else {
            actions.dismissColorPicker()
            return
        }
        collectionView.scrollToItem(at: indexPath, at: .centeredVertically, animated: false)
        collectionView.layoutIfNeeded()
        guard let cell = collectionView.cellForItem(at: indexPath) else {
            actions.dismissColorPicker()
            return
        }
        var selectedHex: String?
        if case .tagColor(let hex) = state.contents[item]?.icon { selectedHex = hex }
        let picker = UIHostingController(rootView: TagColorPicker(selectedHex: selectedHex) { [weak self] hex in
            self?.actions.pickColor(tagId, hex)
        })
        picker.modalPresentationStyle = .popover
        picker.preferredContentSize = picker.sizeThatFits(in: CGSize(width: CGFloat.greatestFiniteMagnitude, height: 200))
        if let popover = picker.popoverPresentationController {
            popover.sourceView = cell.contentView
            // The color dot sits at the row's leading edge.
            let leading = cell.contentView.directionalLayoutMargins.leading
            popover.sourceRect = CGRect(x: leading, y: 0, width: 20, height: cell.contentView.bounds.height)
            popover.delegate = self
        }
        present(picker, animated: true)
        colorPicker = picker
    }

    /// A popover even on iPhone, like the macOS dot's — not a sheet for a single row of swatches.
    func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle {
        .none
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        colorPicker = nil
        actions.dismissColorPicker()
    }
}

/// A row's hosted content — the shared `SidebarRowLabel` in the colors of the cell's current state.
private struct SidebarCellContent: View {
    let content: SidebarRowContent
    let editor: InlineRenameField?
    let color: Color
    let onIconTap: (() -> Void)?

    var body: some View {
        SidebarRowLabel(
            title: content.title,
            icon: content.icon ?? .symbol("circle"),
            isErroring: content.isErroring,
            isGone: content.isGone,
            editor: editor,
            onIconTap: onIconTap,
            symbolTint: color
        )
        .foregroundStyle(color)
        .frame(maxWidth: .infinity, alignment: .leading)
        // One element per row for VoiceOver, except while the name editor needs its own.
        .accessibilityElement(children: editor == nil ? .combine : .contain)
    }
}
#endif

#if os(iOS)
import KeryxShared
import os
import SwiftUI
import UIKit

/// The iOS article row's long-press menu: a `UIContextMenuInteraction` on the `List`'s own cell, in
/// place of SwiftUI's `.contextMenu`.
///
/// With `.contextMenu` (or a UIKit menu whose press highlight is the row), a swipe recognized more than
/// about 375 ms after the finger landed starts with the row's leading edge cut away and the row
/// standing still, then jumping to the finger — the press highlight keeps the row drawn where it was
/// while the swipe moves the cell. So the press highlight here is a clear view the size of the cell,
/// which has nothing to leave behind, and the menu's preview shows the row itself
/// (`ArticleRowLabel`). See "iOS: an article row jumps when a swipe starts" in
/// `docs/known-issues.md`.
///
/// The cell is found by walking up from this view, which relies on SwiftUI's `List` being backed by a
/// `UICollectionView`. If no cell is found, the row has no menu and a fault is logged once.
struct ArticleRowContextMenu: UIViewRepresentable {
    let source: ArticleRowMenuSource

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.isUserInteractionEnabled = false
        view.source = source
        return view
    }

    func updateUIView(_ view: InstallerView, context: Context) {
        view.source = source
    }

    /// Attaches the cell's interaction when put into a window, and keeps it pointed at the row this
    /// view is drawn for — a reused cell can show another row through the same view.
    final class InstallerView: UIView {
        var source: ArticleRowMenuSource? {
            didSet { menuDelegate?.source = source }
        }
        private weak var menuDelegate: ArticleRowMenuDelegate?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil else { return }
            var view = superview
            while let candidate = view, !(candidate is UICollectionViewCell) { view = candidate.superview }
            guard let cell = view as? UICollectionViewCell else {
                ArticleRowContextMenu.reportMissingCell()
                return
            }
            let interaction = cell.interactions.lazy.compactMap { $0 as? ArticleRowMenuInteraction }.first
                ?? ArticleRowMenuInteraction.install(on: cell)
            interaction.menuDelegate.source = source
            menuDelegate = interaction.menuDelegate
        }
    }

    private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Keryx", category: "ArticleRowContextMenu")
    private static var reportedMissingCell = false

    private static func reportMissingCell() {
        guard !reportedMissingCell else { return }
        reportedMissingCell = true
        logger.fault("No UICollectionViewCell above an article row: the row has no long-press menu")
    }
}

/// What the row menu needs from the row a cell currently shows.
struct ArticleRowMenuSource {
    let model: ArticleRowModel
    let isSelected: Bool
    let paneFocused: Bool
    let viewModel: HomeViewModel
    /// The shared article-URL copy handler (`HomeObservable.copyArticleUrl`).
    let onCopyUrl: () -> Void
}

/// The one interaction a cell carries, found again when the cell is reused.
final class ArticleRowMenuInteraction: UIContextMenuInteraction {
    let menuDelegate: ArticleRowMenuDelegate

    private init(menuDelegate: ArticleRowMenuDelegate) {
        self.menuDelegate = menuDelegate
        super.init(delegate: menuDelegate)
    }

    static func install(on cell: UICollectionViewCell) -> ArticleRowMenuInteraction {
        let interaction = ArticleRowMenuInteraction(menuDelegate: ArticleRowMenuDelegate(cell: cell))
        cell.addInteraction(interaction)
        return interaction
    }
}

final class ArticleRowMenuDelegate: NSObject, UIContextMenuInteractionDelegate {
    private weak var cell: UICollectionViewCell?
    var source: ArticleRowMenuSource?

    /// The press highlight's target: clear, the size of the cell, and moving with it, so a press that
    /// turns into a swipe leaves nothing behind (see `ArticleRowContextMenu`).
    private lazy var highlightView: UIView = {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return view
    }()

    init(cell: UICollectionViewCell) {
        self.cell = cell
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
        guard let source else { return nil }
        let model = source.model
        // An iOS long-press never selects the row, so the menu is the row's current state.
        let selectedByOpen = ArticleRowMenuState.opensBySelecting(isSelected: source.isSelected, pointerIsOver: false)
        let items = ArticleRowMenuState.items(for: model, selectedByOpen: selectedByOpen)
        let size = cell?.bounds.size ?? .zero
        return UIContextMenuConfiguration(identifier: nil, previewProvider: {
            let preview = UIHostingController(rootView: ArticleRowLabel(
                model: model,
                isSelected: source.isSelected,
                isReturnFlashing: false,
                paneFocused: source.paneFocused
            ))
            preview.preferredContentSize = size
            return preview
        }, actionProvider: { _ in
            Self.menu(items) { action in
                ArticleRowMenu.perform(action, on: model, viewModel: source.viewModel, onCopyUrl: source.onCopyUrl)
            }
        })
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configuration: UIContextMenuConfiguration,
        highlightPreviewForItemWithIdentifier identifier: any NSCopying
    ) -> UITargetedPreview? {
        clearPreview()
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configuration: UIContextMenuConfiguration,
        dismissalPreviewForItemWithIdentifier identifier: any NSCopying
    ) -> UITargetedPreview? {
        clearPreview()
    }

    /// UIKit hides the view a menu's highlight targets while the menu is up; here that is the clear
    /// view, so the row itself is hidden instead — otherwise it shows beside the preview where the
    /// background is only dimmed, not blurred (iPad).
    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willDisplayMenuFor configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = cell?.contentView else { return }
        if let animator {
            animator.addAnimations { content.alpha = 0 }
        } else {
            content.alpha = 0
        }
    }

    /// Shows the row again once the preview has gone back into its place.
    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willEndFor configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = cell?.contentView else { return }
        if let animator {
            animator.addCompletion { content.alpha = 1 }
        } else {
            content.alpha = 1
        }
    }

    private func clearPreview() -> UITargetedPreview? {
        guard let cell, cell.window != nil else { return nil }
        if highlightView.superview !== cell {
            cell.addSubview(highlightView)
        }
        highlightView.frame = cell.bounds
        let parameters = UIPreviewParameters()
        parameters.backgroundColor = .clear
        return UITargetedPreview(view: highlightView, parameters: parameters)
    }

    /// The items as a `UIMenu`, each group (`ArticleRowMenuItem.startsGroup`) an inline section so the
    /// menu draws the same separators as the SwiftUI menu.
    private static func menu(
        _ items: [ArticleRowMenuItem],
        perform: @escaping (ArticleRowMenuItem.Action) -> Void
    ) -> UIMenu {
        var groups: [[UIAction]] = []
        for item in items {
            if item.startsGroup || groups.isEmpty { groups.append([]) }
            groups[groups.count - 1].append(UIAction(
                title: item.title,
                attributes: item.isEnabled ? [] : .disabled
            ) { _ in perform(item.action) })
        }
        return UIMenu(children: groups.map { UIMenu(options: .displayInline, children: $0) })
    }
}
#endif

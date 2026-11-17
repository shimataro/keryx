#if os(iOS)
import KeryxShared
import os
import SwiftUI
import UIKit

/// The iOS article row's long-press menu: a `UIContextMenuInteraction` on the `List`'s own cell, in
/// place of SwiftUI's `.contextMenu`.
///
/// SwiftUI's `.contextMenu` highlights the row itself, which makes a swipe start with a jump; this
/// menu's highlight is `ContextMenuPressHighlight`'s clear view instead, and its preview shows the row
/// (`ArticleRowLabel`).
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
    private let pressHighlight = ContextMenuPressHighlight()

    init(cell: UICollectionViewCell) {
        self.cell = cell
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
        guard let source else { return nil }
        return ArticleRowMenu.configuration(
            for: source.model,
            isSelected: source.isSelected,
            paneFocused: source.paneFocused,
            previewSize: cell?.bounds.size ?? .zero,
            viewModel: source.viewModel,
            onCopyUrl: source.onCopyUrl
        )
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configuration: UIContextMenuConfiguration,
        highlightPreviewForItemWithIdentifier identifier: any NSCopying
    ) -> UITargetedPreview? {
        cell.flatMap(pressHighlight.preview(in:))
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configuration: UIContextMenuConfiguration,
        dismissalPreviewForItemWithIdentifier identifier: any NSCopying
    ) -> UITargetedPreview? {
        cell.flatMap(pressHighlight.preview(in:))
    }

    /// The row is drawn entirely in the cell's content view (the selection fill included).
    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willDisplayMenuFor configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = cell?.contentView else { return }
        ContextMenuPressHighlight.hideRow(content, animator: animator)
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willEndFor configuration: UIContextMenuConfiguration,
        animator: (any UIContextMenuInteractionAnimating)?
    ) {
        guard let content = cell?.contentView else { return }
        ContextMenuPressHighlight.showRow(content, animator: animator)
    }
}
#endif

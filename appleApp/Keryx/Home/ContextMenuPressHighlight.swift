#if os(iOS)
import UIKit

/// Keeps a list row's long-press menu from making a swipe start with a jump.
///
/// When a row's context menu highlights the row itself, a swipe recognized more than about 375 ms after
/// the finger landed starts with the row's leading edge cut away and the row standing still, then
/// jumping to the finger: the press highlight keeps the row drawn where it was while the swipe moves the
/// cell. This happens on a SwiftUI `List` and on a plain `UICollectionView` list alike. So the highlight
/// (and the dismissal) targets a clear view the size of the cell, which has nothing to leave behind, and
/// the menu's own preview shows the row instead. See "iOS: a row jumps when a swipe starts" in
/// `docs/known-issues.md`.
///
/// One instance per list: it holds the clear view, moves it to whichever cell is pressed, and hides
/// that cell's row while its menu is up. The list's `UICollectionViewDelegate` context-menu methods
/// only forward to it.
@MainActor
final class ContextMenuPressHighlight {
    /// What of a cell is the row to hide behind the menu's preview.
    private let rowView: (UICollectionViewCell) -> UIView
    /// The cell whose menu is opening or open.
    private weak var pressedCell: UICollectionViewCell?

    /// - Parameter rowView: what of a cell to hide while its menu is up — the content view when the row
    ///   is drawn there entirely, or the cell itself when its background and accessories are part of it.
    init(hiding rowView: @escaping (UICollectionViewCell) -> UIView) {
        self.rowView = rowView
    }

    private lazy var highlightView: UIView = {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return view
    }()

    /// Records the cell a menu is being opened for (`contextMenuConfigurationForItemsAt`).
    func pressed(_ cell: UICollectionViewCell?) {
        pressedCell = cell
    }

    /// The press highlight (`highlightPreviewForItemAt`).
    func highlightPreview(in cell: UICollectionViewCell?) -> UITargetedPreview? {
        cell.flatMap(preview(in:))
    }

    /// The dismissal (`dismissalPreviewForItemAt`), on the pressed cell when the item has none on
    /// screen any more.
    func dismissalPreview(in cell: UICollectionViewCell?) -> UITargetedPreview? {
        (cell ?? pressedCell).flatMap(preview(in:))
    }

    /// The menu is opening (`willDisplayContextMenu`).
    func menuWillDisplay(animator: (any UIContextMenuInteractionAnimating)?) {
        guard let pressedCell else { return }
        Self.hideRow(rowView(pressedCell), animator: animator)
    }

    /// The menu is closing (`willEndContextMenuInteraction`).
    func menuWillEnd(animator: (any UIContextMenuInteractionAnimating)?) {
        guard let pressedCell else { return }
        Self.showRow(rowView(pressedCell), animator: animator)
    }

    /// The preview for both the press highlight and the dismissal: the clear view, on `cell` and the
    /// same size, so it moves with the cell. `nil` (UIKit's default) for a cell not in a window.
    private func preview(in cell: UIView) -> UITargetedPreview? {
        guard cell.window != nil else { return nil }
        if highlightView.superview !== cell {
            cell.addSubview(highlightView)
        }
        highlightView.frame = cell.bounds
        let parameters = UIPreviewParameters()
        parameters.backgroundColor = .clear
        return UITargetedPreview(view: highlightView, parameters: parameters)
    }

    /// Hides the row while its menu is up. UIKit hides the view a menu's highlight targets; here that is
    /// the clear view, so the row itself would otherwise show beside the preview where the background is
    /// only dimmed, not blurred (iPad).
    private static func hideRow(_ row: UIView, animator: (any UIContextMenuInteractionAnimating)?) {
        if let animator {
            animator.addAnimations { row.alpha = 0 }
        } else {
            row.alpha = 0
        }
    }

    /// Brings the row back as the menu's preview goes away. Done within the dismissal rather than on its
    /// completion: with a clear dismissal target, iOS 17 and 18 complete the dismissal about half a
    /// second after the preview has gone, which would leave the row blank in between.
    private static func showRow(_ row: UIView, animator: (any UIContextMenuInteractionAnimating)?) {
        if let animator {
            animator.addAnimations { row.alpha = 1 }
        } else {
            row.alpha = 1
        }
    }
}
#endif

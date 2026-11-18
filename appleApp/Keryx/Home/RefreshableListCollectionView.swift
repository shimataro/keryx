#if os(iOS)
import UIKit

/// A list `UICollectionView` that keeps its refresh control visible. The list layout's section
/// background (a decoration view stretched far above the content to cover the overscroll) is drawn
/// over the control, so the pull revealed only that background while the control spun behind it
/// (iOS 26). The collection view orders its own subviews, so the control is lifted by layer depth
/// instead; it sits above the content, so it never covers a cell.
class RefreshableListCollectionView: UICollectionView {
    override var refreshControl: UIRefreshControl? {
        didSet { refreshControl?.layer.zPosition = 1 }
    }
}

/// A list's pull-to-refresh: the refresh control and the async action it runs — the one
/// implementation the sidebar and the article list both use, one instance per list.
@MainActor
final class ListPullToRefresh {
    /// The list the refresh control goes on, set once the list's view is loaded.
    weak var scrollView: UIScrollView?
    private var action: (@MainActor () async -> Void)?
    private var task: Task<Void, Never>?

    /// Pulling the list runs `action`, with the spinner up until it finishes; `nil` takes the refresh
    /// control away.
    func setAction(_ action: (@MainActor () async -> Void)?) {
        self.action = action
        guard let scrollView else { return }
        guard action != nil else {
            scrollView.refreshControl = nil
            // The control is gone, so a refresh it started has no spinner left to end.
            cancel()
            return
        }
        guard scrollView.refreshControl == nil else { return }
        let control = UIRefreshControl()
        control.addAction(
            UIAction { [weak self] action in self?.refresh(from: action.sender as? UIRefreshControl) },
            for: .valueChanged
        )
        scrollView.refreshControl = control
    }

    /// Abandons a refresh the list started, leaving its spinner to whoever removes the list.
    func cancel() {
        task?.cancel()
        task = nil
    }

    /// The indicator of `control` — the one that was pulled — stays up for as long as the refresh runs.
    /// A task cancelled by a newer pull or by the control's removal ends nothing: the spinner is no
    /// longer this task's.
    private func refresh(from control: UIRefreshControl?) {
        task?.cancel()
        guard let action else { return }
        task = Task { @MainActor in
            await action()
            guard !Task.isCancelled else { return }
            control?.endRefreshing()
        }
    }
}
#endif

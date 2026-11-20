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
        installControl(on: scrollView)
    }

    /// Call as the user starts dragging the list. Replaces the refresh control with a fresh one when
    /// UIKit has left it out of the window, so this pull shows its spinner.
    ///
    /// From iOS 26 the navigation bar hosts the refresh control of the scroll view it tracks in a
    /// container of its own, and a navigation transition can drop that container from the hierarchy
    /// without putting it back: the control stays the list's `refreshControl` and still starts
    /// refreshes, but spins out of sight. Re-assigning the same control keeps the zero width it was
    /// given while detached (the spinner shows half off the leading edge), so a new one goes in its
    /// place. Checked at drag start rather than on appearance, since when the container is dropped
    /// is not known. See "iOS: the pull-to-refresh spinner disappears after navigating" in
    /// `docs/known-issues.md`.
    func listWillBeginDragging() {
        guard action != nil, let scrollView, scrollView.window != nil,
              let control = scrollView.refreshControl, control.window == nil else { return }
        // The detached control's refresh, if any, has no visible spinner left to end.
        cancel()
        scrollView.refreshControl = nil
        installControl(on: scrollView)
    }

    private func installControl(on scrollView: UIScrollView) {
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

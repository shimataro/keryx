#if os(iOS)
import KeryxShared
import SwiftUI
import UIKit

/// What one pager page shows — resolved by `ArticlePagerView` from the selection and the held copies.
struct ReaderPageInput {
    let row: ArticleReaderRow?
    let revision: ObjectIdentifier?
    let feedName: String?
    let feedFaviconUrl: String?
}

/// The iOS reader's swipe pager itself: a `UIPageViewController` that asks for the page either side
/// of the one on screen (`ReaderPagerPages.id(before:)` / `id(after:)`) on every swipe.
///
/// Deliberately not a page-style `TabView`: that one is backed by a collection view whose item count
/// and current page SwiftUI updates separately, and when the page list changed under it (every
/// emission while a refresh or sync lands — routine right after the app comes back) a layout it was
/// forced into, such as the interactive pop back to the article list, could scroll to a page past the
/// end of its items, which UIKit treats as a fatal inconsistency. Asking for neighbours by id keeps no
/// count to fall out of step with.
///
/// The swipe physics — following the finger, settling, the rubber band past either end, telling a
/// horizontal swipe from the WebView's own vertical scroll — are the page view controller's own
/// paging scroll view.
struct ReaderPageController: UIViewControllerRepresentable {
    let pages: ReaderPagerPages
    let selectedId: String?
    /// The inputs of the pages kept alive around the selection (`ReaderPagerPages.retainedIds`),
    /// resolved by the caller's body so a change to any of them updates this view.
    let inputs: [String: ReaderPageInput]
    /// Resolves a page outside `inputs` — one a swipe reaches before the next update arrives.
    let input: (String) -> ReaderPageInput
    let preferences: PreferencesObservable
    /// The scroll position restored from before the app was terminated, offered to its own page only
    /// (`ReaderScrollPosition.initialFraction`).
    let restoredScroll: ReaderScrollPosition?
    /// A page's document was scrolled by the user: the page's id and the position.
    let onScroll: (String, Double) -> Void
    /// A swipe came to rest on this page; whether that made it the selection.
    let onSettle: (String) -> Bool

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(transitionStyle: .scroll, navigationOrientation: .horizontal)
        controller.dataSource = context.coordinator
        controller.delegate = context.coordinator
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        context.coordinator.update(self, in: controller)
    }

    @MainActor
    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        private var parent: ReaderPageController?
        /// The page views kept alive, by id — the displayed page and its neighbours.
        private var hosts: [String: ReaderPageHostingController] = [:]
        /// Whether a swipe is in progress; the displayed page is not replaced under the finger.
        private var isTransitioning = false
        /// The neighbours of the displayed page as the page view controller last learned them. It holds
        /// on to the neighbours it already asked for, so when the list changes them it is reset.
        private var knownNeighbours: (before: String?, after: String?)?
        /// The page a swipe just selected, until an update carries that selection: `parent` still
        /// holds the previous one until then, and must not pull the pager back to it.
        private var pendingSelectionId: String?

        func update(_ parent: ReaderPageController, in controller: UIPageViewController) {
            self.parent = parent
            for (id, host) in hosts {
                host.rootView = Self.page(id, of: parent)
            }
            guard !isTransitioning else { return }
            syncDisplayedPage(in: controller)
        }

        /// Shows the selection when the page on screen is another one (a selection made in the article
        /// list or with J/K, or the pager's first appearance), and resets the page view controller's
        /// neighbours when the list changed them, then lets go of every page view no longer kept.
        private func syncDisplayedPage(in controller: UIPageViewController) {
            guard let parent, let selectedId = parent.selectedId else { return }
            if let pending = pendingSelectionId {
                guard pending == selectedId else { return }
                pendingSelectionId = nil
            }
            let displayedId = (controller.viewControllers?.first as? ReaderPageHostingController)?.pageId
            let neighbours = (parent.pages.id(before: selectedId), parent.pages.id(after: selectedId))
            if displayedId != selectedId || knownNeighbours.map({ $0 != neighbours }) ?? true {
                controller.setViewControllers([host(for: selectedId, of: parent)], direction: .forward, animated: false)
                knownNeighbours = neighbours
            }
            prune(keeping: parent.pages.retainedIds(around: selectedId))
        }

        private func prune(keeping ids: Set<String>) {
            hosts = hosts.filter { ids.contains($0.key) }
        }

        private static func page(_ id: String, of parent: ReaderPageController) -> ReaderPageView {
            let input = parent.inputs[id] ?? parent.input(id)
            return ReaderPageView(
                row: input.row,
                revision: input.revision,
                feedName: input.feedName,
                feedFaviconUrl: input.feedFaviconUrl,
                preferences: parent.preferences,
                initialScrollFraction: parent.restoredScroll?.initialFraction(forPage: id, selectedId: parent.selectedId),
                onScroll: { [onScroll = parent.onScroll] in onScroll(id, $0) }
            )
        }

        private func host(for id: String, of parent: ReaderPageController) -> ReaderPageHostingController {
            if let host = hosts[id] { return host }
            let host = ReaderPageHostingController(pageId: id, rootView: Self.page(id, of: parent))
            hosts[id] = host
            return host
        }

        // MARK: UIPageViewControllerDataSource

        func pageViewController(_ controller: UIPageViewController, viewControllerBefore viewController: UIViewController) -> UIViewController? {
            guard let parent, let id = (viewController as? ReaderPageHostingController)?.pageId,
                  let before = parent.pages.id(before: id) else { return nil }
            return host(for: before, of: parent)
        }

        func pageViewController(_ controller: UIPageViewController, viewControllerAfter viewController: UIViewController) -> UIViewController? {
            guard let parent, let id = (viewController as? ReaderPageHostingController)?.pageId,
                  let after = parent.pages.id(after: id) else { return nil }
            return host(for: after, of: parent)
        }

        // MARK: UIPageViewControllerDelegate

        func pageViewController(_ controller: UIPageViewController, willTransitionTo pendingViewControllers: [UIViewController]) {
            isTransitioning = true
        }

        func pageViewController(
            _ controller: UIPageViewController,
            didFinishAnimating finished: Bool,
            previousViewControllers: [UIViewController],
            transitionCompleted completed: Bool
        ) {
            isTransitioning = false
            if completed, let parent, let id = (controller.viewControllers?.first as? ReaderPageHostingController)?.pageId {
                // The page view controller learned the new page's neighbours itself while settling.
                knownNeighbours = (parent.pages.id(before: id), parent.pages.id(after: id))
                if parent.onSettle(id) {
                    // The update carrying the new selection syncs the rest.
                    pendingSelectionId = id
                    return
                }
            }
            // Catches up with whatever changed while the finger was down — or, when the settled page
            // was not selected after all, returns to the selection.
            syncDisplayedPage(in: controller)
        }
    }
}

/// One page's view, remembering which article it shows.
final class ReaderPageHostingController: UIHostingController<ReaderPageView> {
    let pageId: String

    init(pageId: String, rootView: ReaderPageView) {
        self.pageId = pageId
        super.init(rootView: rootView)
        view.backgroundColor = .clear
    }

    @available(*, unavailable)
    @MainActor required dynamic init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }
}
#endif

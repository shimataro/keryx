#if os(iOS)
import SwiftUI
import UIKit

/// An invisible view that reports whether the navigation bar of the column it sits in has its
/// large title collapsed. Collapsed means the bar is shorter than it was when first measured at
/// its current width — the list starts at the top, title expanded. No fixed height, so Dynamic Type
/// does not throw it off; not the tallest height seen, since pulling the list down stretches the
/// bar past its resting height. A width change (rotation, a column resize) or a text-size change
/// starts the measurement over, since the bar's heights change with them.
/// See `ArticleListView.titleCollapsed` for why the article list needs to know.
struct NavigationBarCollapseProbe: UIViewRepresentable {
    let onChange: (Bool) -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.onChange = onChange
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onChange = onChange
    }

    final class ProbeView: UIView {
        var onChange: ((Bool) -> Void)?
        private var observation: NSKeyValueObservation?
        private var measuredWidth: CGFloat = 0
        private var expandedHeight: CGFloat?
        private var measuredSizeCategory: UIContentSizeCategory?
        private var collapsed = false

        override func didMoveToWindow() {
            super.didMoveToWindow()
            observation = nil
            guard window != nil, let bar = enclosingNavigationBar() else { return }
            observation = bar.observe(\.bounds, options: [.initial, .new]) { [weak self] bar, _ in
                MainActor.assumeIsolated { self?.update(bar.bounds.size) }
            }
        }

        private func update(_ size: CGSize) {
            let sizeCategory = traitCollection.preferredContentSizeCategory
            if size.width != measuredWidth || sizeCategory != measuredSizeCategory {
                measuredWidth = size.width
                measuredSizeCategory = sizeCategory
                expandedHeight = nil
            }
            guard size.height > 0 else { return }
            let expanded = expandedHeight ?? size.height
            expandedHeight = expanded
            let isCollapsed = size.height < expanded - 0.5
            guard isCollapsed != collapsed else { return }
            collapsed = isCollapsed
            onChange?(isCollapsed)
        }

        private func enclosingNavigationBar() -> UINavigationBar? {
            var responder: UIResponder? = self
            while let current = responder {
                if let controller = current as? UINavigationController { return controller.navigationBar }
                if let controller = current as? UIViewController, let nav = controller.navigationController {
                    return nav.navigationBar
                }
                responder = current.next
            }
            return nil
        }
    }
}
#endif

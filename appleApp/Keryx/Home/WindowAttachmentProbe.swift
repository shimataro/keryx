#if os(iOS)
import SwiftUI
import UIKit

/// An invisible view that reports each time it is put into a window — the moment its column is
/// actually being shown. SwiftUI's `onAppear` does not mark that for a phone-width split view
/// column: the article list's fires once at launch, while it is laid out off screen behind the
/// sidebar, and not on the push that shows it. See `ArticleListView.attachedToWindow`.
struct WindowAttachmentProbe: UIViewRepresentable {
    let onAttach: () -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.onAttach = onAttach
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onAttach = onAttach
    }

    final class ProbeView: UIView {
        var onAttach: (() -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { onAttach?() }
        }
    }
}
#endif

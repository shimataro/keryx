#if os(macOS)
import AppKit
import SwiftUI

/// Reports the `NSWindow` hosting the main scene's content, so `AppDelegate` knows exactly which
/// window is the main one instead of guessing from whichever window happens to become key first —
/// a guess that missed a window hidden before it ever became key ("start minimized") and could
/// latch onto the Settings window instead.
struct MainWindowReader: NSViewRepresentable {
    let onWindow: (NSWindow) -> Void

    func makeNSView(context: Context) -> ReaderView {
        let view = ReaderView()
        view.onWindow = onWindow
        return view
    }

    func updateNSView(_ nsView: ReaderView, context: Context) {
        nsView.onWindow = onWindow
    }

    final class ReaderView: NSView {
        var onWindow: ((NSWindow) -> Void)?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            // `nil` when the view is being removed from its window — nothing to report then;
            // `AppDelegate.mainWindow` is weak and clears itself once the window goes away.
            guard let window else { return }
            onWindow?(window)
        }
    }
}
#endif

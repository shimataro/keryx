#if os(iOS)
import SwiftUI
import UIKit

/// Applies `style` as the `overrideUserInterfaceStyle` of the `UIWindow` hosting this view — the
/// iOS counterpart of macOS's `NSApp.appearance`. The window override reaches every view controller
/// in that window, presented sheets included, the moment it changes, which `.preferredColorScheme`
/// does not (it reaches a sheet only when the sheet opens). Applying it from `didMoveToWindow`
/// rather than by enumerating `UIApplication.shared.connectedScenes` means it lands as soon as the
/// window exists, and each scene's window (an iPad can have several) is styled by its own content.
struct WindowInterfaceStyle: UIViewRepresentable {
    let style: UIUserInterfaceStyle

    func makeUIView(context: Context) -> StyleView {
        let view = StyleView()
        view.style = style
        return view
    }

    func updateUIView(_ uiView: StyleView, context: Context) {
        uiView.style = style
    }

    final class StyleView: UIView {
        var style: UIUserInterfaceStyle = .unspecified {
            didSet { applyStyle() }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyStyle()
        }

        private func applyStyle() {
            // `nil` before the view joins a window and while it is being removed from one —
            // `didMoveToWindow` applies it once the window is there.
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            window.overrideUserInterfaceStyle = style
        }
    }
}
#endif

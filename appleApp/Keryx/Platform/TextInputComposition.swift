#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Whether a text input is in the middle of an input-method composition (Japanese kana-kanji
/// conversion, say): it holds marked text that the IME has not committed yet. While it does, the
/// IME owns the keys — ↑/↓ move between its candidates and Return commits — so Home's own key
/// handling must leave them alone.
///
/// On iOS this only matters with a hardware keyboard (`onKeyPress` never sees the on-screen
/// keyboard). That Home's handler sees an iPad's keys before the IME there, as it does on macOS,
/// has not been reproduced; the check is applied there as a precaution, and is a no-op whenever
/// nothing is being composed.
@MainActor
enum TextInputComposition {
    #if os(macOS)
    /// Whether `responder` is a text input holding uncommitted marked text. A search field's or a
    /// rename field's field editor is an `NSTextView`, which is an `NSTextInputClient`.
    static func isComposing(_ responder: NSResponder?) -> Bool {
        (responder as? NSTextInputClient)?.hasMarkedText() ?? false
    }

    /// Whether the key window's first responder — where the next key goes — is composing.
    static var isComposingInKeyWindow: Bool {
        isComposing(NSApp.keyWindow?.firstResponder)
    }
    #else
    /// Whether `responder` is a text input holding uncommitted marked text. The search field
    /// (`UISearchTextField`) and a row's name editor are `UITextInput`s.
    static func isComposing(_ responder: UIResponder?) -> Bool {
        (responder as? UITextInput)?.markedTextRange != nil
    }

    /// Whether the first responder — where the next key goes — is composing.
    static var isComposingInKeyWindow: Bool {
        isComposing(currentFirstResponder())
    }

    /// Set by `UIResponder.keryx_captureFirstResponder` for the duration of `currentFirstResponder`.
    fileprivate static var capturedFirstResponder: UIResponder?

    /// UIKit has no public accessor for the first responder; an action sent to a `nil` target is
    /// delivered to it, so it records itself.
    private static func currentFirstResponder() -> UIResponder? {
        capturedFirstResponder = nil
        UIApplication.shared.sendAction(
            #selector(UIResponder.keryx_captureFirstResponder), to: nil, from: nil, for: nil
        )
        defer { capturedFirstResponder = nil }
        return capturedFirstResponder
    }
    #endif
}

#if os(iOS)
extension UIResponder {
    /// The target of `TextInputComposition.currentFirstResponder`'s nil-targeted action.
    @objc fileprivate func keryx_captureFirstResponder() {
        TextInputComposition.capturedFirstResponder = self
    }
}
#endif

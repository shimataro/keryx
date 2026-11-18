#if os(macOS)
import AppKit

/// Whether a text input is in the middle of an input-method composition (Japanese kana-kanji
/// conversion, say): it holds marked text that the IME has not committed yet. While it does, the
/// IME owns the keys — ↑/↓ move between its candidates and Return commits — so Home's own key
/// handling must leave them alone.
@MainActor
enum TextInputComposition {
    /// Whether `responder` is a text input holding uncommitted marked text. A search field's or a
    /// rename field's field editor is an `NSTextView`, which is an `NSTextInputClient`.
    static func isComposing(_ responder: NSResponder?) -> Bool {
        (responder as? NSTextInputClient)?.hasMarkedText() ?? false
    }

    /// Whether the key window's first responder — where the next key goes — is composing.
    static var isComposingInKeyWindow: Bool {
        isComposing(NSApp.keyWindow?.firstResponder)
    }
}
#endif

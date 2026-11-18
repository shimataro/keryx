#if os(macOS)
import AppKit
import Testing

@Suite
@MainActor
struct TextInputCompositionTests {
    @Test
    func markedTextIsComposing() {
        let textView = NSTextView()
        textView.setMarkedText("にほんご", selectedRange: NSRange(location: 0, length: 4), replacementRange: NSRange(location: NSNotFound, length: 0))
        #expect(TextInputComposition.isComposing(textView))
    }

    @Test
    func committedTextIsNotComposing() {
        let textView = NSTextView()
        textView.setMarkedText("にほんご", selectedRange: NSRange(location: 0, length: 4), replacementRange: NSRange(location: NSNotFound, length: 0))
        textView.unmarkText()
        #expect(!TextInputComposition.isComposing(textView))
    }

    @Test
    func plainTextIsNotComposing() {
        let textView = NSTextView()
        textView.string = "keryx"
        #expect(!TextInputComposition.isComposing(textView))
    }

    @Test
    func nonTextResponderIsNotComposing() {
        #expect(!TextInputComposition.isComposing(nil))
        #expect(!TextInputComposition.isComposing(NSView()))
    }
}
#endif

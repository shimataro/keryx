#if os(macOS)
import AppKit
#else
import UIKit
#endif
import Testing

@Suite
@MainActor
struct TextInputCompositionTests {
    #if os(macOS)
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
    #else
    @Test
    func markedTextIsComposing() {
        let textView = UITextView()
        textView.setMarkedText("にほんご", selectedRange: NSRange(location: 0, length: 4))
        #expect(TextInputComposition.isComposing(textView))
    }

    @Test
    func committedTextIsNotComposing() {
        let textView = UITextView()
        textView.setMarkedText("にほんご", selectedRange: NSRange(location: 0, length: 4))
        textView.unmarkText()
        #expect(!TextInputComposition.isComposing(textView))
    }

    @Test
    func plainTextIsNotComposing() {
        let textView = UITextView()
        textView.text = "keryx"
        #expect(!TextInputComposition.isComposing(textView))
    }

    @Test
    func nonTextResponderIsNotComposing() {
        #expect(!TextInputComposition.isComposing(nil))
        #expect(!TextInputComposition.isComposing(UIView()))
    }
    #endif
}

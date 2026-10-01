#if os(macOS)
import AppKit
import SwiftUI

/// The text input inside `InlineRenameField` on macOS: a plain `NSTextField` that makes itself first
/// responder, instead of SwiftUI's `TextField` + `@FocusState`.
///
/// A SwiftUI focus request for a field inside a sidebar `List` row (an `NSOutlineView`) is not
/// reliable: the row's content exists more than once (the outline keeps more than one instance of a
/// row's view alive), every instance binds the same focus value, and the request is sometimes
/// dropped silently — the editor then shows its frame while keystrokes keep going to the outline,
/// typically from the second edit of the same row on. Asking AppKit directly, from the one instance
/// that is actually on screen, sidesteps both.
struct RenameTextField: NSViewRepresentable {
    @Binding var text: String
    let placeholder: String
    let handle: RenameTextFieldHandle
    let onSubmit: () -> Void
    let onCancel: () -> Void
    /// The field stopped being edited for any other reason — typically a click on another row.
    let onFocusLost: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> FocusClaimingTextField {
        let field = FocusClaimingTextField()
        field.isBordered = false
        field.drawsBackground = false
        field.focusRingType = .none
        field.font = .systemFont(ofSize: NSFont.systemFontSize)
        // Paired with the editor's own `textBackgroundColor` fill (`InlineRenameField`), not with the
        // selected row's emphasized style.
        field.textColor = .textColor
        field.usesSingleLineMode = true
        field.cell?.isScrollable = true
        field.cell?.wraps = false
        field.lineBreakMode = .byClipping
        field.stringValue = text
        field.placeholderString = placeholder
        field.delegate = context.coordinator
        // Take the width the row offers rather than sizing to the text.
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        handle.field = field
        return field
    }

    func updateNSView(_ field: FocusClaimingTextField, context: Context) {
        context.coordinator.parent = self
        handle.field = field
        // Never overwrite the text while it is being typed; only an outside change applies.
        if field.currentEditor() == nil, field.stringValue != text { field.stringValue = text }
        if field.placeholderString != placeholder { field.placeholderString = placeholder }
    }

    final class Coordinator: NSObject, NSTextFieldDelegate {
        var parent: RenameTextField

        init(_ parent: RenameTextField) { self.parent = parent }

        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSTextField else { return }
            parent.text = field.stringValue
        }

        func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            switch selector {
            case #selector(NSResponder.insertNewline(_:)):
                parent.onSubmit()
                return true
            case #selector(NSResponder.cancelOperation(_:)):
                parent.onCancel()
                return true
            default:
                return false
            }
        }

        func controlTextDidEndEditing(_ notification: Notification) {
            parent.onFocusLost()
        }
    }
}

/// Lets `InlineRenameField` hand keyboard focus back to the sidebar when an edit ends: the field is
/// still first responder at that point, and `focusedPane` may already read `.feedList` (the
/// outline's `.focused` binding does not always notice a descendant AppKit field taking over), so
/// assigning `.feedList` alone can be a no-op that leaves focus nowhere.
@MainActor
final class RenameTextFieldHandle {
    weak var field: NSTextField?

    /// Makes the enclosing outline view first responder. Returns whether it did.
    @discardableResult
    func returnFocusToList() -> Bool {
        guard let field, let window = field.window else { return false }
        var view = field.superview
        while let current = view, !(current is NSOutlineView) { view = current.superview }
        guard let outline = view else { return false }
        return window.makeFirstResponder(outline)
    }
}

final class FocusClaimingTextField: NSTextField {
    /// How long after joining a window the field keeps trying to take focus — long enough to cover
    /// the outline's own layout passes, short enough that a later relayout never pulls focus back
    /// after the user has moved on.
    private static let claimWindow: TimeInterval = 0.5

    private var attachedAt: Date?
    private var claimed = false

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        attachedAt = window == nil ? nil : Date()
        scheduleClaim()
    }

    override func layout() {
        super.layout()
        scheduleClaim()
    }

    /// `drawsBackground = false` only covers the field itself: while it is being typed in, the
    /// window's shared field editor sits on top and paints a square block of its own background over
    /// the text part only, not the "×" beside it nor the rounded fill `InlineRenameField` draws around
    /// both. AppKit turns that
    /// background back on after the cell's own `setUpFieldEditorAttributes`, so it is switched off
    /// here, once the editor is in place. The next field to be edited sets the shared editor up again.
    override func becomeFirstResponder() -> Bool {
        let became = super.becomeFirstResponder()
        if became, let editor = currentEditor() {
            editor.drawsBackground = false
        }
        return became
    }

    private func scheduleClaim() {
        guard !claimed, let attachedAt, Date().timeIntervalSince(attachedAt) < Self.claimWindow else { return }
        DispatchQueue.main.async { [weak self] in self?.claimFocusIfVisible() }
    }

    /// Only the instance actually on screen takes focus; any other copy of the row stays passive.
    private func claimFocusIfVisible() {
        guard !claimed, let window, !isHiddenOrHasHiddenAncestor, !visibleRect.isEmpty else { return }
        claimed = window.makeFirstResponder(self)
    }
}
#endif

import KeryxShared
import SwiftUI

/// A sidebar row's in-place name editor: the row's label `Text` swapped for a text field in the
/// same slot, instead of a modal sheet — the SwiftUI counterpart of Compose's `InlineRenameField`
/// (`InlineRename.kt`), with the same rules (see "Inline row editing" in the `ui-guidelines` skill):
/// - **Return** commits, but only a valid value; otherwise the editor stays open.
/// - **Escape** and the "×" always cancel.
/// - **Losing focus** commits a valid value and silently cancels an invalid one.
/// - Validation is the shared `inlineRenameValidation`: a blank value is not an error, it merely
///   cannot be committed unless `allowBlank`; `blockingError` (a duplicate name) paints the frame red.
///
/// It reports its own focus as `HomeFocusedPane.rowNameEditor`, which is what makes the window's
/// bare-key shortcuts and the Feed menu's Return/Delete accelerators stand aside while it is open.
struct InlineRenameField: View {
    let initialName: String
    /// Shown while the field is empty — the title a blanked feed name falls back to.
    var placeholder: String = ""
    /// Whether a blank value is itself meaningful (a feed: it clears the custom title).
    var allowBlank = false
    /// A validation message for the trimmed, non-blank value, or `nil` when it is acceptable.
    let blockingError: (String) -> String?
    /// Receives the trimmed value; only ever called once, and only for a valid, changed value.
    let onCommit: (String) -> Void
    /// Called instead of `onCommit` when the edit ends without a change. Only ever called once.
    let onCancel: () -> Void
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @State private var text: String
    @State private var finished = false

    init(
        initialName: String,
        placeholder: String = "",
        allowBlank: Bool = false,
        blockingError: @escaping (String) -> String?,
        onCommit: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        focusedPane: FocusState<HomeFocusedPane?>.Binding
    ) {
        self.initialName = initialName
        self.placeholder = placeholder
        self.allowBlank = allowBlank
        self.blockingError = blockingError
        self.onCommit = onCommit
        self.onCancel = onCancel
        self.focusedPane = focusedPane
        self._text = State(initialValue: initialName)
    }

    private var validation: InlineRenameValidation {
        InlineRenameValidationKt.inlineRenameValidation(text: text, allowBlank: allowBlank, blockingError: blockingError)
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        HStack(spacing: 4) {
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .lineLimit(1)
                .focused(focusedPane, equals: .rowNameEditor)
                .onSubmit { finish(commit: true, restoreFocus: true) }
                #if os(macOS)
                // The field editor turns Escape into `cancelOperation` before a key-press handler
                // sees it.
                .onExitCommand { finish(commit: false, restoreFocus: true) }
                #else
                .onKeyPress(.escape) {
                    finish(commit: false, restoreFocus: true)
                    return .handled
                }
                #endif
            // Escape has no touch equivalent and is hard to discover, so the editor also carries an
            // explicit cancel. Not focusable, so pressing it doesn't first take focus off the field
            // and commit the edit.
            Button {
                finish(commit: false, restoreFocus: true)
            } label: {
                Image(systemName: "xmark.circle.fill").imageScale(.small)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            .focusable(false)
            .accessibilityLabel(L("common_cancel"))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(validation.error == nil ? Color.secondary.opacity(0.4) : Color.red, lineWidth: 1)
                .padding(-2)
                .allowsHitTesting(false)
        }
        .help(validation.error ?? "")
        .accessibilityValue(validation.error ?? "")
        .task { focusedPane.wrappedValue = .rowNameEditor }
        // Focus moving anywhere else (another row clicked, another pane) ends the edit.
        .onChange(of: focusedPane.wrappedValue) { _, pane in
            if pane != .rowNameEditor { finish(commit: true, restoreFocus: false) }
        }
    }

    /// Ends the edit exactly once. A commit of an invalid value is refused when Return asked for it
    /// (the editor stays open) and turns into a cancel when focus has already gone elsewhere.
    private func finish(commit: Bool, restoreFocus: Bool) {
        guard !finished else { return }
        if commit {
            if !validation.canCommit {
                if restoreFocus { return }
                return end(restoreFocus: false) { onCancel() }
            }
            let value = trimmed
            // Nothing changed: not a rename, and for a feed it would stamp a custom title equal to
            // the fetched one.
            let unchanged = value == initialName.trimmingCharacters(in: .whitespacesAndNewlines)
            return end(restoreFocus: restoreFocus) { unchanged ? onCancel() : onCommit(value) }
        }
        end(restoreFocus: restoreFocus) { onCancel() }
    }

    private func end(restoreFocus: Bool, _ action: () -> Void) {
        finished = true
        if restoreFocus { focusedPane.wrappedValue = .feedList }
        action()
    }
}

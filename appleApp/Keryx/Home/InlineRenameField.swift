#if os(macOS)
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
/// While it is open, `SidebarDialogState.isEditingInline` is what makes the window's bare-key
/// shortcuts and the Feed menu's Return/Delete accelerators stand aside. The field is not reported
/// through `focusedPane`: the input is an AppKit field that takes focus itself (see `RenameTextField`
/// for why).
///
/// macOS only: iOS renames in the same form sheet that creates a folder or tag (`NamePromptSheet`),
/// since a keyboard-driven in-row editor with an Escape key does not suit touch.
struct InlineRenameField: View {
    let initialName: String
    /// What was typed before this field was built afresh (a re-keyed row), or `nil`: the field then
    /// starts from `initialName`. `initialName` stays what an unchanged edit is judged against.
    var draft: String?
    /// Reports each change of the text, for the owner to keep as `draft`.
    var onDraftChange: (String) -> Void = { _ in }
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
    @State private var fieldHandle = RenameTextFieldHandle()

    init(
        initialName: String,
        draft: String? = nil,
        onDraftChange: @escaping (String) -> Void = { _ in },
        placeholder: String = "",
        allowBlank: Bool = false,
        blockingError: @escaping (String) -> String?,
        onCommit: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        focusedPane: FocusState<HomeFocusedPane?>.Binding
    ) {
        self.initialName = initialName
        self.draft = draft
        self.onDraftChange = onDraftChange
        self.placeholder = placeholder
        self.allowBlank = allowBlank
        self.blockingError = blockingError
        self.onCommit = onCommit
        self.onCancel = onCancel
        self.focusedPane = focusedPane
        self._text = State(initialValue: draft ?? initialName)
    }

    private var validation: InlineRenameValidation {
        InlineRenameValidationKt.inlineRenameValidation(text: text, allowBlank: allowBlank, blockingError: blockingError)
    }

    /// The validation message the editor shows: none for a value the user has not changed. A row whose
    /// own name is already shared (two same-named rows, shown with an ordinal) opens on that name, and a
    /// red frame there would blame them for what they have not touched. Committing it stays blocked.
    private var shownError: String? { text == initialName ? nil : validation.error }

    // Fixed colors rather than hierarchical styles: inside a selected sidebar row `.secondary` is
    // drawn for the row's emphasized background, which would vanish against the field's own fill.
    private static let fieldBackground = Color(nsColor: .textBackgroundColor)
    private static let secondaryForeground = Color(nsColor: .secondaryLabelColor)

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        HStack(spacing: 4) {
            RenameTextField(
                text: $text,
                placeholder: placeholder,
                handle: fieldHandle,
                onSubmit: { finish(commit: true, restoreFocus: true) },
                onCancel: { finish(commit: false, restoreFocus: true) },
                onFocusLost: { finish(commit: true, restoreFocus: false) }
            )
            .accessibilityHint(shownError ?? "")
            // Escape is hard to discover, so the editor also carries an explicit cancel. Not focusable, so pressing it doesn't first take focus off the field
            // and commit the edit.
            Button {
                finish(commit: false, restoreFocus: true)
            } label: {
                Image(systemName: "xmark.circle.fill").imageScale(.small)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(Self.secondaryForeground)
            .focusable(false)
            .accessibilityLabel(L("common_cancel"))
        }
        .onChange(of: text) { _, new in onDraftChange(new) }
        // A text-field fill of its own, like Finder's sidebar rename: without it the selected row's
        // color shows through and the editor reads as a selection rather than an input.
        .background {
            RoundedRectangle(cornerRadius: 4)
                .fill(Self.fieldBackground)
                .padding(-2)
        }
        .overlay {
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(shownError == nil ? Color.secondary.opacity(0.4) : Color.red, lineWidth: 1)
                .padding(-2)
                .allowsHitTesting(false)
        }
        .help(shownError ?? "")
        // The red border and `.help` do not reach VoiceOver, so a new error is announced as it appears.
        .onChange(of: shownError) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
        // Another pane (or the search field) taking focus ends the edit; a click on another row is
        // reported by the field itself (`onFocusLost`). Any other value is not a focus loss: the
        // outline's own `.feedList` binding may read `nil` or stay `.feedList` once the AppKit field
        // takes over, which would otherwise close the editor the moment it opens.
        .onChange(of: focusedPane.wrappedValue) { _, pane in
            switch pane {
            case .articleList, .reader, .search: finish(commit: true, restoreFocus: false)
            case .feedList, nil: break
            }
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
        if restoreFocus {
            fieldHandle.returnFocusToList()
            focusedPane.wrappedValue = .feedList
        }
        action()
    }
}
#endif

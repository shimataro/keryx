import KeryxShared
import SwiftUI

/// A reusable name-entry sheet for creating a folder or tag — and, on iOS, for renaming a folder, tag
/// or feed (`initialName`; macOS renames in the row itself, `InlineRenameField`) — with live
/// duplicate-name validation (`NameValidation.kt`'s `isDuplicateFolderName`/`isDuplicateTagName`) and,
/// for a tag, a color swatch picker. The rules are the shared `inlineRenameValidation`, the same ones
/// the in-place editor follows.
struct NamePromptSheet: View {
    let titleKey: String
    let placeholderKey: String
    /// Shown instead of `placeholderKey` while the field is empty — a feed's parsed title, which is
    /// what a blanked name falls back to.
    let placeholderText: String?
    let duplicateMessageKey: String
    /// The confirm button's catalog key.
    let confirmTitleKey: String
    /// Whether a blank name may be confirmed (a feed: it clears the custom title).
    let allowBlank: Bool
    let initialName: String
    let showColorPicker: Bool
    let isDuplicate: (String) -> Bool
    let onConfirm: (String, String?) -> Void
    @Binding var isPresented: Bool

    @State private var name: String
    @State private var color: String?
    @FocusState private var nameFieldFocused: Bool

    init(
        titleKey: String,
        placeholderKey: String,
        placeholderText: String? = nil,
        duplicateMessageKey: String = "",
        confirmTitleKey: String = "common_ok",
        allowBlank: Bool = false,
        initialName: String = "",
        showColorPicker: Bool = false,
        isDuplicate: @escaping (String) -> Bool,
        onConfirm: @escaping (String, String?) -> Void,
        isPresented: Binding<Bool>
    ) {
        self.titleKey = titleKey
        self.placeholderKey = placeholderKey
        self.placeholderText = placeholderText
        self.duplicateMessageKey = duplicateMessageKey
        self.confirmTitleKey = confirmTitleKey
        self.allowBlank = allowBlank
        self.initialName = initialName
        self.showColorPicker = showColorPicker
        self.isDuplicate = isDuplicate
        self.onConfirm = onConfirm
        self._isPresented = isPresented
        self._name = State(initialValue: initialName)
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var validation: InlineRenameValidation {
        InlineRenameValidationKt.inlineRenameValidation(
            text: name,
            allowBlank: allowBlank,
            blockingError: { isDuplicate($0) ? L(duplicateMessageKey) : nil }
        )
    }

    var body: some View {
        #if os(iOS)
        // iOS's own form sheet: the title and Cancel / confirm in the navigation bar, the fields in a
        // grouped form — as Reminders' "New List" does.
        NavigationStack {
            Form {
                Section {
                    nameField
                    if let error = validation.error { duplicateMessage(error) }
                }
                if showColorPicker {
                    Section { colorPicker }
                }
            }
            .navigationTitle(L(titleKey))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common_cancel"), role: .cancel) { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L(confirmTitleKey), action: confirm).disabled(!validation.canCommit)
                }
            }
        }
        .presentationDetents([.medium])
        .task { nameFieldFocused = true }
        #else
        VStack(alignment: .leading, spacing: 12) {
            Text(L(titleKey)).font(.headline)

            nameField
                .textFieldStyle(.roundedBorder)

            if let error = validation.error { duplicateMessage(error) }

            if showColorPicker { colorPicker }

            HStack {
                Spacer()
                Button(L("common_cancel"), role: .cancel) { isPresented = false }
                    .keyboardShortcut(.cancelAction)
                Button(L(confirmTitleKey), action: confirm)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!validation.canCommit)
            }
        }
        .padding()
        .frame(minWidth: 320)
        #endif
    }

    private var nameField: some View {
        #if os(iOS)
        // The standard Clear button at the field's trailing end (HIG, Text fields): it erases the
        // text, which is why cancelling is the navigation bar's Cancel instead.
        HStack {
            nameTextField
            if !name.isEmpty {
                Button {
                    name = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("apple_clear_text"))
            }
        }
        #else
        nameTextField
        #endif
    }

    private var nameTextField: some View {
        TextField(placeholderText ?? L(placeholderKey), text: $name)
            .focused($nameFieldFocused)
            .onSubmit(confirm)
    }

    private func duplicateMessage(_ message: String) -> some View {
        Text(message)
            .font(.caption)
            .foregroundStyle(.red)
    }

    private var colorPicker: some View {
        TagColorSwatchRow(selectedHex: color) { color = $0 }
    }

    /// An unchanged name is not a rename (and for a feed it would stamp a custom title equal to the
    /// fetched one), so it only closes the sheet.
    private func confirm() {
        guard validation.canCommit else { return }
        if trimmedName != initialName.trimmingCharacters(in: .whitespacesAndNewlines) {
            onConfirm(trimmedName, color)
        }
        isPresented = false
    }
}

/// Parses a hex color string (optionally `#`-prefixed), or `nil` for the shared
/// `TagColorsKt.TAG_COLOR_NONE_HEX` fallback gray — mirrors Compose's own `colorFromHex` (`TagColorPicker.kt`)
/// so an unset tag color renders identically on both platforms.
func colorFromHex(_ hex: String?) -> Color {
    guard let hex else { return colorFromHex(TagColorsKt.TAG_COLOR_NONE_HEX) }
    var value: UInt64 = 0
    Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&value)
    let r = Double((value & 0xFF0000) >> 16) / 255
    let g = Double((value & 0x00FF00) >> 8) / 255
    let b = Double(value & 0x0000FF) / 255
    return Color(red: r, green: g, blue: b)
}

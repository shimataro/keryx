import KeryxShared
import SwiftUI

/// A reusable name-entry sheet for creating a folder or tag, with live duplicate-name validation
/// (`NameValidation.kt`'s `isDuplicateFolderName`/`isDuplicateTagName`) and, for a tag, a color
/// swatch picker. Renaming is not done here: it happens in the row itself (`InlineRenameField`).
struct NamePromptSheet: View {
    let titleKey: String
    let placeholderKey: String
    let duplicateMessageKey: String
    let showColorPicker: Bool
    let isDuplicate: (String) -> Bool
    let onConfirm: (String, String?) -> Void
    @Binding var isPresented: Bool

    @State private var name = ""
    @State private var color: String?

    init(
        titleKey: String,
        placeholderKey: String,
        duplicateMessageKey: String = "",
        showColorPicker: Bool = false,
        isDuplicate: @escaping (String) -> Bool,
        onConfirm: @escaping (String, String?) -> Void,
        isPresented: Binding<Bool>
    ) {
        self.titleKey = titleKey
        self.placeholderKey = placeholderKey
        self.duplicateMessageKey = duplicateMessageKey
        self.showColorPicker = showColorPicker
        self.isDuplicate = isDuplicate
        self.onConfirm = onConfirm
        self._isPresented = isPresented
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var duplicate: Bool { !trimmedName.isEmpty && isDuplicate(trimmedName) }
    private var canConfirm: Bool { !trimmedName.isEmpty && !duplicate }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L(titleKey)).font(.headline)

            TextField(L(placeholderKey), text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit(confirm)

            if duplicate {
                Text(L(duplicateMessageKey))
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if showColorPicker {
                HStack(spacing: 8) {
                    swatch(nil)
                    ForEach(TagColorsKt.TAG_COLOR_PALETTE, id: \.self) { hex in swatch(hex) }
                }
            }

            HStack {
                Spacer()
                Button(L("common_cancel"), role: .cancel) { isPresented = false }
                    .keyboardShortcut(.cancelAction)
                Button(L("common_ok"), action: confirm)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canConfirm)
            }
        }
        .padding()
        .frame(minWidth: 320)
    }

    /// A single swatch — `hex == nil` is the "no color" option, matching Compose's own
    /// `TagColorPicker` (`TagColorPicker.kt`), which always offers it alongside `TagColorsKt.TAG_COLOR_PALETTE`.
    private func swatch(_ hex: String?) -> some View {
        Circle()
            .fill(colorFromHex(hex))
            .frame(width: 20, height: 20)
            .overlay(
                Circle().strokeBorder(Color.primary, lineWidth: color == hex ? 2 : 0)
            )
            .onTapGesture { color = hex }
    }

    private func confirm() {
        guard canConfirm else { return }
        onConfirm(trimmedName, color)
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

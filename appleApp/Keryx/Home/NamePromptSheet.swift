import SwiftUI

/// Fixed color palette for a tag's swatch — Compose's own tag-color picker isn't ported yet
/// (see M3's known gaps); this is a reasonable, small fixed set rather than a full color wheel.
let tagColorPalette: [String] = [
    "#F44336", "#E91E63", "#9C27B0", "#3F51B5",
    "#2196F3", "#009688", "#4CAF50", "#FF9800",
]

/// A reusable name-entry sheet for creating/renaming a folder or tag, with live duplicate-name
/// validation (`NameValidation.kt`'s `isDuplicateFolderName`/`isDuplicateTagName`) and, for a tag,
/// a color swatch picker. One shared component rather than separate folder/tag/rename dialogs,
/// since the shape (name + optional color + duplicate check + confirm/cancel) is identical.
struct NamePromptSheet: View {
    let titleKey: String
    let placeholderKey: String
    let duplicateMessageKey: String
    let showColorPicker: Bool
    let isDuplicate: (String) -> Bool
    let onConfirm: (String, String?) -> Void
    @Binding var isPresented: Bool

    @State private var name: String
    @State private var color: String?

    init(
        titleKey: String,
        placeholderKey: String,
        duplicateMessageKey: String = "",
        initialName: String = "",
        initialColor: String? = nil,
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
        self._name = State(initialValue: initialName)
        self._color = State(initialValue: initialColor)
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var duplicate: Bool { !trimmedName.isEmpty && isDuplicate(trimmedName) }

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
                    ForEach(tagColorPalette, id: \.self) { hex in
                        Circle()
                            .fill(colorFromHex(hex))
                            .frame(width: 20, height: 20)
                            .overlay(
                                Circle().strokeBorder(Color.primary, lineWidth: color == hex ? 2 : 0)
                            )
                            .onTapGesture { color = hex }
                    }
                }
            }

            HStack {
                Spacer()
                Button(L("common_cancel"), role: .cancel) { isPresented = false }
                Button(L("common_ok"), action: confirm)
                    .keyboardShortcut(.defaultAction)
                    .disabled(trimmedName.isEmpty || duplicate)
            }
        }
        .padding()
        .frame(minWidth: 320)
    }

    private func confirm() {
        guard !trimmedName.isEmpty, !duplicate else { return }
        onConfirm(trimmedName, color)
        isPresented = false
    }
}

func colorFromHex(_ hex: String) -> Color {
    var value: UInt64 = 0
    Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&value)
    let r = Double((value & 0xFF0000) >> 16) / 255
    let g = Double((value & 0x00FF00) >> 8) / 255
    let b = Double(value & 0x0000FF) / 255
    return Color(red: r, green: g, blue: b)
}

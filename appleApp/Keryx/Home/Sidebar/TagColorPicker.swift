import KeryxShared
import SwiftUI

/// A tag's color picker: "no color" followed by the shared palette, with the current color ringed.
/// A tap applies immediately — there is nothing to confirm, matching Compose's own
/// `TagColorPickerPopup` (`TagColorPicker.kt`).
struct TagColorPicker: View {
    /// The tag's current color, ringed in the picker.
    let selectedHex: String?
    let onPick: (String?) -> Void

    var body: some View {
        HStack(spacing: 8) {
            swatch(nil)
            ForEach(TagColorsKt.TAG_COLOR_PALETTE, id: \.self) { hex in swatch(hex) }
        }
        .padding(12)
    }

    private func swatch(_ hex: String?) -> some View {
        Circle()
            .fill(colorFromHex(hex))
            .frame(width: 20, height: 20)
            .overlay(Circle().strokeBorder(Color.primary, lineWidth: selectedHex == hex ? 2 : 0))
            .onTapGesture { onPick(hex) }
    }
}

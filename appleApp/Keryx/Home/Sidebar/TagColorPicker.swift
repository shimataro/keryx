import KeryxShared
import SwiftUI

/// A tag's color picker: "no color" followed by the shared palette, with the current color ringed.
/// A tap applies immediately — there is nothing to confirm, matching Compose's own
/// `TagColorPickerPopup` (`TagColorPicker.kt`). Only the macOS tag row's dot popover shows it; the iOS
/// sidebar changes a tag's color from a palette in its long-press menu (`SidebarContextMenus`).
struct TagColorPicker: View {
    /// The tag's current color, ringed in the picker.
    let selectedHex: String?
    let onPick: (String?) -> Void

    var body: some View {
        TagColorSwatchRow(selectedHex: selectedHex, onPick: onPick)
            .padding(12)
    }
}

/// The swatches themselves — "no color" followed by `TAG_COLOR_PALETTE` — shared by the macOS popover
/// and the tag-creation sheet. Each is a button named for its color and marked as selected for
/// VoiceOver; on iOS it is also drawn larger, inside the 44pt touch target the HIG asks for, and the
/// row wraps rather than overflowing a narrow screen.
struct TagColorSwatchRow: View {
    let selectedHex: String?
    let onPick: (String?) -> Void

    #if os(iOS)
    private static let diameter: CGFloat = 28
    private static let touchTarget: CGFloat = 44
    #else
    private static let diameter: CGFloat = 20
    #endif

    var body: some View {
        #if os(iOS)
        LazyVGrid(columns: [GridItem(.adaptive(minimum: Self.touchTarget), spacing: 0)], alignment: .leading, spacing: 0) {
            swatches
        }
        #else
        HStack(spacing: 8) {
            swatches
        }
        #endif
    }

    @ViewBuilder
    private var swatches: some View {
        swatch(nil)
        ForEach(TagColorsKt.TAG_COLOR_PALETTE, id: \.self) { hex in swatch(hex) }
    }

    private func swatch(_ hex: String?) -> some View {
        let selected = selectedHex == hex
        return Button {
            onPick(hex)
        } label: {
            Circle()
                .fill(colorFromHex(hex))
                .frame(width: Self.diameter, height: Self.diameter)
                .overlay(Circle().strokeBorder(Color.primary, lineWidth: selected ? 2 : 0))
                #if os(iOS)
                .frame(width: Self.touchTarget, height: Self.touchTarget)
                .contentShape(Rectangle())
                #endif
        }
        // `.plain`, so that inside a `Form` row each swatch is its own button rather than the whole
        // row becoming one.
        .buttonStyle(.plain)
        .accessibilityLabel(TagColorNames.name(for: hex))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

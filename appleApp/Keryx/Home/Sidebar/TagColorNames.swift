import KeryxShared

/// The localized names of the tag colors, for what a swatch cannot say on its own: VoiceOver's label
/// and the title of an item in the iOS palette menu. Keyed by position in the shared
/// `TAG_COLOR_PALETTE`, so a color added there without a name is caught by `TagColorNamesTests`.
enum TagColorNames {
    /// The String Catalog key for "no color", `TAG_COLOR_NONE_HEX`.
    static let noneKey = "apple_tag_color_none"

    /// One key per `TAG_COLOR_PALETTE` entry, in the palette's order.
    static let paletteKeys = [
        "apple_tag_color_red",
        "apple_tag_color_orange",
        "apple_tag_color_amber",
        "apple_tag_color_green",
        "apple_tag_color_blue",
        "apple_tag_color_indigo",
        "apple_tag_color_purple",
        "apple_tag_color_pink",
    ]

    /// The catalog key naming `hex` (`nil` is "no color"), or `nil` for a color outside the palette.
    static func key(for hex: String?) -> String? {
        guard let hex else { return noneKey }
        guard let index = TagColorsKt.TAG_COLOR_PALETTE.firstIndex(of: hex), paletteKeys.indices.contains(index) else {
            return nil
        }
        return paletteKeys[index]
    }

    /// The localized name of `hex`, falling back to "no color" for one outside the palette.
    static func name(for hex: String?) -> String {
        L(key(for: hex) ?? noneKey)
    }
}

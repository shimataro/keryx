import KeryxShared
import Testing

/// Covers `TagColorNames`: every selectable tag color has a name for VoiceOver and the palette menu.
@Suite
struct TagColorNamesTests {
    @Test
    func everyPaletteColorHasItsOwnName() {
        let palette = TagColorsKt.TAG_COLOR_PALETTE
        #expect(TagColorNames.paletteKeys.count == palette.count)
        let keys = palette.compactMap { TagColorNames.key(for: $0) }
        #expect(keys.count == palette.count)
        #expect(Set(keys).count == palette.count)
    }

    @Test
    func noColorHasTheNoneName() {
        #expect(TagColorNames.key(for: nil) == TagColorNames.noneKey)
    }

    @Test
    func aColorOutsideThePaletteHasNoName() {
        #expect(TagColorNames.key(for: "#000001") == nil)
    }
}

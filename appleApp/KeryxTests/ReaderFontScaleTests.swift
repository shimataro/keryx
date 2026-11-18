#if os(iOS)
import SwiftUI
import Testing

/// `ReaderFontScale` carries the system text size (Dynamic Type) into the iOS reader.
@Suite
struct ReaderFontScaleTests {
    @Test
    func theDefaultSizeLeavesTheReaderUnscaled() {
        #expect(ReaderFontScale.scale(for: .large) == 1.0)
    }

    @Test
    func aSmallerSizeShrinksTheReader() {
        #expect(ReaderFontScale.scale(for: .xSmall) < 1.0)
    }

    @Test
    func theScaleGrowsWithEachLargerSize() {
        let scales = DynamicTypeSize.allCases.map(ReaderFontScale.scale(for:))
        for (smaller, larger) in zip(scales, scales.dropFirst()) {
            #expect(smaller <= larger)
        }
        #expect(scales.first! < scales.last!)
    }

    @Test
    func anAccessibilitySizeEnlargesTheReader() {
        #expect(ReaderFontScale.scale(for: .accessibility1) > ReaderFontScale.scale(for: .xxxLarge))
    }
}
#endif

import Testing

@Suite
struct ReaderScrollIndicatorTests {
    @Test
    func aDarkThemeGetsALightIndicator() {
        #expect(ReaderScrollIndicator(isDarkTheme: true) == .light)
    }

    @Test
    func aLightThemeGetsADarkIndicator() {
        #expect(ReaderScrollIndicator(isDarkTheme: false) == .dark)
    }
}

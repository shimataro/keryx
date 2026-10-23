package works.merc.keryx.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MacAppearanceTest {
    @Test
    fun lightThemeMapsToAqua() {
        assertEquals("NSAppearanceNameAqua", macAppearanceFor("light"))
    }

    @Test
    fun darkThemeMapsToDarkAqua() {
        assertEquals("NSAppearanceNameDarkAqua", macAppearanceFor("dark"))
    }

    @Test
    fun systemOrUnknownThemeFollowsTheOs() {
        assertEquals("system", macAppearanceFor("system"))
        assertEquals("system", macAppearanceFor("unexpected"))
    }
}

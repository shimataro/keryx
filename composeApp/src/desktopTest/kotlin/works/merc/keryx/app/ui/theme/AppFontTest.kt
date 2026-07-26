package works.merc.keryx.app.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppFontTest {

    @Test
    fun stripsTrailingPointSize() {
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell 11"))
        assertEquals("Adwaita Sans", pangoFontFamilyName("Adwaita Sans 11"))
    }

    @Test
    fun stripsFractionalAndPixelSizes() {
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell 11.5"))
        assertEquals("Noto Sans", pangoFontFamilyName("Noto Sans 12px"))
    }

    @Test
    fun stripsStyleKeywordsBeforeSize() {
        assertEquals("Noto Sans", pangoFontFamilyName("Noto Sans Bold 10"))
        assertEquals("DejaVu Sans", pangoFontFamilyName("DejaVu Sans Condensed Italic 9"))
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell Semi-Bold"))
    }

    @Test
    fun styleKeywordMatchingIsCaseInsensitive() {
        assertEquals("Noto Sans", pangoFontFamilyName("Noto Sans BOLD 10"))
    }

    @Test
    fun keepsFamilyWithoutSizeOrStyle() {
        assertEquals("Ubuntu", pangoFontFamilyName("Ubuntu"))
    }

    /** Stripping stops at the first non-keyword token, so an embedded keyword survives. */
    @Test
    fun keepsKeywordThatIsPartOfTheFamilyName() {
        assertEquals("Book Antiqua", pangoFontFamilyName("Book Antiqua 11"))
    }

    @Test
    fun takesFirstFamilyOfAList() {
        assertEquals("DejaVu Sans", pangoFontFamilyName("DejaVu Sans, Cantarell Bold 11"))
    }

    @Test
    fun collapsesSurroundingAndRepeatedWhitespace() {
        assertEquals("Noto Sans", pangoFontFamilyName("  Noto   Sans   10  "))
    }

    @Test
    fun returnsNullWhenNoFamilyRemains() {
        assertNull(pangoFontFamilyName(""))
        assertNull(pangoFontFamilyName("   "))
        assertNull(pangoFontFamilyName("11"))
        assertNull(pangoFontFamilyName("Bold 11"))
    }
}

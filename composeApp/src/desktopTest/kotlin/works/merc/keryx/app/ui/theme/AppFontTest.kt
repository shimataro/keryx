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
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell Roman 11"))
    }

    @Test
    fun stripsTrailingVariationsAndFeatures() {
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell 11 @wght=200 #tnum=1"))
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell Italic Light 15 @wght=200 #tnum=1"))
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

    @Test
    fun stripsStretchKeywordsBeforeSize() {
        assertEquals("Noto Sans", pangoFontFamilyName("Noto Sans Semi-Expanded 10"))
        assertEquals("Ubuntu", pangoFontFamilyName("Ubuntu Ultra-Condensed"))
    }

    @Test
    fun stripsVariantKeywordsBeforeSize() {
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell Small-Caps 11"))
        assertEquals("Cantarell", pangoFontFamilyName("Cantarell All-Petite-Caps"))
    }

    @Test
    fun stripsMultipleTrailingKeywordsInSequence() {
        // style + weight + stretch, all before the size - each is stripped in turn.
        assertEquals("Noto Sans", pangoFontFamilyName("Noto Sans Italic Semi-Bold Condensed 10"))
    }

    /**
     * Documents an actual limitation rather than desired behavior: the pixel-size suffix check
     * only recognizes a lowercase "px" (`removeSuffix("px")`), so an unusual uppercase report is
     * treated as part of the family instead of being stripped as a size.
     */
    @Test
    fun pixelSizeSuffixMatchingIsCaseSensitive() {
        assertEquals("Cantarell 12PX", pangoFontFamilyName("Cantarell 12PX"))
    }

    @Test
    fun takesFirstFamilyWhenTheListHasNoSizeOrStyle() {
        assertEquals("DejaVu Sans", pangoFontFamilyName("DejaVu Sans, Cantarell"))
    }
}

package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpUrlAndTagColorsTest {

    @Test
    fun httpAndHttpsAreAccepted() {
        assertTrue(isHttpOrHttpsUrl("http://example.com"))
        assertTrue(isHttpOrHttpsUrl("https://example.com/a?b=c"))
    }

    @Test
    fun schemeMatchIsCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        assertTrue(isHttpOrHttpsUrl("HTTPS://Example.com"))
        assertTrue(isHttpOrHttpsUrl("HtTp://example.com"))
        assertTrue(isHttpOrHttpsUrl("  https://example.com  "))
    }

    @Test
    fun otherSchemesAreRejected() {
        assertFalse(isHttpOrHttpsUrl("ftp://example.com"))
        assertFalse(isHttpOrHttpsUrl("file:///etc/passwd"))
        assertFalse(isHttpOrHttpsUrl("javascript:alert(1)"))
        assertFalse(isHttpOrHttpsUrl("mailto:a@example.com"))
        assertFalse(isHttpOrHttpsUrl("httpx://example.com"))
        assertFalse(isHttpOrHttpsUrl("http:/example.com"))
    }

    @Test
    fun relativeAndSchemeLessLinksAreRejected() {
        assertFalse(isHttpOrHttpsUrl("/path/to/page"))
        assertFalse(isHttpOrHttpsUrl("example.com"))
        assertFalse(isHttpOrHttpsUrl("//example.com"))
    }

    @Test
    fun nullAndBlankAreRejected() {
        assertFalse(isHttpOrHttpsUrl(null))
        assertFalse(isHttpOrHttpsUrl(""))
        assertFalse(isHttpOrHttpsUrl("   "))
    }

    @Test
    fun canOpenInBrowserFollowsTheSameRule() {
        assertTrue(canOpenInBrowser("https://example.com"))
        assertFalse(canOpenInBrowser("javascript:alert(1)"))
        assertFalse(canOpenInBrowser(null))
    }

    @Test
    fun tagColorPaletteIsEightDistinctHexColors() {
        val hex = Regex("#[0-9A-F]{6}")
        assertEquals(8, TAG_COLOR_PALETTE.size)
        assertEquals(TAG_COLOR_PALETTE.size, TAG_COLOR_PALETTE.toSet().size)
        assertTrue(TAG_COLOR_PALETTE.all { hex.matches(it) })
    }

    @Test
    fun noColorSwatchIsNotOneOfThePaletteColors() {
        assertEquals("#9E9E9E", TAG_COLOR_NONE_HEX)
        assertFalse(TAG_COLOR_NONE_HEX in TAG_COLOR_PALETTE)
    }
}

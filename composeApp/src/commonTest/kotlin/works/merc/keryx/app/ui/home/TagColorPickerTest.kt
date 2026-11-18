package works.merc.keryx.app.ui.home

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class TagColorPickerTest {

    private val none = Color(0xFF9E9E9E)

    @Test
    fun sixDigitHexWithHashIsOpaque() {
        assertEquals(Color(0xFFFF0000), colorFromHex("#FF0000"))
    }

    @Test
    fun sixDigitHexWithoutHashIsOpaque() {
        assertEquals(Color(0xFF00FF00), colorFromHex("00FF00"))
    }

    @Test
    fun hexIsCaseInsensitive() {
        assertEquals(colorFromHex("#abcdef"), colorFromHex("#ABCDEF"))
    }

    @Test
    fun eightDigitHexCarriesItsOwnAlpha() {
        assertEquals(Color(0x80FF0000), colorFromHex("#80FF0000"))
        assertEquals(Color(0x800000FF), colorFromHex("800000FF"))
    }

    @Test
    fun nullFallsBackToTheNoneColor() {
        assertEquals(none, colorFromHex(null))
    }

    @Test
    fun invalidHexFallsBackToTheNoneColor() {
        assertEquals(none, colorFromHex("#GGGGGG"))
        assertEquals(none, colorFromHex("not a color"))
        assertEquals(none, colorFromHex(""))
        assertEquals(none, colorFromHex("#"))
    }
}

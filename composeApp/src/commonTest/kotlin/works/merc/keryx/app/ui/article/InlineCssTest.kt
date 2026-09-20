package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InlineCssTest {
    @Test
    fun parsesDeclarationsIgnoringImportantAndMalformedEntries() {
        val declarations = InlineCss.parseDeclarations("color: red !important; ; bogus ; font-weight:bold")

        assertEquals(mapOf("color" to "red", "font-weight" to "bold"), declarations)
    }

    @Test
    fun parsesHexColorsOfEveryLength() {
        assertEquals(Color(0xFF, 0x00, 0x00), InlineCss.parseColor("#f00"))
        assertEquals(Color(0xFF, 0x00, 0x00), InlineCss.parseColor("#ff0000"))
        // 4-digit shorthand doubles each hex digit (CSS #rgba -> #rrggbbaa), so "8" becomes "88".
        assertEquals(Color(0xFF, 0x00, 0x00, 0x88), InlineCss.parseColor("#f008"))
    }

    @Test
    fun parsesRgbAndRgbaFunctions() {
        assertEquals(Color(10, 20, 30), InlineCss.parseColor("rgb(10, 20, 30)"))
        assertEquals(Color(10, 20, 30, 128), InlineCss.parseColor("rgba(10, 20, 30, 0.5)"))
    }

    @Test
    fun parsesNamedColors() {
        assertEquals(Color(0, 0, 0), InlineCss.parseColor("black"))
        assertEquals(Color.Transparent, InlineCss.parseColor("transparent"))
    }

    @Test
    fun returnsNullForUnrecognizedOrInvalidColors() {
        assertNull(InlineCss.parseColor(""))
        assertNull(InlineCss.parseColor("not-a-color"))
        assertNull(InlineCss.parseColor("#12"))
        assertNull(InlineCss.parseColor("inherit"))
    }

    @Test
    fun parsesLengthScalesRelativeToTheBodyFontSize() {
        assertEquals(1.5f, InlineCss.parseLengthScale("1.5em"))
        assertEquals(1.5f, InlineCss.parseLengthScale("150%"))
        assertEquals(1.0f, InlineCss.parseLengthScale("16px"))
        assertEquals(0.5f, InlineCss.parseLengthScale("8px"))
        assertEquals(1.2f, InlineCss.parseLengthScale("large"))
    }

    @Test
    fun rejectsZeroOrNegativeOrUnrecognizedLengths() {
        assertNull(InlineCss.parseLengthScale("0px"))
        assertNull(InlineCss.parseLengthScale("-1em"))
        assertNull(InlineCss.parseLengthScale("thin"))
    }

    @Test
    fun parsesFontWeightKeywordsAndNumbers() {
        assertEquals(FontWeight.Bold, InlineCss.parseFontWeight("bold"))
        assertEquals(FontWeight.Normal, InlineCss.parseFontWeight("normal"))
        assertEquals(FontWeight(700), InlineCss.parseFontWeight("700"))
    }

    @Test
    fun parsesTextDecorationCombinations() {
        assertEquals(TextDecoration.Underline, InlineCss.parseTextDecoration("underline"))
        assertEquals(TextDecoration.LineThrough, InlineCss.parseTextDecoration("line-through"))
        assertEquals(
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            InlineCss.parseTextDecoration("underline line-through"),
        )
        assertEquals(TextDecoration.None, InlineCss.parseTextDecoration("none"))
    }

    @Test
    fun parsesTextAlignKeywords() {
        assertEquals(TextAlign.Center, InlineCss.parseTextAlign("center"))
        assertEquals(TextAlign.End, InlineCss.parseTextAlign("right"))
        assertEquals(TextAlign.Start, InlineCss.parseTextAlign("left"))
        assertNull(InlineCss.parseTextAlign("bogus"))
    }

    @Test
    fun detectsMonospaceFontFamilies() {
        assertEquals(true, InlineCss.isMonospaceFontFamily("Consolas, monospace"))
        assertEquals(true, InlineCss.isMonospaceFontFamily("'Courier New', Courier, monospace"))
        assertEquals(false, InlineCss.isMonospaceFontFamily("Arial, sans-serif"))
    }

    @Test
    fun mergesRecognizedDeclarationsOntoAnInlineStyle() {
        val merged = InlineStyle().mergedWithCss("color: #ff0000; font-weight: bold; font-size: 2em")

        assertEquals(Color(0xFF, 0x00, 0x00), merged.color)
        assertEquals(true, merged.bold)
        assertEquals(2.0f, merged.sizeScale)
    }

    @Test
    fun leavesFieldsUntouchedForUnrecognizedOrAbsentProperties() {
        val base = InlineStyle(bold = true)
        val merged = base.mergedWithCss("letter-spacing: 1px")

        assertEquals(base, merged)
    }
}

package works.merc.keryx.app.ui.home

import androidx.compose.ui.text.font.FontWeight
import works.merc.keryx.app.data.local.FtsSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val S = FtsSearch.MARK_START
private const val E = FtsSearch.MARK_END

class SearchHighlightTest {
    @Test
    fun noMarkersProducesPlainTextWithNoBoldSpans() {
        val result = markedToAnnotatedString("Hello World")
        assertEquals("Hello World", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun singleMarkedRegionBecomesBoldSpan() {
        val result = markedToAnnotatedString("He${S}llo${E} World")
        assertEquals("Hello World", result.text)
        val span = result.spanStyles.single()
        assertEquals(2, span.start)
        assertEquals(5, span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun markedRegionGetsHighlighterSpanStyle() {
        // The matched span carries the full highlighter style (bold + marker background + text color),
        // not just bold — so hits stay visible even on already-bold unread titles.
        val result = markedToAnnotatedString("He${S}llo${E} World")
        val span = result.spanStyles.single()
        assertEquals(SearchHighlightSpanStyle, span.item)
    }

    @Test
    fun multipleMarkedRegionsEachBecomeBold() {
        val result = markedToAnnotatedString("${S}Cats${E} and ${S}Dogs${E}")
        assertEquals("Cats and Dogs", result.text)
        assertEquals(2, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(4, result.spanStyles[0].end)
        assertEquals(9, result.spanStyles[1].start)
        assertEquals(13, result.spanStyles[1].end)
    }

    @Test
    fun unclosedStartExtendsBoldToEnd() {
        val result = markedToAnnotatedString("Hello ${S}World")
        assertEquals("Hello World", result.text)
        val span = result.spanStyles.single()
        assertEquals(6, span.start)
        assertEquals(11, span.end)
    }

    @Test
    fun strayEndIsIgnored() {
        val result = markedToAnnotatedString("Hello${E} World")
        assertEquals("Hello World", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun redundantStartDoesNotOpenNestedSpan() {
        // A second start while already bold is ignored; one span covers the whole run.
        val result = markedToAnnotatedString("${S}Ko${S}tlin${E}")
        assertEquals("Kotlin", result.text)
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }
}

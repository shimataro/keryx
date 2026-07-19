package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleWebViewHtmlTest {
    @Test
    fun extractLinksReturnsEmptySetWhenNoLinks() {
        assertEquals(emptySet(), extractLinks("<p>no links here</p>"))
    }

    @Test
    fun extractLinksReturnsAbsoluteHrefs() {
        val html = """
            <p><a href="https://a.com/x">a</a></p>
            <p><a href="https://b.com/y">b</a></p>
        """.trimIndent()
        assertEquals(setOf("https://a.com/x", "https://b.com/y"), extractLinks(html))
    }

    @Test
    fun extractLinksFallsBackToRawHrefWhenNoBaseUriToResolveRelativePath() {
        // Ksoup.parse(html) is called without a base URI, so abs:href can't resolve a
        // relative path and the code falls back to the raw href attribute.
        assertEquals(setOf("/foo"), extractLinks("""<a href="/foo">link</a>"""))
    }

    @Test
    fun extractLinksIgnoresAnchorWithoutHref() {
        assertEquals(emptySet(), extractLinks("<a>no href</a>"))
    }

    @Test
    fun extractLinksCollapsesDuplicateHrefs() {
        val html = """
            <a href="https://a.com/x">one</a>
            <a href="https://a.com/x">two</a>
        """.trimIndent()
        assertEquals(setOf("https://a.com/x"), extractLinks(html))
    }

    @Test
    fun wrapArticleHtmlContainsBodyUnchanged() {
        val body = "<p>hello & world</p>"
        val result = wrapArticleHtml(
            body = body,
            surface = Color(1f, 1f, 1f),
            onSurface = Color(0f, 0f, 0f),
            linkColor = Color(0f, 0f, 1f),
            fontScale = 1.0f,
            title = "",
            meta = "",
            mutedColor = Color(0.5f, 0.5f, 0.5f),
        )
        assertTrue(result.contains(body))
    }

    @Test
    fun wrapArticleHtmlContainsExpectedColorsAndFontSize() {
        val result = wrapArticleHtml(
            body = "<p>body</p>",
            surface = Color(1f, 1f, 1f),
            onSurface = Color(0f, 0f, 0f),
            linkColor = Color(1f, 0f, 0f),
            fontScale = 1.5f,
            title = "",
            meta = "",
            mutedColor = Color(0.5f, 0.5f, 0.5f),
        )
        assertTrue(result.contains("background-color: #ffffff;"))
        assertTrue(result.contains("color: #000000;"))
        assertTrue(result.contains("a { color: #ff0000; }"))
        assertTrue(result.contains("font-size: 150%;"))
    }

    @Test
    fun wrapArticleHtmlComputesFontPercentForDefaultScale() {
        val result = wrapArticleHtml(
            body = "<p>body</p>",
            surface = Color(1f, 1f, 1f),
            onSurface = Color(0f, 0f, 0f),
            linkColor = Color(0f, 0f, 0f),
            fontScale = 1.0f,
            title = "",
            meta = "",
            mutedColor = Color(0.5f, 0.5f, 0.5f),
        )
        assertTrue(result.contains("font-size: 100%;"))
    }

    @Test
    fun wrapArticleHtmlRendersEscapedTitleAndMeta() {
        val result = wrapArticleHtml(
            body = "<p>body</p>",
            surface = Color(1f, 1f, 1f),
            onSurface = Color(0f, 0f, 0f),
            linkColor = Color(0f, 0f, 0f),
            fontScale = 1.0f,
            title = "A <b> & \"quoted\" title",
            meta = "Alice · 2026-07-13 09:00",
            mutedColor = Color(0.5f, 0.5f, 0.5f),
        )
        assertTrue(result.contains("""<h1 class="article-title">A &lt;b&gt; &amp; &quot;quoted&quot; title</h1>"""))
        assertTrue(result.contains("""<div class="article-meta">Alice · 2026-07-13 09:00</div>"""))
        // The raw title must not leak through as live markup.
        assertTrue(!result.contains("<b>"))
        // Body stays raw.
        assertTrue(result.contains("<p>body</p>"))
    }

    @Test
    fun wrapArticleHtmlOmitsTitleAndMetaWhenBlank() {
        val result = wrapArticleHtml(
            body = "<p>body</p>",
            surface = Color(1f, 1f, 1f),
            onSurface = Color(0f, 0f, 0f),
            linkColor = Color(0f, 0f, 0f),
            fontScale = 1.0f,
            title = "",
            meta = "",
            mutedColor = Color(0.5f, 0.5f, 0.5f),
        )
        // The CSS rules for these classes are always present; assert the *elements* aren't emitted.
        assertTrue(!result.contains("""<h1 class="article-title">"""))
        assertTrue(!result.contains("""<div class="article-meta">"""))
    }

    @Test
    fun escapeHtmlEscapesSpecialCharactersWithoutDoubleEscapingAmpersand() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&#39;",
            escapeHtml("""&<>"'"""),
        )
        // Ampersand replaced first, so an existing entity-like sequence isn't double-escaped.
        assertEquals("a &amp;amp; b", escapeHtml("a &amp; b"))
    }

    @Test
    fun toCssHexConvertsPureWhite() {
        assertEquals("#ffffff", Color(1f, 1f, 1f).toCssHex())
    }

    @Test
    fun toCssHexConvertsPureBlack() {
        assertEquals("#000000", Color(0f, 0f, 0f).toCssHex())
    }

    @Test
    fun toCssHexConvertsPureRed() {
        assertEquals("#ff0000", Color(1f, 0f, 0f).toCssHex())
    }

    @Test
    fun toCssHexConvertsMidGray() {
        // 128 / 255 -> 0x80
        assertEquals("#808080", Color(128f / 255f, 128f / 255f, 128f / 255f).toCssHex())
    }
}

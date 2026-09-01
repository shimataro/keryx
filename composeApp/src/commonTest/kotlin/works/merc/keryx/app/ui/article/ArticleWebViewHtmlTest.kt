package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleWebViewHtmlTest {
    private val theme = ArticleHtmlTheme(
        surface = Color(1f, 1f, 1f),
        onSurface = Color(0f, 0f, 0f),
        linkColor = Color(0f, 0f, 1f),
        mutedColor = Color(0.5f, 0.5f, 0.5f),
        fontScale = 1.0f,
    )

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
    fun extractLinksDropsRelativeHrefWhenNoBaseUriToResolveAgainst() {
        // A relative href can't be resolved without a base URI, and an unresolved raw string
        // could never match the WebView's own absolutely-resolved navigation request, so it's
        // dropped rather than kept as-is.
        assertEquals(emptySet(), extractLinks("""<a href="/foo">link</a>"""))
    }

    @Test
    fun extractLinksResolvesRelativeHrefAgainstProvidedBaseUri() {
        assertEquals(
            setOf("https://example.com/foo"),
            extractLinks("""<a href="/foo">link</a>""", baseUri = "https://example.com/article/1"),
        )
    }

    @Test
    fun extractLinksResolvesAbsoluteHrefRegardlessOfBaseUri() {
        assertEquals(
            setOf("https://a.com/x"),
            extractLinks("""<a href="https://a.com/x">a</a>""", baseUri = "https://example.com/article/1"),
        )
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
        val result = wrapArticleHtml(theme, title = "", meta = "", body = body)
        assertTrue(result.contains(body))
    }

    @Test
    fun wrapArticleHtmlContainsExpectedColorsAndFontSize() {
        val customTheme = theme.copy(linkColor = Color(1f, 0f, 0f), fontScale = 1.5f)
        val result = wrapArticleHtml(customTheme, title = "", meta = "", body = "<p>body</p>")
        assertTrue(result.contains("background-color: #ffffff;"))
        assertTrue(result.contains("color: #000000;"))
        assertTrue(result.contains("a { color: #ff0000; }"))
        assertTrue(result.contains("font-size: 150%;"))
    }

    @Test
    fun wrapArticleHtmlComputesFontPercentForDefaultScale() {
        val result = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>")
        assertTrue(result.contains("font-size: 100%;"))
    }

    @Test
    fun wrapArticleHtmlAppliesFontScaleOnlyOnce() {
        // font-size on a percentage is relative to the parent's computed size, so body (whose
        // parent is html) must inherit rather than repeat the percentage — otherwise a 150%
        // scale would compound to 225% (150% of the already-scaled html size).
        val scaledTheme = theme.copy(fontScale = 1.5f)
        val result = wrapArticleHtml(scaledTheme, title = "", meta = "", body = "<p>body</p>")
        val htmlOnlyRule = Regex("""html\s*\{[^}]*}""").find(result)?.value
        val sharedRule = Regex("""html,\s*body\s*\{[^}]*}""").find(result)?.value
        assertTrue(htmlOnlyRule != null && htmlOnlyRule.contains("font-size: 150%;"))
        assertTrue(sharedRule != null && !sharedRule.contains("font-size"))
    }

    @Test
    fun wrapArticleHtmlWrapsTitleInLinkWhenUrlProvided() {
        val result = wrapArticleHtml(
            theme,
            title = "My Title",
            meta = "",
            body = "<p>body</p>",
            titleUrl = "https://example.com/article",
            titleTooltip = "Open in browser",
        )
        assertTrue(result.contains("""<h1 class="article-title"><a href="https://example.com/article" title="Open in browser">My Title</a></h1>"""))
    }

    @Test
    fun wrapArticleHtmlOmitsTitleLinkWhenUrlIsNullOrBlank() {
        val withNull = wrapArticleHtml(theme, title = "My Title", meta = "", body = "<p>body</p>", titleUrl = null)
        val withBlank = wrapArticleHtml(theme, title = "My Title", meta = "", body = "<p>body</p>", titleUrl = "")
        assertTrue(withNull.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(withBlank.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(!withNull.contains("<a "))
        assertTrue(!withBlank.contains("<a "))
    }

    @Test
    fun wrapArticleHtmlOmitsTitleLinkForNonHttpUrls() {
        val malicious = wrapArticleHtml(
            theme,
            title = "My Title",
            meta = "",
            body = "<p>body</p>",
            titleUrl = "javascript:alert(1)",
        )
        val dataUrl = wrapArticleHtml(
            theme,
            title = "My Title",
            meta = "",
            body = "<p>body</p>",
            titleUrl = "data:text/html,<script>alert(1)</script>",
        )
        val upperCaseScheme = wrapArticleHtml(
            theme,
            title = "My Title",
            meta = "",
            body = "<p>body</p>",
            titleUrl = "JAVASCRIPT:alert(1)",
        )
        assertTrue(malicious.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(dataUrl.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(upperCaseScheme.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(!malicious.contains("<a "))
        assertTrue(!dataUrl.contains("<a "))
        assertTrue(!upperCaseScheme.contains("<a "))
    }

    @Test
    fun wrapArticleHtmlRendersEscapedTitleAndMeta() {
        val result = wrapArticleHtml(
            theme,
            title = "A <b> & \"quoted\" title",
            meta = "Alice · 2026-07-13 09:00",
            body = "<p>body</p>",
        )
        assertTrue(result.contains("""<h1 class="article-title">A &lt;b&gt; &amp; &quot;quoted&quot; title</h1>"""))
        assertTrue(result.contains("""<div class="article-meta">Alice · 2026-07-13 09:00</div>"""))
        // The raw title must not leak through as live markup.
        assertTrue(!result.contains("<b>"))
        // Body stays raw.
        assertTrue(result.contains("<p>body</p>"))
    }

    @Test
    fun wrapArticleHtmlEmitsBaseHrefWhenUrlProvided() {
        val result = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>", baseUrl = "https://example.com/article/1")
        assertTrue(result.contains("""<base href="https://example.com/article/1" />"""))
    }

    @Test
    fun wrapArticleHtmlOmitsBaseHrefWhenUrlIsNullOrBlank() {
        val withNull = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>", baseUrl = null)
        val withBlank = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>", baseUrl = "")
        assertTrue(!withNull.contains("<base"))
        assertTrue(!withBlank.contains("<base"))
    }

    @Test
    fun wrapArticleHtmlEscapesBaseHrefUrl() {
        val result = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>", baseUrl = """https://example.com/?a=1&b="2"""")
        assertTrue(result.contains("""<base href="https://example.com/?a=1&amp;b=&quot;2&quot;" />"""))
    }

    @Test
    fun wrapArticleHtmlOmitsTitleAndMetaWhenBlank() {
        val result = wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>")
        // The CSS rules for these classes are always present; assert the *elements* aren't emitted.
        assertTrue(!result.contains("""<h1 class="article-title">"""))
        assertTrue(!result.contains("""<div class="article-meta">"""))
    }

    @Test
    fun articleNoContentHtmlWrapsTitleInLinkWhenUrlProvided() {
        val result = articleNoContentHtml(
            theme,
            title = "My Title",
            meta = "Alice · now",
            message = "No content",
            titleUrl = "https://example.com/article",
            titleTooltip = "Open in browser",
        )
        assertTrue(result.contains("""<h1 class="article-title"><a href="https://example.com/article" title="Open in browser">My Title</a></h1>"""))
    }

    @Test
    fun wrapArticleHtmlContainsTitleLinkStyles() {
        val result = wrapArticleHtml(theme, title = "My Title", meta = "", body = "<p>body</p>", titleUrl = "https://example.com/article")
        assertTrue(result.contains(".article-title a { color: inherit; text-decoration: none; cursor: pointer; transition: opacity 0.15s ease; }"))
        assertTrue(result.contains(".article-title a:hover { opacity: 0.7; }"))
        assertTrue(result.contains(".article-title a:active { opacity: 0.5; }"))
    }

    @Test
    fun articlePlaceholderHtmlRendersOnlyTheCenteredEscapedMessage() {
        val result = articlePlaceholderHtml(theme, "Select <an> article & read it")
        assertTrue(result.contains("""<div class="article-placeholder">Select &lt;an&gt; article &amp; read it</div>"""))
        assertTrue(!result.contains("""<h1 class="article-title">"""))
        assertTrue(!result.contains("""<div class="article-meta">"""))
        assertTrue(!result.contains("<an>"))
    }

    @Test
    fun articleNoContentHtmlOmitsTitleLinkForNonHttpUrls() {
        val result = articleNoContentHtml(
            theme,
            title = "My Title",
            meta = "Alice · now",
            message = "No content",
            titleUrl = "javascript:alert(1)",
        )
        assertTrue(result.contains("""<h1 class="article-title">My Title</h1>"""))
        assertTrue(!result.contains("<a "))
    }

    @Test
    fun articleNoContentHtmlRendersHeaderAboveTheEscapedNotice() {
        val result = articleNoContentHtml(theme, title = "My Title", meta = "Alice · now", message = "No <b>content</b>")
        val titleIndex = result.indexOf("""<h1 class="article-title">My Title</h1>""")
        val metaIndex = result.indexOf("""<div class="article-meta">Alice · now</div>""")
        val noticeIndex = result.indexOf("""<p class="article-notice">No &lt;b&gt;content&lt;/b&gt;</p>""")
        assertTrue(titleIndex >= 0 && metaIndex >= 0 && noticeIndex >= 0)
        assertTrue(titleIndex < metaIndex)
        assertTrue(metaIndex < noticeIndex)
        assertTrue(!result.contains("<b>content</b>"))
    }

    @Test
    fun everyDocumentPaintsTheThemeBackgroundAndFontScale() {
        val scaledTheme = theme.copy(fontScale = 1.5f)
        val documents = listOf(
            wrapArticleHtml(scaledTheme, title = "", meta = "", body = "<p>body</p>"),
            articleNoContentHtml(scaledTheme, title = "", meta = "", message = "empty"),
            articlePlaceholderHtml(scaledTheme, "placeholder"),
        )
        for (document in documents) {
            assertTrue(document.contains("background-color: #ffffff;"))
            assertTrue(document.contains("color: #000000;"))
            assertTrue(document.contains("font-size: 150%;"))
        }
    }

    @Test
    fun everyDocumentSharesTheSameStyleBlock() {
        fun styleBlockOf(document: String): String {
            val start = document.indexOf("<style>")
            val end = document.indexOf("</style>") + "</style>".length
            return document.substring(start, end)
        }
        val a = styleBlockOf(wrapArticleHtml(theme, title = "", meta = "", body = "<p>body</p>"))
        val b = styleBlockOf(articleNoContentHtml(theme, title = "", meta = "", message = "empty"))
        val c = styleBlockOf(articlePlaceholderHtml(theme, "placeholder"))
        assertEquals(a, b)
        assertEquals(b, c)
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

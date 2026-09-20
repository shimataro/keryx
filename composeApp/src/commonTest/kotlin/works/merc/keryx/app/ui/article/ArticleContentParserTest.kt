package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is fed the reader's own assembled documents rather than bare body fragments, since
 * that is exactly what it receives in production — see [parseArticleContent]'s KDoc.
 */
class ArticleContentParserTest {
    private val theme = ArticleHtmlTheme(
        surface = Color(1f, 1f, 1f),
        onSurface = Color(0f, 0f, 0f),
        linkColor = Color(0f, 0f, 1f),
        mutedColor = Color(0.5f, 0.5f, 0.5f),
        fontScale = 1.0f,
    )

    private fun parseBody(body: String, baseUrl: String? = null): List<ArticleBlock> =
        parseArticleContent(wrapArticleHtml(theme, title = "T", meta = "M", body = body, baseUrl = baseUrl)).blocks

    private fun ArticleInline.plain(): String = spans.joinToString("") { it.text }

    @Test
    fun extractsTitleMetaAndTitleLinkWithoutLeakingThemIntoBlocks() {
        val content = parseArticleContent(
            wrapArticleHtml(
                theme,
                title = "Hello & goodbye",
                meta = "Author · 2026-01-01",
                body = "<p>body</p>",
                baseUrl = "https://example.com/a",
                titleUrl = "https://example.com/a",
            ),
        )

        assertEquals("Hello & goodbye", content.title)
        assertEquals("Author · 2026-01-01", content.meta)
        assertEquals("https://example.com/a", content.titleUrl)
        // The header is drawn separately, so it must not also appear as body content.
        assertEquals(listOf("body"), content.blocks.map { (it as ArticleBlock.Paragraph).text.plain() })
    }

    @Test
    fun placeholderDocumentBecomesCenteredNoticeOnly() {
        val content = parseArticleContent(articlePlaceholderHtml(theme, "記事が選択されていません"))

        assertEquals("記事が選択されていません", content.centeredNotice)
        assertTrue(content.blocks.isEmpty())
        assertNull(content.title)
    }

    @Test
    fun noContentDocumentKeepsItsNoticeAsOrdinaryText() {
        val content = parseArticleContent(
            articleNoContentHtml(theme, title = "T", meta = "M", message = "本文がありません"),
        )

        assertNull(content.centeredNotice)
        assertEquals("T", content.title)
        assertEquals(
            listOf("本文がありません"),
            content.blocks.map { (it as ArticleBlock.Paragraph).text.plain() },
        )
    }

    @Test
    fun mapsHeadingLevels() {
        val blocks = parseBody("<h2>Two</h2><h4>Four</h4>")

        assertEquals(
            listOf(2 to "Two", 4 to "Four"),
            blocks.map { (it as ArticleBlock.Heading).let { h -> h.level to h.text.plain() } },
        )
    }

    @Test
    fun carriesInlineDecorations() {
        val blocks = parseBody("<p>plain <b>bold</b> <i>italic</i> <code>code</code> <s>gone</s></p>")

        val spans = (blocks.single() as ArticleBlock.Paragraph).text.spans
        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "italic" && it.italic })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "gone" && it.strikethrough })
    }

    @Test
    fun nestedInlineElementsInheritOuterDecorations() {
        val blocks = parseBody("""<p><b>bold <a href="https://example.com/x">link</a></b></p>""")

        val link = (blocks.single() as ArticleBlock.Paragraph).text.spans.single { it.text == "link" }
        assertTrue(link.bold)
        assertEquals("https://example.com/x", link.link)
    }

    @Test
    fun resolvesRelativeLinkAgainstTheDocumentBase() {
        val blocks = parseBody("""<p><a href="/next">next</a></p>""", baseUrl = "https://example.com/article/1")

        val span = (blocks.single() as ArticleBlock.Paragraph).text.spans.single()
        assertEquals("https://example.com/next", span.link)
    }

    @Test
    fun keepsTextOfAnUnresolvableLinkButDropsTheLinkItself() {
        // No <base> is emitted without an article URL, so a relative href cannot be resolved.
        val blocks = parseBody("""<p><a href="/next">next</a></p>""")

        val span = (blocks.single() as ArticleBlock.Paragraph).text.spans.single()
        assertEquals("next", span.text)
        assertNull(span.link)
    }

    @Test
    fun buildsNestedLists() {
        val blocks = parseBody("<ul><li>one<ul><li>inner</li></ul></li><li>two</li></ul>")

        val bullets = blocks.single() as ArticleBlock.Bullets
        assertEquals(false, bullets.ordered)
        assertEquals(2, bullets.items.size)
        assertEquals("one", (bullets.items[0][0] as ArticleBlock.Paragraph).text.plain())
        val inner = bullets.items[0][1] as ArticleBlock.Bullets
        assertEquals("inner", (inner.items.single().single() as ArticleBlock.Paragraph).text.plain())
    }

    @Test
    fun marksOrderedLists() {
        val bullets = parseBody("<ol><li>first</li></ol>").single() as ArticleBlock.Bullets
        assertTrue(bullets.ordered)
    }

    @Test
    fun preservesWhitespaceInCodeBlocksOnly() {
        val blocks = parseBody("<pre><code>fun main() {\n    println()\n}</code></pre><p>a\n    b</p>")

        assertEquals("fun main() {\n    println()\n}", (blocks[0] as ArticleBlock.Code).text)
        // Outside <pre>, source line breaks and indentation collapse the way a browser collapses them.
        assertEquals("a b", (blocks[1] as ArticleBlock.Paragraph).text.plain())
    }

    @Test
    fun dropsScriptAndStyleContent() {
        val blocks = parseBody("<script>alert('x')</script><style>p{color:red}</style><p>real</p>")

        assertEquals(listOf("real"), blocks.map { (it as ArticleBlock.Paragraph).text.plain() })
    }

    @Test
    fun recursesThroughContainerElements() {
        val blocks = parseBody("<div><section><p>deep</p></section></div>")

        assertEquals("deep", (blocks.single() as ArticleBlock.Paragraph).text.plain())
    }

    @Test
    fun keepsBareTextBetweenBlocksAsItsOwnParagraph() {
        val blocks = parseBody("<div>loose text<p>in a p</p></div>")

        assertEquals(
            listOf("loose text", "in a p"),
            blocks.map { (it as ArticleBlock.Paragraph).text.plain() },
        )
    }

    @Test
    fun resolvesImagesAndDropsUnresolvableOnes() {
        val resolved = parseBody(
            """<img src="/img.png" alt="shown">""",
            baseUrl = "https://example.com/article/1",
        ).single() as ArticleBlock.Picture
        assertEquals("https://example.com/img.png", resolved.src)
        assertEquals("shown", resolved.alt)

        // Unresolvable: a broken image is worse than none.
        assertTrue(parseBody("""<img src="/img.png">""").isEmpty())
    }

    @Test
    fun treatsFigcaptionAsACaption() {
        val blocks = parseBody(
            """<figure><img src="https://example.com/i.png"><figcaption>cap</figcaption></figure>""",
        )

        val figure = blocks.single() as ArticleBlock.Figure
        assertTrue(figure.children[0] is ArticleBlock.Picture)
        assertEquals("cap", (figure.children[1] as ArticleBlock.Caption).text.plain())
    }

    @Test
    fun turnsEmbedsIntoEmbedBlocks() {
        val blocks = parseBody("""<iframe src="https://www.youtube.com/embed/x"></iframe>""")

        assertEquals("https://www.youtube.com/embed/x", (blocks.single() as ArticleBlock.Embed).url)
    }

    @Test
    fun readsEmbedUrlFromAChildSourceElement() {
        val blocks = parseBody("""<video><source src="https://example.com/v.mp4"></video>""")

        assertEquals("https://example.com/v.mp4", (blocks.single() as ArticleBlock.Embed).url)
    }

    @Test
    fun buildsTableRowsAcrossSectionElements() {
        val table = parseBody(
            "<table><thead><tr><th>h1</th><th>h2</th></tr></thead><tbody><tr><td>a</td><td>b</td></tr></tbody></table>",
        ).single() as ArticleBlock.Table

        assertEquals(
            listOf(listOf("h1", "h2"), listOf("a", "b")),
            table.rows.map { row -> row.cells.map { it.plain() } },
        )
        assertEquals(listOf(true, false), table.rows.map { it.isHeader })
    }

    @Test
    fun mapsHorizontalRule() {
        assertEquals(ArticleBlock.Rule, parseBody("<hr>").single())
    }

    @Test
    fun decodesEntities() {
        val blocks = parseBody("<p>a &amp; b &lt;c&gt; &#169;</p>")

        assertEquals("a & b <c> ©", (blocks.single() as ArticleBlock.Paragraph).text.plain())
    }

    @Test
    fun keepsAnInlineImagesAltTextInsideAParagraph() {
        val blocks = parseBody("""<p>before <img src="https://example.com/i.png" alt="pic"> after</p>""")

        val paragraph = assertNotNull(blocks.single() as? ArticleBlock.Paragraph)
        assertTrue(paragraph.text.plain().contains("pic"))
    }

    @Test
    fun ignoresEmptyBlocks() {
        assertTrue(parseBody("<p></p><p>   </p><ul></ul><blockquote></blockquote>").isEmpty())
    }

    @Test
    fun unknownInlineTagsDoNotSplitAParagraph() {
        // ksoup has no entry for <ruby> or an inline <svg> icon, so both default to a generic,
        // non-block tag — exactly like a browser's own UA stylesheet treats an unrecognized element.
        val blocks = parseBody("<p>before <ruby>漢字<rt>かんじ</rt></ruby> middle <svg></svg> after</p>")

        assertEquals(1, blocks.size)
        val text = (blocks.single() as ArticleBlock.Paragraph).text.plain()
        assertTrue(text.contains("before"))
        assertTrue(text.contains("middle"))
        assertTrue(text.contains("after"))
    }

    @Test
    fun carriesUnderlineHighlightAndSizeDecorations() {
        val spans = (parseBody("<p><u>under</u> <mark>hi</mark> <small>sm</small> <big>bg</big></p>").single() as ArticleBlock.Paragraph)
            .text.spans

        assertTrue(spans.any { it.text == "under" && it.underline })
        assertTrue(spans.any { it.text == "hi" && it.highlight })
        assertTrue(spans.any { it.text == "sm" && it.sizeScale < 1f })
        assertTrue(spans.any { it.text == "bg" && it.sizeScale > 1f })
    }

    @Test
    fun carriesSubAndSuperscriptBaseline() {
        val spans = (parseBody("<p>x<sub>2</sub> and y<sup>3</sup></p>").single() as ArticleBlock.Paragraph).text.spans

        assertTrue(spans.any { it.text == "2" && it.baseline == InlineBaseline.Sub })
        assertTrue(spans.any { it.text == "3" && it.baseline == InlineBaseline.Super })
    }

    @Test
    fun honorsInlineStyleColorSizeAndWeight() {
        val spans = (parseBody("""<p><span style="color:#ff0000; font-size:2em; font-weight:bold">red</span></p>""").single() as ArticleBlock.Paragraph)
            .text.spans

        val span = spans.single { it.text == "red" }
        assertEquals(Color(0xFF, 0x00, 0x00), span.color)
        assertEquals(2.0f, span.sizeScale)
        assertTrue(span.bold)
    }

    @Test
    fun honorsBlockLevelTextAlign() {
        val paragraph = parseBody("""<p style="text-align:center">centered</p>""").single() as ArticleBlock.Paragraph
        assertEquals(TextAlign.Center, paragraph.align)
    }

    @Test
    fun centerTagForcesAlignOnItsBlocks() {
        val blocks = parseBody("<center><p>a</p><h2>b</h2></center>")

        assertEquals(TextAlign.Center, (blocks[0] as ArticleBlock.Paragraph).align)
        assertEquals(TextAlign.Center, (blocks[1] as ArticleBlock.Heading).align)
    }

    @Test
    fun resolvesSrcsetAndLazyLoadAttributes() {
        val fromSrcset = parseBody(
            """<img data-srcset="/a.jpg 1x, /b.jpg 2x">""",
            baseUrl = "https://example.com/x",
        ).single() as ArticleBlock.Picture
        assertEquals("https://example.com/a.jpg", fromSrcset.src)

        val fromDataSrc = parseBody(
            """<img data-src="/lazy.jpg">""",
            baseUrl = "https://example.com/x",
        ).single() as ArticleBlock.Picture
        assertEquals("https://example.com/lazy.jpg", fromDataSrc.src)
    }

    @Test
    fun promotesAnImageOnlyParagraphToABlockPicture() {
        val blocks = parseBody(
            """<p><img src="/img.png"></p>""",
            baseUrl = "https://example.com/a",
        )

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ArticleBlock.Picture)
    }

    @Test
    fun promotesALinkWrappedImageWithNoTextToABlockPicture() {
        val blocks = parseBody(
            """<a href="https://example.com/full"><img src="/img.png"></a>""",
            baseUrl = "https://example.com/a",
        )

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ArticleBlock.Picture)
    }

    @Test
    fun honorsOrderedListStart() {
        val bullets = parseBody("""<ol start="5"><li>a</li></ol>""").single() as ArticleBlock.Bullets
        assertEquals(5, bullets.start)
    }

    @Test
    fun parsesDefinitionListsPairingEachTermWithItsDescription() {
        val blocks = parseBody("<dl><dt>Term</dt><dd>Def one</dd><dd>Def two</dd></dl>")

        assertEquals(2, blocks.size)
        val first = blocks[0] as ArticleBlock.Definition
        val second = blocks[1] as ArticleBlock.Definition
        assertEquals("Term", first.term.plain())
        assertEquals("Def one", first.description.plain())
        assertEquals("Term", second.term.plain())
        assertEquals("Def two", second.description.plain())
    }

    @Test
    fun noContentDocumentIsMarkedMuted() {
        val paragraph = parseArticleContent(
            articleNoContentHtml(theme, title = "T", meta = "M", message = "本文がありません"),
        ).blocks.single() as ArticleBlock.Paragraph

        assertTrue(paragraph.muted)
    }
}

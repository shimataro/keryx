package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import works.merc.keryx.app.data.remote.UrlResolver

/** Tags whose content is machinery rather than prose, and must not leak into the body text. */
private val DROPPED_TAGS = setOf("script", "style", "link", "noscript", "template")

/**
 * Tags always drawn as their own block, regardless of how ksoup itself classifies them — checked
 * before the general inline/block split below, since ksoup classifies several of these
 * (`img`/`iframe`/`embed`/`object`) as *inline* (phrasing content that merely happens to replace
 * itself with an external resource), which would otherwise route them into [inlineSpans] and
 * reduce an image to alt text instead of a real block.
 */
private val FORCED_BLOCK_TAGS = setOf(
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "pre", "img", "table",
    "figure", "figcaption", "dl", "hr", "iframe", "embed", "object", "video", "audio", "center",
)

/**
 * Tags ksoup classifies as block-level (its own [com.fleeksoft.ksoup.parser.Tag.isBlock]) but whose
 * HTML5 content model is "transparent"/phrasing — they render inline by default in every browser.
 * Every other inline/block decision defers entirely to ksoup's own [com.fleeksoft.ksoup.parser.Tag
 * .isInline], including for a tag ksoup has never heard of (an unknown tag defaults to a generic,
 * non-block [com.fleeksoft.ksoup.parser.Tag], so it is treated as inline the same way a browser's
 * own UA stylesheet defaults an unrecognized element to `display: inline`) — this is what stops an
 * unfamiliar tag (`<ruby>`, an inline `<svg>` icon, a CMS-specific wrapper) from splitting a
 * paragraph in two the way the previous hardcoded inline-tag allowlist did.
 */
private val FORCED_INLINE_TAGS = setOf("ins", "del", "button")

private val WHITESPACE = Regex("\\s+")

/**
 * Parses the reader's own assembled document — the very string the web view would have been
 * handed — into the model the Compose fallback draws.
 *
 * Taking the finished document rather than the raw article body looks roundabout, but it is what
 * keeps the two readers in step: the four states the reader can be in (placeholder, "no content",
 * header-only while the body loads, and a full article) are already decided upstream in
 * `ArticleDetailPane`'s document builders, so re-deriving them here would duplicate that logic and
 * let the two drift.
 */
internal fun parseArticleContent(html: String): ArticleContent {
    val document = Ksoup.parse(html)
    val body = document.body()

    // The placeholder document has no article at all — the whole body is one centered message.
    body.selectFirst(".article-placeholder")?.let { placeholder ->
        return ArticleContent(centeredNotice = placeholder.text().trim().ifEmpty { null })
    }

    // <base href> is present only when the article has a URL of its own; without it a relative
    // reference is unresolvable, and UrlResolver.resolve reports that by returning null.
    val base = document.selectFirst("base")?.attr("href").orEmpty()

    val titleElement = body.selectFirst("h1.article-title")
    val metaElement = body.selectFirst(".article-meta")
    val header = setOfNotNull(titleElement, metaElement)

    return ArticleContent(
        title = titleElement?.text()?.trim()?.ifEmpty { null },
        titleUrl = titleElement?.selectFirst("a")?.let { resolveAttr(it, "href", base) },
        meta = metaElement?.text()?.trim()?.ifEmpty { null },
        blocks = parseBlocks(body, base, skip = header),
    )
}

/**
 * Walks [parent]'s children, emitting a block per block-level element and gathering everything
 * between them — bare text and inline elements alike — into paragraphs of their own.
 *
 * @param skip Elements to leave out entirely, used for the header the caller renders separately.
 * @param depth The nesting level of the *list* this call is inside (0 outside any list), used to
 * cycle the marker style/indent a new nested `<ul>`/`<ol>` found here would get — see [bullets].
 */
private fun parseBlocks(parent: Element, base: String, skip: Set<Element> = emptySet(), depth: Int = 0): List<ArticleBlock> {
    val blocks = mutableListOf<ArticleBlock>()
    val pending = mutableListOf<InlineSpan>()
    val pendingImages = mutableListOf<Element>()

    fun flushPending() {
        emitInlineOrPictures(pending.toList(), pendingImages.toList(), base, blocks = blocks)
        pending.clear()
        pendingImages.clear()
    }

    for (node in parent.childNodes()) {
        if (node is TextNode) {
            pending += InlineSpan(node.getWholeText().replace(WHITESPACE, " "))
            continue
        }
        if (node !is Element || node in skip) continue

        val tag = node.normalName()
        if (tag in DROPPED_TAGS) continue
        if (tag == "br") {
            pending += InlineSpan("\n")
            continue
        }
        if (tag in FORCED_BLOCK_TAGS) {
            flushPending()
            when (tag) {
                "p" -> {
                    val images = mutableListOf<Element>()
                    val spans = inlineSpans(node, base, blockBaseStyle(node), images)
                    emitInlineOrPictures(
                        spans,
                        images,
                        base,
                        align = resolveBlockAlign(node),
                        muted = node.hasClass("article-notice"),
                        blocks = blocks,
                    )
                }
                "figcaption" -> inlineBlock(node, base, blockBaseStyle(node))?.let {
                    // A caption defaults to centered under its picture unless the markup says
                    // otherwise — the reader document never sets this itself, so there is nothing
                    // this would override.
                    blocks += ArticleBlock.Caption(it, align = resolveBlockAlign(node) ?: TextAlign.Center)
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> inlineBlock(node, base, blockBaseStyle(node))?.let {
                    blocks += ArticleBlock.Heading(tag.substring(1).toInt(), it, align = resolveBlockAlign(node))
                }
                "ul", "ol" -> blocks += bullets(node, base, ordered = tag == "ol", depth = depth)
                "blockquote" -> parseBlocks(node, base, depth = depth).takeIf { it.isNotEmpty() }?.let { blocks += ArticleBlock.Quote(it) }
                // wholeText(), not text(): a code block's own line breaks and indentation are its content.
                "pre" -> node.wholeText().trimEnd().takeIf { it.isNotBlank() }?.let { blocks += ArticleBlock.Code(it) }
                "img" -> picture(node, base)?.let { blocks += it }
                "table" -> table(node, base)?.let { blocks += it }
                "dl" -> blocks += definitionList(node, base)
                "figure" -> parseBlocks(node, base, depth = depth).takeIf { it.isNotEmpty() }?.let { blocks += ArticleBlock.Figure(it) }
                "iframe", "embed", "object", "video", "audio" -> embed(node, base)?.let { blocks += it }
                "hr" -> blocks += ArticleBlock.Rule
                // <center> has no decoration of its own beyond forcing every block under it to
                // center — applied as a post-pass rather than threaded through parseBlocks, since
                // only three block kinds actually carry an align field.
                "center" -> blocks += parseBlocks(node, base, depth = depth).map(::forceCenterAlign)
            }
            continue
        }
        if (tag in FORCED_INLINE_TAGS || node.tag().isInline()) {
            pending += inlineSpans(node, base, InlineStyle().extendedBy(tag, node, base), pendingImages)
            continue
        }

        // Anything else — div, section, article, unknown block-level markup — is a container:
        // recurse so its content is kept rather than flattened or dropped.
        flushPending()
        blocks += parseBlocks(node, base, depth = depth)
    }
    flushPending()
    return blocks
}

/** The inline content of [element] as one block, or null when it carries no visible text. */
private fun inlineBlock(element: Element, base: String, baseStyle: InlineStyle = InlineStyle()): ArticleInline? =
    ArticleInline(inlineSpans(element, base, baseStyle, mutableListOf())).trimEdges().takeIf { !it.isBlank }

/** [element]'s own `style=""` attribute as a starting [InlineStyle], for a tag with no decoration of its own. */
private fun blockBaseStyle(element: Element): InlineStyle {
    val style = element.attr("style")
    return if (style.isBlank()) InlineStyle() else InlineStyle().mergedWithCss(style)
}

/** [element]'s own text alignment, from `style="text-align:..."` first, then the deprecated `align=""`. */
private fun resolveBlockAlign(element: Element): TextAlign? {
    InlineCss.parseDeclarations(element.attr("style"))["text-align"]?.let { InlineCss.parseTextAlign(it) }?.let { return it }
    return when (element.attr("align").trim().lowercase()) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.End
        "left" -> TextAlign.Start
        "justify" -> TextAlign.Justify
        else -> null
    }
}

/** Forces every block under a `<center>` to center, unless it already names its own alignment. */
private fun forceCenterAlign(block: ArticleBlock): ArticleBlock = when (block) {
    is ArticleBlock.Paragraph -> if (block.align == null) block.copy(align = TextAlign.Center) else block
    is ArticleBlock.Caption -> if (block.align == null) block.copy(align = TextAlign.Center) else block
    is ArticleBlock.Heading -> if (block.align == null) block.copy(align = TextAlign.Center) else block
    is ArticleBlock.Quote -> block.copy(children = block.children.map(::forceCenterAlign))
    is ArticleBlock.Figure -> block.copy(children = block.children.map(::forceCenterAlign))
    else -> block
}

/**
 * Emits [spans] as a single [ArticleBlock.Paragraph], unless every span is blank text and [images]
 * holds at least one picture — in which case each image is promoted to its own block-level
 * [ArticleBlock.Picture] instead of collapsing to nothing. This recovers the common
 * `<p><img></p>` / `<a href="..."><img></a>` pattern, where the "paragraph" or "link" is really
 * just a captionless image wrapper: without this, [ArticleInline.isBlank] made the whole run
 * disappear (a link with no text around a bare image has no text at all), and a *textful*
 * paragraph's own inline images stay alt text (see [inlineSpans]'s `img` branch) — an inline
 * image's real size is unknown, so there's no way to drop it into running text without breaking
 * the line's layout.
 */
private fun emitInlineOrPictures(
    spans: List<InlineSpan>,
    images: List<Element>,
    base: String,
    align: TextAlign? = null,
    muted: Boolean = false,
    blocks: MutableList<ArticleBlock>,
) {
    val inline = ArticleInline(spans).trimEdges()
    if (inline.isBlank) {
        for (image in images) picture(image, base)?.let { blocks += it }
        return
    }
    blocks += ArticleBlock.Paragraph(inline, align = align, muted = muted)
}

/**
 * Walks [node]'s children as inline content, accumulating any `<img>` found (directly or nested)
 * into [images] rather than resolving it here — the caller decides whether those images end up as
 * alt text (a textful paragraph) or promoted block pictures (see [emitInlineOrPictures]).
 */
private fun inlineSpans(node: Node, base: String, style: InlineStyle, images: MutableList<Element>): List<InlineSpan> = buildList {
    for (child in node.childNodes()) {
        when {
            child is TextNode -> add(
                InlineSpan(
                    text = child.getWholeText().replace(WHITESPACE, " "),
                    bold = style.bold,
                    italic = style.italic,
                    code = style.code,
                    strikethrough = style.strikethrough,
                    underline = style.underline,
                    highlight = style.highlight,
                    sizeScale = style.sizeScale,
                    baseline = style.baseline,
                    color = style.color,
                    background = style.background,
                    link = style.link,
                ),
            )

            child is Element -> {
                val tag = child.normalName()
                if (tag in DROPPED_TAGS) continue
                if (tag == "br") {
                    add(InlineSpan("\n", link = style.link))
                    continue
                }
                if (tag == "img") {
                    images.add(child)
                    // An inline image inside textful content: keep its alt text rather than
                    // dropping the image silently, since the block-level Picture branch never
                    // sees it (see emitInlineOrPictures for the image-only-paragraph case, which
                    // does).
                    child.attr("alt").trim().takeIf { it.isNotEmpty() }
                        ?.let { add(InlineSpan(it, italic = true, link = style.link)) }
                    continue
                }
                addAll(inlineSpans(child, base, style.extendedBy(tag, child, base), images))
            }
        }
    }
}

/**
 * [tag]'s UA-default decoration mapping, then [element]'s own `style=""` merged on top (matching
 * CSS's cascade — an inline style always wins over a tag's own default look). See [InlineStyle]'s
 * KDoc for why a feed's own colors are trusted even against the app's theme.
 */
private fun InlineStyle.extendedBy(tag: String, element: Element, base: String): InlineStyle {
    val tagStyle = when (tag) {
        "b", "strong" -> copy(bold = true)
        "i", "em", "cite", "q" -> copy(italic = true)
        "code", "kbd", "samp", "var", "tt" -> copy(code = true)
        "s", "del", "strike" -> copy(strikethrough = true)
        "u", "ins" -> copy(underline = true)
        "mark" -> copy(highlight = true)
        "small" -> copy(sizeScale = sizeScale * 0.83f)
        "big" -> copy(sizeScale = sizeScale * 1.2f)
        "sub" -> copy(sizeScale = sizeScale * 0.83f, baseline = InlineBaseline.Sub)
        "sup" -> copy(sizeScale = sizeScale * 0.83f, baseline = InlineBaseline.Super)
        // An unresolvable href leaves the text in place without a link, rather than offering a
        // tap that could go nowhere.
        "a" -> copy(link = resolveAttr(element, "href", base) ?: link)
        else -> this
    }
    val style = element.attr("style")
    return if (style.isBlank()) tagStyle else tagStyle.mergedWithCss(style)
}

/**
 * A `<ul>`/`<ol>` as one [ArticleBlock.Bullets], with each `<li>`'s own content recursed one list
 * [depth] deeper — that deeper value is what lets a nested list found while parsing an `<li>`'s
 * content cycle its own marker (see `ArticleBlockViews.kt`'s bullet-glyph selection).
 */
private fun bullets(element: Element, base: String, ordered: Boolean, depth: Int): List<ArticleBlock> {
    val items = element.children()
        .filter { it.normalName() == "li" }
        .map { li -> parseBlocks(li, base, depth = depth + 1) }
        .filter { it.isNotEmpty() }
    if (items.isEmpty()) return emptyList()
    val start = if (ordered) element.attr("start").toIntOrNull() ?: 1 else 1
    return listOf(ArticleBlock.Bullets(ordered, start = start, depth = depth, items = items))
}

/** A `<dl>` as a flat list of term/description pairs; a `<dt>` with several `<dd>`s repeats the term for each. */
private fun definitionList(element: Element, base: String): List<ArticleBlock> {
    val result = mutableListOf<ArticleBlock>()
    var currentTerm = ArticleInline(emptyList())
    for (child in element.children()) {
        when (child.normalName()) {
            "dt" -> currentTerm = inlineBlock(child, base) ?: ArticleInline(emptyList())
            "dd" -> inlineBlock(child, base)?.let { result += ArticleBlock.Definition(currentTerm, it) }
        }
    }
    return result
}

private fun table(element: Element, base: String): ArticleBlock.Table? {
    val rows = element.select("tr").mapNotNull { row ->
        val cellElements = row.children().filter { it.normalName() == "td" || it.normalName() == "th" }
        if (cellElements.isEmpty()) return@mapNotNull null
        val isHeader = cellElements.all { it.normalName() == "th" }
        val cells = cellElements.map { cell ->
            ArticleInline(inlineSpans(cell, base, blockBaseStyle(cell), mutableListOf())).trimEdges()
        }
        TableRow(cells, isHeader)
    }
    return if (rows.isEmpty()) null else ArticleBlock.Table(rows)
}

private fun picture(element: Element, base: String): ArticleBlock.Picture? {
    val src = resolveImageSrc(element, base) ?: return null
    return ArticleBlock.Picture(src, element.attr("alt").trim().ifEmpty { null })
}

/** Attributes a lazy-loading image commonly carries its real URL under, tried in order after `src`. */
private val LAZY_IMAGE_ATTRIBUTES = listOf("data-src", "data-original", "data-lazy-src")

/**
 * Resolves an `<img>`'s real source, trying (in order): `src`; the common lazy-load attributes;
 * `srcset`/`data-srcset` on the `<img>` itself; and, for an `<img>` inside a `<picture>` with no
 * usable attribute of its own, the first `<source>` sibling's `srcset`/`src`.
 */
private fun resolveImageSrc(element: Element, base: String): String? {
    resolveAttr(element, "src", base)?.let { return it }
    for (attribute in LAZY_IMAGE_ATTRIBUTES) {
        resolveAttr(element, attribute, base)?.let { return it }
    }
    resolveSrcset(element.attr("srcset"), base)?.let { return it }
    resolveSrcset(element.attr("data-srcset"), base)?.let { return it }
    val pictureParent = element.parent()?.takeIf { it.normalName() == "picture" }
    pictureParent?.selectFirst("source")?.let { source ->
        resolveSrcset(source.attr("srcset"), base)?.let { return it }
        resolveAttr(source, "src", base)?.let { return it }
    }
    return null
}

/** The first candidate URL in a `srcset`/`data-srcset` list (`"a.jpg 1x, b.jpg 2x"` → `a.jpg`). */
private fun resolveSrcset(value: String, base: String): String? {
    val first = value.split(',').firstOrNull()?.trim()?.substringBefore(' ')?.trim()
    return first?.takeIf { it.isNotEmpty() }?.let { UrlResolver.resolve(base, it) }
}

private fun embed(element: Element, base: String): ArticleBlock.Embed? {
    // <object> names its resource "data"; <video>/<audio> may carry it on a child <source>.
    val url = resolveAttr(element, "src", base)
        ?: resolveAttr(element, "data", base)
        ?: element.select("source").firstNotNullOfOrNull { resolveAttr(it, "src", base) }
    return url?.let { ArticleBlock.Embed(it) }
}

/** Resolves [element]'s [attribute] against [base], or null when absent or unresolvable. */
private fun resolveAttr(element: Element, attribute: String, base: String): String? =
    element.attr(attribute).trim().takeIf { it.isNotEmpty() }?.let { UrlResolver.resolve(base, it) }

/**
 * Drops leading and trailing whitespace across the whole run, the way a block element's own
 * leading and trailing text nodes collapse away in a browser.
 */
private fun ArticleInline.trimEdges(): ArticleInline {
    val trimmed = spans.toMutableList()
    while (trimmed.isNotEmpty() && trimmed.first().text.isBlank()) trimmed.removeAt(0)
    while (trimmed.isNotEmpty() && trimmed.last().text.isBlank()) trimmed.removeAt(trimmed.lastIndex)
    if (trimmed.isEmpty()) return ArticleInline(emptyList())
    trimmed[0] = trimmed[0].copy(text = trimmed[0].text.trimStart())
    trimmed[trimmed.lastIndex] = trimmed[trimmed.lastIndex].let { it.copy(text = it.text.trimEnd()) }
    return ArticleInline(trimmed.filter { it.text.isNotEmpty() })
}

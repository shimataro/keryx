package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/** Where an inline run sits relative to the baseline — `<sub>`/`<sup>`. */
internal enum class InlineBaseline { Normal, Sub, Super }

/**
 * A run of text sharing one set of inline decorations. [link], when non-null, is an
 * already-resolved absolute URL. [color]/[background] come from the element's own `style=""`
 * attribute (see [InlineCss]) — see [InlineStyle]'s KDoc for why these are trusted at face value
 * even against the app's own theme.
 */
internal data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val underline: Boolean = false,
    val highlight: Boolean = false,
    val sizeScale: Float = 1f,
    val baseline: InlineBaseline = InlineBaseline.Normal,
    val color: Color? = null,
    val background: Color? = null,
    val link: String? = null,
)

/** One block's worth of inline content. */
internal data class ArticleInline(val spans: List<InlineSpan>) {
    val isBlank: Boolean get() = spans.all { it.text.isBlank() }
}

/** A single `<tr>`, keeping `<th>` cells distinguishable from `<td>` ones for UA-default bolding/centering. */
internal data class TableRow(val cells: List<ArticleInline>, val isHeader: Boolean)

/** A block-level piece of an article, in the order it appears. */
internal sealed interface ArticleBlock {
    data class Paragraph(val text: ArticleInline, val align: TextAlign? = null, val muted: Boolean = false) : ArticleBlock
    data class Caption(val text: ArticleInline, val align: TextAlign? = null) : ArticleBlock
    data class Heading(val level: Int, val text: ArticleInline, val align: TextAlign? = null) : ArticleBlock
    data class Bullets(val ordered: Boolean, val start: Int = 1, val depth: Int = 0, val items: List<List<ArticleBlock>>) : ArticleBlock
    data class Quote(val children: List<ArticleBlock>) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Picture(val src: String, val alt: String?) : ArticleBlock
    data class Figure(val children: List<ArticleBlock>) : ArticleBlock
    data class Table(val rows: List<TableRow>) : ArticleBlock
    data class Definition(val term: ArticleInline, val description: ArticleInline) : ArticleBlock
    data class Embed(val url: String) : ArticleBlock
    data object Rule : ArticleBlock
}

/**
 * An article document reduced to what the Compose fallback reader draws.
 *
 * @property centeredNotice Set only for the "no article selected" document, which the reader
 * centers on its own instead of laying out as a body — every other property is then empty.
 */
internal data class ArticleContent(
    val title: String? = null,
    val titleUrl: String? = null,
    val meta: String? = null,
    val blocks: List<ArticleBlock> = emptyList(),
    val centeredNotice: String? = null,
)

/**
 * Decorations in force at a point in the inline tree, inherited by nested elements. [color]/
 * [background]/[sizeScale]/[bold]/[underline] can be set either by a tag (`<b>`, `<mark>`, …) or
 * by that element's own `style=""` attribute (see [InlineCss]) — the two are merged, with `style`
 * taking precedence since it is the more specific of the two (matching CSS's own cascade: an
 * inline `style` attribute always wins over a UA-default tag mapping).
 *
 * A feed's own inline color/background is honored even against the app's dark theme (e.g.
 * `color:#000` stays black text on a dark background) — this matches what the WebView reader
 * itself does: its `!important` rules only cover the reader's *chrome* (`.article-title`,
 * `.article-meta`, …), never a generic `<span style="color:...">` inside the body, so an inline
 * style there already beats the reader's own theme colors in the real WebView too. Reproducing
 * that here is a deliberate fidelity choice, not an oversight.
 */
internal data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val underline: Boolean = false,
    val highlight: Boolean = false,
    val sizeScale: Float = 1f,
    val baseline: InlineBaseline = InlineBaseline.Normal,
    val color: Color? = null,
    val background: Color? = null,
    val link: String? = null,
)

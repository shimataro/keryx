package works.merc.keryx.app.ui.article

/**
 * A run of text sharing one set of inline decorations. [link], when non-null, is an
 * already-resolved absolute URL.
 */
internal data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

/** One block's worth of inline content. */
internal data class ArticleInline(val spans: List<InlineSpan>) {
    val isBlank: Boolean get() = spans.all { it.text.isBlank() }
}

/** A block-level piece of an article, in the order it appears. */
internal sealed interface ArticleBlock {
    data class Paragraph(val text: ArticleInline) : ArticleBlock
    data class Caption(val text: ArticleInline) : ArticleBlock
    data class Heading(val level: Int, val text: ArticleInline) : ArticleBlock
    data class Bullets(val ordered: Boolean, val items: List<List<ArticleBlock>>) : ArticleBlock
    data class Quote(val children: List<ArticleBlock>) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Picture(val src: String, val alt: String?) : ArticleBlock
    data class Table(val rows: List<List<ArticleInline>>) : ArticleBlock
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

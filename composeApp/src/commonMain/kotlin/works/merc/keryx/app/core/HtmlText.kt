package works.merc.keryx.app.core

import com.fleeksoft.ksoup.Ksoup

/** HTML → plain-text extraction for search indexing. */
object HtmlText {
    /**
     * Strips HTML markup, returning only the visible text (tag names and
     * attributes are dropped). Whitespace is normalized. Plain (tag-free)
     * input is returned essentially unchanged (only normalized). Used to keep
     * `articles.search_text` free of HTML so the FTS index does not match tag
     * names/attributes; the raw HTML stays in `content`/`summary` for rendering.
     */
    fun toPlainText(html: String): String = Ksoup.parse(html).text()
}

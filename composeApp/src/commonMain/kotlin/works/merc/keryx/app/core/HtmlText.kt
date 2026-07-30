package works.merc.keryx.app.core

import com.fleeksoft.ksoup.Ksoup

/** HTML → plain-text extraction for search indexing. */
object HtmlText {
    /** The visible text of [html] with tags/attributes removed and whitespace normalized. */
    fun toPlainText(html: String): String = Ksoup.parse(html).text()
}

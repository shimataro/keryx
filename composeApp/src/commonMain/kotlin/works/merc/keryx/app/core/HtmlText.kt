package works.merc.keryx.app.core

import com.fleeksoft.ksoup.Ksoup

/** HTML → plain-text extraction for search indexing. */
object HtmlText {
    /**
     * Converts HTML content to visible plain text.
     *
     * @param html The HTML content to convert.
     * @return The visible text with tags and attributes removed and whitespace normalized.
     */
    fun toPlainText(html: String): String = Ksoup.parse(html).text()
}

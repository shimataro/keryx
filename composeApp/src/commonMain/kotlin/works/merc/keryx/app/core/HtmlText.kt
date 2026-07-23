package works.merc.keryx.app.core

import com.fleeksoft.ksoup.Ksoup

/** HTML → plain-text extraction for search indexing. */
object HtmlText {
    /**
 * Converts HTML content to plain text for search indexing.
 *
 * @return The visible text with HTML tags and attributes removed and whitespace normalized.
 */
    fun toPlainText(html: String): String = Ksoup.parse(html).text()
}

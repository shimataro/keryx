package works.merc.keryx.app.data.opml

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/** Imports/exports OPML 2.0 subscription lists. */
object OpmlCodec {

    /** A feed to export. */
    data class ExportFeed(val title: String, val xmlUrl: String, val htmlUrl: String?)

    /** An imported subscription. */
    data class ImportedFeed(val xmlUrl: String, val title: String?)

    /** Collects every `<outline>` that carries an `xmlUrl` (nested folders included). */
    fun import(xml: String): List<ImportedFeed> = runCatching {
        val doc = Ksoup.parse(html = xml, parser = Parser.xmlParser())
        val seen = mutableSetOf<String>()
        doc.getElementsByTag("outline").mapNotNull { outline ->
            val url = outline.attrCI("xmlUrl")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            val title = (outline.attrCI("title") ?: outline.attrCI("text"))?.trim()?.takeIf { it.isNotEmpty() }
            ImportedFeed(url, title)
        }
    }.getOrDefault(emptyList())

    fun export(feeds: List<ExportFeed>, title: String = "Keryx Subscriptions"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n    <title>").append(escape(title)).append("</title>\n  </head>\n")
        append("  <body>\n")
        for (f in feeds) {
            append("    <outline type=\"rss\"")
            append(" text=\"").append(escape(f.title)).append('"')
            append(" title=\"").append(escape(f.title)).append('"')
            append(" xmlUrl=\"").append(escape(f.xmlUrl)).append('"')
            if (!f.htmlUrl.isNullOrBlank()) {
                append(" htmlUrl=\"").append(escape(f.htmlUrl)).append('"')
            }
            append("/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private fun Element.attrCI(name: String): String? {
        for (attr in attributes()) {
            if (attr.key.equals(name, ignoreCase = true)) return attr.value
        }
        return null
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

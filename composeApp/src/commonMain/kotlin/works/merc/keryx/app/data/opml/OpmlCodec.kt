package works.merc.keryx.app.data.opml

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/** Imports/exports OPML 2.0 subscription lists. */
object OpmlCodec {

    /**
     * A feed to export. [tags] are written to the OPML 2.0 `category` attribute as a comma-separated
     * list — a tag name that itself contains a comma therefore does not round-trip losslessly, which
     * is the same informal convention other readers use.
     */
    data class ExportFeed(
        val title: String,
        val xmlUrl: String,
        val htmlUrl: String?,
        val tags: List<String> = emptyList(),
    )

    /** An imported subscription. [folderName] is the enclosing folder outline's name, if any. */
    data class ImportedFeed(
        val xmlUrl: String,
        val title: String?,
        val folderName: String? = null,
        val tags: List<String> = emptyList(),
    )

    /**
     * Imports feeds from an OPML document, preserving folder names and category tags.
     *
     * Feeds are deduplicated by XML URL, keeping the first occurrence. Nested folders replace
     * their parent folder for descendant feeds.
     *
     * @param xml The OPML document to import.
     * @return The imported feeds, or an empty list if parsing fails.
     */
    fun import(xml: String): List<ImportedFeed> = runCatching {
        val doc = Ksoup.parse(html = xml, parser = Parser.xmlParser())
        val seen = mutableSetOf<String>()
        val imported = mutableListOf<ImportedFeed>()

        /**
         * Traverses descendant outlines, importing unique feeds and preserving their folder context.
         *
         * @param element The element whose descendants are traversed.
         * @param folderName The current folder name assigned to discovered feeds.
         */
        fun walk(element: Element, folderName: String?) {
            for (child in element.children()) {
                if (!child.tagName().equals("outline", ignoreCase = true)) {
                    // A structural wrapper (<opml>, <body>, …): transparent, keeps the folder context.
                    walk(child, folderName)
                    continue
                }
                val name = child.attrCI("title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: child.attrCI("text")?.trim()?.takeIf { it.isNotEmpty() }
                val url = child.attrCI("xmlUrl")?.trim()?.takeIf { it.isNotEmpty() }
                if (url == null) {
                    walk(child, name ?: folderName)
                    continue
                }
                if (seen.add(url)) {
                    imported += ImportedFeed(url, name, folderName, parseCategories(child.attrCI("category")))
                }
                // A feed outline may still nest further feeds; they stay in the same folder.
                walk(child, folderName)
            }
        }

        walk(doc, null)
        imported
    }.getOrDefault(emptyList())

    /**
     * Serializes [groups] — each `(folderName, feeds)`, in the order they should appear — to an OPML
     * document. A non-null folder name wraps its feeds in a folder `<outline>`; a null one emits
     * them at the top level. Grouping and ordering are the caller's concern.
     */
    fun export(groups: List<Pair<String?, List<ExportFeed>>>, title: String = "Keryx Subscriptions"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n    <title>").append(escape(title)).append("</title>\n  </head>\n")
        append("  <body>\n")
        for ((folderName, feeds) in groups) {
            if (folderName == null) {
                for (f in feeds) appendFeedOutline(f, indent = "    ")
            } else {
                append("    <outline text=\"").append(escape(folderName)).append("\">\n")
                for (f in feeds) appendFeedOutline(f, indent = "      ")
                append("    </outline>\n")
            }
        }
        append("  </body>\n</opml>\n")
    }

    private fun StringBuilder.appendFeedOutline(feed: ExportFeed, indent: String) {
        append(indent).append("<outline type=\"rss\"")
        append(" text=\"").append(escape(feed.title)).append('"')
        append(" title=\"").append(escape(feed.title)).append('"')
        append(" xmlUrl=\"").append(escape(feed.xmlUrl)).append('"')
        if (!feed.htmlUrl.isNullOrBlank()) {
            append(" htmlUrl=\"").append(escape(feed.htmlUrl)).append('"')
        }
        if (feed.tags.isNotEmpty()) {
            append(" category=\"").append(escape(feed.tags.joinToString(","))).append('"')
        }
        append("/>\n")
    }

    /** Splits a `category` attribute on `,`, trimming and dropping empty entries. */
    private fun parseCategories(category: String?): List<String> =
        category?.split(',')?.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }.orEmpty()

    /**
     * Retrieves an attribute value using a case-insensitive attribute name.
     *
     * @param name The attribute name to find.
     * @return The first matching attribute value, or `null` if no matching attribute exists.
     */
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

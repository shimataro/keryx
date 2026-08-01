package works.merc.keryx.app.data.remote

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import works.merc.keryx.app.core.DateTimeParser

/**
 * Parses RSS 2.0 (incl. RSS 1.0 / RDF) and Atom 1.0 / 0.3 into [FetchedFeed]. Uses a
 * lenient DOM (ksoup XML parser) and direct-child lookups so channel-level
 * fields never accidentally pick up an item's title/link.
 */
object FeedParser {

    /** Sniffs the format and parses, or returns null if it isn't a known feed. */
    fun detectAndParse(body: String): FetchedFeed? {
        val head = body.trimStart('﻿', ' ', '\n', '\r', '\t').take(512)
        return when {
            head.contains("<rss", ignoreCase = true) || head.contains("<rdf:RDF", ignoreCase = true) ->
                parseRss(body)
            head.contains("<feed", ignoreCase = true) -> parseAtom(body)
            else -> null
        }
    }

    fun parseRss(body: String): FetchedFeed {
        val doc = Ksoup.parse(html = body, parser = Parser.xmlParser())
        val channel = doc.getElementsByTag("channel").firstOrNull()
        val articles = doc.getElementsByTag("item").map { item ->
            val link = item.childText("link")
            ParsedArticle(
                guid = item.childText("guid") ?: link ?: "",
                url = link,
                title = item.childText("title"),
                summary = item.childText("description"),
                content = item.childText("content:encoded"),
                author = item.childText("dc:creator", "author"),
                publishedAtMillis = DateTimeParser.parseToEpochMillis(item.childText("pubDate", "dc:date")),
                thumbnailUrl = item.imageUrl(),
            )
        }
        return FetchedFeed(
            title = channel?.childText("title"),
            description = channel?.childText("description"),
            siteUrl = channel?.childText("link"),
            articles = articles,
        )
    }

    fun parseAtom(body: String): FetchedFeed {
        val doc = Ksoup.parse(html = body, parser = Parser.xmlParser())
        val feed = doc.getElementsByTag("feed").firstOrNull()
        val articles = doc.getElementsByTag("entry").map { entry ->
            val url = atomLink(entry)
            val id = entry.childText("id")
            ParsedArticle(
                guid = id ?: url ?: "",
                url = url,
                title = entry.childText("title"),
                summary = entry.childText("summary"),
                content = entry.childText("content") ?: entry.childText("summary"),
                author = entry.directChild("author")?.childText("name"),
                // Publication date first, falling back to the modification date. Atom 1.0 uses
                // published/updated; Atom 0.3 (still emitted by e.g. livedoor Blog) uses issued/modified.
                publishedAtMillis = DateTimeParser.parseToEpochMillis(
                    entry.childText("published", "issued", "updated", "modified"),
                ),
            )
        }
        return FetchedFeed(
            title = feed?.childText("title"),
            description = feed?.childText("subtitle", "tagline"),
            siteUrl = atomLink(feed),
            articles = articles,
        )
    }

    /** Picks the best Atom `<link>` href (rel=alternate, or the first with an href). */
    private fun atomLink(scope: Element?): String? {
        if (scope == null) return null
        val links = scope.children().filter { it.tagName().equals("link", ignoreCase = true) }
        val preferred = links.firstOrNull {
            val rel = it.attr("rel")
            (rel == "alternate" || rel.isBlank()) && it.attr("href").isNotBlank()
        }
        return (preferred ?: links.firstOrNull { it.attr("href").isNotBlank() })
            ?.attr("href")?.ifBlank { null }
    }

    private fun Element.imageUrl(): String? {
        directChild("media:content")?.attr("url")?.ifBlank { null }?.let { return it }
        directChild("media:thumbnail")?.attr("url")?.ifBlank { null }?.let { return it }
        children()
            .firstOrNull { it.tagName().equals("enclosure", ignoreCase = true) && it.attr("type").startsWith("image") }
            ?.attr("url")?.ifBlank { null }?.let { return it }
        return null
    }

    /** Finds the first direct child matching [tags], in argument order (earlier tags win). */
    private fun Element.directChild(vararg tags: String): Element? =
        tags.firstNotNullOfOrNull { tag ->
            children().firstOrNull { it.tagName().equals(tag, ignoreCase = true) }
        }

    private fun Element.childText(vararg tags: String): String? {
        val el = directChild(*tags) ?: return null
        return el.text().trim().ifEmpty { null }
    }
}

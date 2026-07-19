package works.merc.keryx.app.data.remote

import com.fleeksoft.ksoup.Ksoup
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.DiscoveredFeedType

/** Finds `<link rel="alternate" type="application/rss+xml|atom+xml">` feeds in an HTML page. */
object FeedDiscovery {
    private val typesByMime = mapOf(
        "application/rss+xml" to DiscoveredFeedType.Rss,
        "application/atom+xml" to DiscoveredFeedType.Atom,
    )

    fun discover(html: String, baseUrl: String): List<DiscoveredFeedLink> = runCatching {
        val doc = Ksoup.parse(html = html, baseUri = baseUrl)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<DiscoveredFeedLink>()
        for (link in doc.select("link[rel=alternate]")) {
            val type = typesByMime[link.attr("type").lowercase()] ?: continue
            val href = link.attr("href")
            if (href.isBlank()) continue
            val resolved = UrlResolver.resolve(baseUrl, href) ?: continue
            if (!seen.add(resolved)) continue
            out.add(DiscoveredFeedLink(url = resolved, title = link.attr("title").ifBlank { null }, type = type))
        }
        out
    }.getOrDefault(emptyList())
}

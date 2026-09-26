package works.merc.keryx.app.data.remote

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText

/**
 * Best-effort favicon URL resolution. Only the URL is stored in the DB; the
 * image bytes are fetched at display time by Coil. Every failure path returns
 * null (the caller then stores a sentinel to avoid re-checking).
 */
class FaviconResolver(private val client: HttpClient) {

    suspend fun resolve(siteUrl: String?, feedUrl: String): String? {
        val base = siteUrl?.takeIf { it.isNotBlank() } ?: UrlResolver.origin(feedUrl) ?: return null
        val origin = UrlResolver.origin(base) ?: return null

        // 1. Look for declared <link rel=icon> candidates in the site's HTML,
        // preferring SVG first, then the largest declared bitmap resolution.
        val candidates = runCatching {
            val html = client.get(base).bodyAsText()
            val doc = Ksoup.parse(html = html, baseUri = base)
            doc.select("link[rel~=(?i)icon]")
                // "mask-icon" is Safari's monochrome pinned-tab silhouette, not a
                // real site logo - exclude it even though it matches the regex above.
                .filter { it.attr("rel").trim().lowercase() != "mask-icon" }
                .mapNotNull { link ->
                    val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = UrlResolver.resolve(base, href) ?: return@mapNotNull null
                    val bareUrl = url.substringBefore('?').substringBefore('#')
                    val sizes = link.attr("sizes")
                    val isSvg = link.attr("type").contains("svg", ignoreCase = true) ||
                        bareUrl.endsWith(".svg", ignoreCase = true) ||
                        sizes.equals("any", ignoreCase = true)
                    val resolution = sizes.lowercase().split(" ")
                        .mapNotNull { token -> token.substringBefore("x").toIntOrNull() }
                        .maxOrNull() ?: 0
                    FaviconCandidate(url, isSvg, resolution)
                }
                .sortedWith(compareByDescending<FaviconCandidate> { it.isSvg }.thenByDescending { it.resolution })
        }.getOrDefault(emptyList())

        for (candidate in candidates) {
            if (isReachable(candidate.url)) return candidate.url
        }

        // 2. Fall back to the conventional /favicon.ico.
        val fallback = "$origin/favicon.ico"
        if (isReachable(fallback)) return fallback

        return null
    }

    suspend fun isReachable(url: String): Boolean = runCatching {
        client.head(url).status.value in 200..299
    }.getOrDefault(false)

    private data class FaviconCandidate(val url: String, val isSvg: Boolean, val resolution: Int)
}

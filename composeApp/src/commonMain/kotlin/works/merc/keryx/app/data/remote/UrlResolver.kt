package works.merc.keryx.app.data.remote

import io.ktor.http.Url

/** Resolves relative URLs against a base and derives an origin. */
object UrlResolver {
    private val absoluteScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /** Resolves [ref] against [base]. Returns null if [base] can't be parsed. */
    fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        if (absoluteScheme.containsMatchIn(r)) return r

        val origin = origin(base) ?: return null
        val b = runCatching { Url(base) }.getOrNull() ?: return null
        return when {
            r.startsWith("//") -> "${b.protocol.name}:$r"
            r.startsWith("/") -> "$origin$r"
            else -> {
                val dir = b.encodedPath.substringBeforeLast('/', "")
                "$origin$dir/$r"
            }
        }
    }

    /** True if [url] (trimmed) already has an explicit scheme (e.g. "http://", "https://"). */
    fun hasScheme(url: String): Boolean = absoluteScheme.containsMatchIn(url.trim())

    /** Prepends "https://" if [url] (trimmed) has no scheme. */
    fun withDefaultScheme(url: String): String {
        val trimmed = url.trim()
        return if (hasScheme(trimmed)) trimmed else "https://$trimmed"
    }

    /** Returns the scheme://host[:port] origin of [url], or null. */
    fun origin(url: String): String? {
        val u = runCatching { Url(url) }.getOrNull() ?: return null
        if (u.host.isBlank()) return null
        val scheme = u.protocol.name
        val port = u.specifiedPort
        val portPart = if (port > 0 && port != u.protocol.defaultPort) ":$port" else ""
        return "$scheme://${u.host}$portPart"
    }
}

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
        if (r.startsWith("//")) return "${b.protocol.name}:$r"

        val fragmentIdx = r.indexOf('#')
        val withoutFragment = if (fragmentIdx >= 0) r.substring(0, fragmentIdx) else r
        val fragment = if (fragmentIdx >= 0) r.substring(fragmentIdx) else ""
        val queryIdx = withoutFragment.indexOf('?')
        val rPath = if (queryIdx >= 0) withoutFragment.substring(0, queryIdx) else withoutFragment
        val rQuery = if (queryIdx >= 0) withoutFragment.substring(queryIdx) else null

        val (targetPath, targetQuery) = when {
            rPath.isEmpty() -> b.encodedPath to (rQuery ?: b.encodedQuery.let { if (it.isEmpty()) "" else "?$it" })
            rPath.startsWith("/") -> removeDotSegments(rPath) to (rQuery ?: "")
            else -> {
                val dir = b.encodedPath.substringBeforeLast('/', "")
                removeDotSegments("$dir/$rPath") to (rQuery ?: "")
            }
        }
        return "$origin$targetPath$targetQuery$fragment"
    }

    /**
     * RFC 3986 §5.2.4 dot-segment removal, applied to an already-merged absolute [path]. Drops
     * `.` segments and pops the preceding segment on `..`, without popping past the leading
     * root segment (an excess `..` at the root is simply discarded, matching browser behavior).
     */
    private fun removeDotSegments(path: String): String {
        val requiresTrailingSlash = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
        val output = mutableListOf<String>()
        for (segment in path.split("/")) {
            when (segment) {
                "." -> Unit
                ".." -> if (output.size > 1 || (output.size == 1 && output[0].isNotEmpty())) output.removeAt(output.lastIndex)
                else -> output.add(segment)
            }
        }
        val normalized = output.joinToString("/")
        return if (requiresTrailingSlash && !normalized.endsWith("/")) "$normalized/" else normalized
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
        // Ktor's Url("") does not throw — it defaults to a "http://localhost" origin rather than
        // an unparseable one, which would otherwise make a blank base silently resolve a relative
        // ref against a made-up host instead of being rejected as unresolvable.
        if (url.isBlank()) return null
        val u = runCatching { Url(url) }.getOrNull() ?: return null
        if (u.host.isBlank()) return null
        val scheme = u.protocol.name
        val port = u.specifiedPort
        val portPart = if (port > 0 && port != u.protocol.defaultPort) ":$port" else ""
        return "$scheme://${u.host}$portPart"
    }
}

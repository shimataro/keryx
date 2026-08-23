package works.merc.keryx.app.domain

import java.net.URI
import java.net.URLDecoder

/**
 * Parses a custom-scheme redirect URI into structured OAuth callback parameters.
 * Extracts the query string from the URI and decodes key/value pairs.
 */
fun parseOAuthUri(uriString: String): OAuthCallbackParams {
    val uri = URI(uriString)
    val queryMap = parseQuery(uri.rawQuery)
    return OAuthCallbackParams(
        code = queryMap["code"],
        state = queryMap["state"],
        error = queryMap["error"],
    )
}

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@mapNotNull null
        val key = pair.substring(0, idx)
        val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        key to value
    }.toMap()
}

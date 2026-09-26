package works.merc.keryx.app.domain

import io.ktor.http.decodeURLQueryComponent

/**
 * Parses a custom-scheme redirect URI into structured OAuth callback parameters.
 * Extracts the query string from the URI and decodes key/value pairs.
 */
fun parseOAuthUri(uriString: String): OAuthCallbackParams {
    val queryMap = parseQuery(rawQuery(uriString))
    return OAuthCallbackParams(
        code = queryMap["code"],
        state = queryMap["state"],
        error = queryMap["error"],
        errorDescription = queryMap["error_description"],
    )
}

/** The still-encoded query: everything between the first `?` and a trailing `#fragment`. */
private fun rawQuery(uriString: String): String? {
    val start = uriString.indexOf('?')
    if (start < 0) return null
    val end = uriString.indexOf('#', startIndex = start + 1).takeIf { it >= 0 } ?: uriString.length
    return uriString.substring(start + 1, end)
}

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@mapNotNull null
        val key = pair.substring(0, idx)
        // application/x-www-form-urlencoded: `+` is a space, `%2B` a literal plus.
        val value = pair.substring(idx + 1).decodeURLQueryComponent(plusIsSpace = true)
        key to value
    }.toMap()
}

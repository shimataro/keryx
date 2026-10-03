package works.merc.keryx.app.presentation.home

private val URL_SCHEMES = listOf("https://", "http://")

/** Characters that end a URL candidate outright: they never appear unencoded inside one. */
private fun Char.endsUrl(): Boolean = isWhitespace() || this == '<' || this == '>' || this == '"' || this == '`'

/** Punctuation that usually belongs to the surrounding sentence rather than to the URL itself. */
private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\"。、，．！？）」』】〉》”’"

/**
 * Extracts the feed URL another app shared to Keryx (Android's `ACTION_SEND` `EXTRA_TEXT`) — the
 * first `http://` or `https://` URL in [text], or `null` when there is none.
 *
 * The text is untrusted input from any app, and is often a URL wrapped in prose ("Title —
 * https://example.com/feed"), so only an http(s) URL is ever returned: every other scheme is ignored,
 * and so is everything around the URL. The candidate runs from its scheme to the next whitespace (or
 * `<`, `>`, `"`, `` ` ``), then sentence punctuation stuck to its end is dropped — except a closing
 * `)` / `]` that balances an opening one inside the URL, as in a Wikipedia link. The scheme is
 * matched case-insensitively and returned lowercased. A candidate with no host after the scheme is
 * skipped in favor of the next one.
 *
 * The result only pre-fills the Add feed dialog; nothing is fetched or subscribed until the user
 * confirms it there.
 */
fun extractSharedFeedUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    var searchFrom = 0
    while (true) {
        val start = URL_SCHEMES
            .map { text.indexOf(it, searchFrom, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        var end = start
        while (end < text.length && !text[end].endsUrl()) end++
        val candidate = trimTrailingPunctuation(text.substring(start, end))
        val schemeLength = candidate.indexOf("://") + 3
        val host = candidate.substring(schemeLength).takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isNotEmpty()) {
            return candidate.substring(0, schemeLength).lowercase() + candidate.substring(schemeLength)
        }
        searchFrom = start + 1
    }
}

private fun trimTrailingPunctuation(candidate: String): String {
    var url = candidate
    while (url.isNotEmpty() && url.last() in TRAILING_PUNCTUATION) {
        val last = url.last()
        val opening = when (last) {
            ')' -> '('
            ']' -> '['
            else -> null
        }
        // Keep a closing bracket the URL itself opened (https://en.wikipedia.org/wiki/Foo_(bar)).
        if (opening != null && url.count { it == opening } >= url.count { it == last }) break
        url = url.dropLast(1)
    }
    return url
}

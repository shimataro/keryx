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
 * The text is scanned once, front to back, in time linear in its length — it arrives from any app and is
 * parsed on the UI thread, so a huge payload of repeated schemes or trailing brackets must not stall it.
 *
 * The result only pre-fills the Add feed dialog; nothing is fetched or subscribed until the user
 * confirms it there.
 */
fun extractSharedFeedUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    // End of the whitespace-delimited run holding the current scheme, cached so every scheme
    // occurrence inside one run reuses it instead of rescanning to the run's end.
    var runEnd = -1
    for (start in text.indices) {
        val scheme = URL_SCHEMES.firstOrNull { text.regionMatches(start, it, 0, it.length, ignoreCase = true) } ?: continue
        if (start >= runEnd) {
            runEnd = start
            while (runEnd < text.length && !text[runEnd].endsUrl()) runEnd++
        }
        val hostStart = start + scheme.length
        // No host: the run ends here, or the next character already ends the authority.
        if (hostStart >= runEnd || text[hostStart] in "/?#") continue
        // A failed trim means everything after the scheme was punctuation, so no later scheme can
        // start in this run: the trim's cost is paid once per run, not once per scheme.
        val end = trimmedEnd(text, hostStart, runEnd)
        if (end > hostStart) return scheme + text.substring(hostStart, end)
    }
    return null
}

/**
 * The end index of the URL whose host starts at [from] and whose raw run ends at [runEnd], once
 * trailing sentence punctuation is dropped. A closing `)` / `]` stays while the URL itself opened
 * as many of its kind (https://en.wikipedia.org/wiki/Foo_(bar)). The brackets are counted once and
 * the end only moves backwards, so nothing is copied or rescanned per dropped character.
 */
private fun trimmedEnd(text: String, from: Int, runEnd: Int): Int {
    var openParens = 0
    var closeParens = 0
    var openSquares = 0
    var closeSquares = 0
    for (i in from until runEnd) {
        when (text[i]) {
            '(' -> openParens++
            ')' -> closeParens++
            '[' -> openSquares++
            ']' -> closeSquares++
        }
    }
    var end = runEnd
    while (end > from && text[end - 1] in TRAILING_PUNCTUATION) {
        when (text[end - 1]) {
            ')' -> if (openParens >= closeParens) break else closeParens--
            ']' -> if (openSquares >= closeSquares) break else closeSquares--
        }
        end--
    }
    return end
}

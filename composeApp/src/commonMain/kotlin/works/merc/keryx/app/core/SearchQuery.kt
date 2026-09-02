package works.merc.keryx.app.core

private val WHITESPACE = Regex("\\s+")

/**
 * Splits the raw search string on whitespace and returns only terms at least
 * [SEARCH_MIN_TERM_LENGTH] characters long.
 *
 * Each space-separated term is ANDed together in the search. Terms below [SEARCH_MIN_TERM_LENGTH]
 * are excluded entirely; terms from [SEARCH_MIN_TERM_LENGTH] up to (but not including)
 * [TRIGRAM_MIN_TERM_LENGTH] can't be matched by the trigram index and are searched via a `LIKE`
 * fallback instead (see `FtsSearch`). Returns an empty list (= no valid search terms) if the input
 * is empty/blank or all terms are too short.
 */
fun searchTerms(raw: String): List<String> =
    raw.trim().split(WHITESPACE).filter { it.length >= SEARCH_MIN_TERM_LENGTH }

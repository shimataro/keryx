package works.merc.keryx.app.core

private val WHITESPACE = Regex("\\s+")

/**
 * Splits the raw search string on whitespace and returns only terms at least
 * [SEARCH_MIN_TERM_LENGTH] characters long.
 *
 * Each space-separated term is ANDed together in the search. The trigram tokenizer can't match
 * terms under 3 characters, so terms of 2 characters or fewer are excluded from the AND
 * condition. Returns an empty list (= no valid search terms) if the input is empty/blank or all
 * terms are too short.
 */
fun searchTerms(raw: String): List<String> =
    raw.trim().split(WHITESPACE).filter { it.length >= SEARCH_MIN_TERM_LENGTH }

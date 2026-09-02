package works.merc.keryx.app.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests for [escapeLikePattern] and [markTerms]. The driver-backed MATCH/LIKE
 * behavior itself is covered by [FtsSearchTest] (desktopTest, needs a real SQLite connection).
 */
class FtsSearchQueryHelpersTest {

    @Test
    fun escapeLikePatternEscapesWildcardsAndTheEscapeCharItself() {
        assertEquals("100\\%", escapeLikePattern("100%"))
        assertEquals("a\\_b", escapeLikePattern("a_b"))
        assertEquals("a\\\\b", escapeLikePattern("a\\b"))
        assertEquals("plain", escapeLikePattern("plain"))
    }

    @Test
    fun escapeLikePatternEscapesBackslashBeforeWildcards() {
        // Escaping must happen backslash-first: a user-typed backslash immediately followed by a
        // percent must not be read as an already-escaped percent. The two-character raw input
        // (backslash, percent) becomes an escaped backslash (two backslashes) plus an escaped
        // percent (its own leading backslash), i.e. three backslashes then a percent — verified
        // against SQLite's own `LIKE ... ESCAPE '\'` semantics, not just this function's logic.
        assertEquals("a\\\\\\%b", escapeLikePattern("a\\%b"))
    }

    @Test
    fun markTermsReturnsOriginalTextWhenNoTermsGiven() {
        assertEquals("Hello World", markTerms("Hello World", emptyList()))
    }

    @Test
    fun markTermsReturnsOriginalTextWhenNothingMatches() {
        assertEquals("Hello World", markTerms("Hello World", listOf("xyz")))
    }

    @Test
    fun markTermsWrapsACaseInsensitiveMatch() {
        val marked = markTerms("Hello World", listOf("world"))
        assertEquals("Hello " + FtsSearch.MARK_START + "World" + FtsSearch.MARK_END, marked)
    }

    @Test
    fun markTermsWrapsEveryNonOverlappingOccurrence() {
        val marked = markTerms("cat cat cat", listOf("cat"))
        val s = FtsSearch.MARK_START
        val e = FtsSearch.MARK_END
        assertEquals("${s}cat$e ${s}cat$e ${s}cat$e", marked)
    }

    @Test
    fun markTermsHandlesMultipleDistinctTerms() {
        val marked = markTerms("cats and dogs are friends", listOf("cats", "dogs"))
        val s = FtsSearch.MARK_START
        val e = FtsSearch.MARK_END
        assertEquals("${s}cats$e and ${s}dogs$e are friends", marked)
    }

    @Test
    fun markTermsMergesOverlappingRangesFromDifferentTerms() {
        // "cats" (long term) and "at" (short term) overlap inside the same word. Naive
        // START/END emission at raw match boundaries would close the highlight after "at" and
        // leave the trailing "s" unmarked; the merged range must cover the whole word.
        val marked = markTerms("cats and dogs", listOf("cats", "at"))
        val s = FtsSearch.MARK_START
        val e = FtsSearch.MARK_END
        assertEquals("${s}cats$e and dogs", marked)
    }

    @Test
    fun markTermsMergesOverlappingRangesFromSameTerm() {
        // "aa" occurs at index 0 and index 1 inside "aaa". Advancing by term.length would skip
        // the second occurrence, leaving only the first two characters highlighted; advancing by
        // one discovers both and the merge logic unions them into the full range.
        val marked = markTerms("aaa", listOf("aa"))
        val s = FtsSearch.MARK_START
        val e = FtsSearch.MARK_END
        assertEquals("${s}aaa$e", marked)
    }

    @Test
    fun markTermsStrippedOfMarkersRestoresOriginalText() {
        val text = "Kotlin Multiplatform apps"
        val marked = markTerms(text, listOf("Kotlin", "apps"))
        assertEquals(text, marked.filter { it != FtsSearch.MARK_START && it != FtsSearch.MARK_END })
    }
}

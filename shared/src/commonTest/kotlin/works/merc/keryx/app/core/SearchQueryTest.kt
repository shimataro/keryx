package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueryTest {
    @Test
    fun emptyOrBlankReturnsEmpty() {
        assertEquals(emptyList(), searchTerms(""))
        assertEquals(emptyList(), searchTerms("   "))
    }

    @Test
    fun dropsSubMinimumTermsAndKeepsTheRest() {
        // "f" is 1 character, shorter than SEARCH_MIN_TERM_LENGTH, and is dropped. "de" (2
        // characters) and "abc" (3+) both clear the minimum and are kept — whether a kept term
        // is long enough for the trigram index or falls back to LIKE is FtsSearch's concern, not
        // searchTerms'.
        assertEquals(listOf("abc", "de"), searchTerms("abc de f"))
    }

    @Test
    fun splitsOnRunsOfWhitespace() {
        assertEquals(listOf("foo", "bar"), searchTerms("foo  bar"))
        assertEquals(listOf("foo", "bar"), searchTerms("foo\tbar"))
    }

    @Test
    fun allSubMinimumTermsReturnEmpty() {
        // Both terms are 1 character, below SEARCH_MIN_TERM_LENGTH.
        assertEquals(emptyList(), searchTerms("a b"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(listOf("kotlin"), searchTerms("  kotlin  "))
    }
}

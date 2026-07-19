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
    fun dropsShortTermsAndKeepsLongOnes() {
        // "de" and "f" are shorter than SEARCH_MIN_TERM_LENGTH and are dropped.
        assertEquals(listOf("abc"), searchTerms("abc de f"))
    }

    @Test
    fun splitsOnRunsOfWhitespace() {
        assertEquals(listOf("foo", "bar"), searchTerms("foo  bar"))
        assertEquals(listOf("foo", "bar"), searchTerms("foo\tbar"))
    }

    @Test
    fun allShortTermsReturnEmpty() {
        assertEquals(emptyList(), searchTerms("ab cd"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(listOf("kotlin"), searchTerms("  kotlin  "))
    }
}

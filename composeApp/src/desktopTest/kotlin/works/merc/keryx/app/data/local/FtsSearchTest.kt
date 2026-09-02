package works.merc.keryx.app.data.local

import works.merc.keryx.app.core.SEARCH_FALLBACK_RESULT_LIMIT
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.stampArticleDeleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inserts an article directly (bypassing the repository) for FTS tests. */
private fun KeryxDatabase.insertArticle(id: String, feedId: String, title: String, content: String?, publishedAt: Long? = null) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = publishedAt,
        thumbnail_url = null, is_read = 0, read_at = null, is_starred = 0, starred_at = null,
        cached_at = 0L, search_text = content ?: "", updated_at = 0L, created_at = 0L,
    )
}

class FtsSearchTest {
    @Test
    fun basicMatchReturnsMatchingArticleId() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform mobile and desktop apps")
            db.insertArticle("a2", "f1", "Unrelated", "some other content")
            ftsManagerIndexed(driver)

            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun softDeletedArticleIsExcludedFromSearch() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin one", "shared body")
            db.insertArticle("a2", "f1", "Kotlin two", "shared body")
            driver.stampArticleDeleted("a2", deletedAt = 100)
            ftsManagerIndexed(driver)

            // a2 is still indexed (index isn't touched on soft-delete) but excluded by the query.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun articleDeletedAfterIndexingIsExcludedFromSearch() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin only", "body")
            ftsManagerIndexed(driver)
            assertEquals(listOf("a1"), FtsSearch(driver).search("Kotlin").map { it.id })

            driver.stampArticleDeleted("a1", deletedAt = 100)
            assertEquals(emptyList(), FtsSearch(driver).search("Kotlin").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun literalDoubleQuoteInQueryDoesNotThrow() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Say \"Hello\"", "quoted content here")
            ftsManagerIndexed(driver)

            // Must not throw an FTS5 MATCH syntax error; embedded quotes are escaped. The
            // trigram tokenizer indexes punctuation-adjacent substrings too, so a query
            // containing literal quotes around a word that's actually present still matches.
            val ids = FtsSearch(driver).search("\"Hello\"").map { it.id }
            assertEquals(listOf("a1"), ids)
        } finally {
            driver.close()
        }
    }

    @Test
    fun spaceSeparatedTermsAreAndMatched() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // In a1 the two words are NOT adjacent, so a phrase search for "Cats Dogs" would miss it;
            // an AND search matches because the article contains both words.
            db.insertArticle("a1", "f1", "Cats and Dogs together", "cats live with dogs happily")
            db.insertArticle("a2", "f1", "Cats only", "just cats, nothing else")
            db.insertArticle("a3", "f1", "Dogs only", "just dogs, nothing else")
            ftsManagerIndexed(driver)

            // Only the article containing BOTH terms matches; single-term articles are excluded.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Cats Dogs").map { it.id })
            // Order-independent: it's AND, not a phrase.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Dogs Cats").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun subMinimumTermsAreExcludedFromAnd() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Cats", "just cats, nothing else")
            ftsManagerIndexed(driver)

            // "x" is 1 char (< SEARCH_MIN_TERM_LENGTH) so it's dropped; only "Cats" is required.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Cats x").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun allSubMinimumTermsReturnEmpty() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "a b", "a b content")
            ftsManagerIndexed(driver)

            // Every word is 1 char, too short for even the LIKE fallback: no usable terms, so an
            // empty list (no query is run at all).
            assertEquals(emptyList(), FtsSearch(driver).search("a b").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun specialCharactersInLongTermDoNotThrow() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Something", "content")
            ftsManagerIndexed(driver)

            // A term long enough to survive filtering that also contains FTS5 operator-like
            // characters must reach SQL as a quoted literal without a MATCH syntax error.
            val ids = FtsSearch(driver).search("a-b*").map { it.id }
            assertEquals(emptyList(), ids)
        } finally {
            driver.close()
        }
    }

    @Test
    fun emptyOrBlankQueryReturnsEmptyList() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Something", "content")
            ftsManagerIndexed(driver)

            assertEquals(emptyList(), FtsSearch(driver).search("").map { it.id })
            assertEquals(emptyList(), FtsSearch(driver).search("   ").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun queryWithNoMatchesReturnsEmptyList() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Something", "content")
            ftsManagerIndexed(driver)

            assertEquals(emptyList(), FtsSearch(driver).search("nonexistentterm").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun titleOnlyTermIsFound() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // "search_text" (mapped from content) doesn't contain the term; only the title does.
            db.insertArticle("a1", "f1", "UniqueTitleWord", "body text has nothing special")
            ftsManagerIndexed(driver)

            val ids = FtsSearch(driver).search("UniqueTitleWord").map { it.id }
            assertTrue(ids.contains("a1"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun titleHighlightWrapsMatchedTermInMarkers() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Kotlin Multiplatform", "cross platform apps")
            ftsManagerIndexed(driver)

            val hit = FtsSearch(driver).search("Kotlin").single()
            assertEquals("a1", hit.id)
            // Markers are only inserted — stripping them restores the original title.
            assertEquals("Kotlin Multiplatform", hit.titleMarked.filter { it != FtsSearch.MARK_START && it != FtsSearch.MARK_END })
            // The highlighted span covers the matched term.
            val marked = hit.titleMarked.substringAfter(FtsSearch.MARK_START).substringBefore(FtsSearch.MARK_END)
            assertTrue(marked.lowercase().contains("kotlin"), "titleMarked=${hit.titleMarked}")
        } finally {
            driver.close()
        }
    }

    @Test
    fun bodyOnlyMatchIsReturnedWithNoTitleHighlight() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val body = "lorem ipsum ".repeat(40) + "needle " + "dolor sit ".repeat(40)
            db.insertArticle("a1", "f1", "Title", body)
            ftsManagerIndexed(driver)

            // search_text (body) is still MATCHed, so a body-only hit is returned...
            val hit = FtsSearch(driver).search("needle").single()
            assertEquals("a1", hit.id)
            // ...but the title has no match, so it carries no highlight markers.
            assertEquals("Title", hit.titleMarked)
            assertTrue(FtsSearch.MARK_START !in hit.titleMarked, "titleMarked=${hit.titleMarked}")
        } finally {
            driver.close()
        }
    }

    @Test
    fun twoCharacterTermMatchesTitleViaLikeFallback() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // "AI" is exactly SEARCH_MIN_TERM_LENGTH, below TRIGRAM_MIN_TERM_LENGTH: the trigram
            // index cannot match it at all, so this only succeeds via the LIKE fallback (case C).
            db.insertArticle("a1", "f1", "AI news today", "unrelated body")
            db.insertArticle("a2", "f1", "Unrelated title", "unrelated body")
            ftsManagerIndexed(driver)

            val hit = FtsSearch(driver).search("AI").single()
            assertEquals("a1", hit.id)
            assertEquals(
                "${FtsSearch.MARK_START}AI${FtsSearch.MARK_END} news today",
                hit.titleMarked,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun twoCharacterTermMatchesBodyViaLikeFallback() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Title", "lorem ipsum xq dolor sit")
            ftsManagerIndexed(driver)

            // Case-insensitive, same as the trigram path.
            val hit = FtsSearch(driver).search("XQ").single()
            assertEquals("a1", hit.id)
            // The body isn't projected, so a body-only hit carries no title highlight.
            assertEquals("Title", hit.titleMarked)
        } finally {
            driver.close()
        }
    }

    @Test
    fun longAndShortTermsAreAndedTogether() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Elephant AI news", "body")
            // Has the long term but not the short one: must be excluded by the AND.
            db.insertArticle("a2", "f1", "Elephant only", "no short term anywhere")
            ftsManagerIndexed(driver)

            val hits = FtsSearch(driver).search("Elephant AI")
            assertEquals(listOf("a1"), hits.map { it.id })
            // Both the long (trigram-matched) and short (LIKE-matched) terms are highlighted.
            assertEquals(
                "${FtsSearch.MARK_START}Elephant${FtsSearch.MARK_END} ${FtsSearch.MARK_START}AI${FtsSearch.MARK_END} news",
                hits.single().titleMarked,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun underscoreInShortTermIsNotTreatedAsWildcard() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // "box" contains "o" immediately followed by "x" -- if "_x" were passed through as a
            // raw (unescaped) LIKE wildcard, "_" would match any single character and "box" would
            // wrongly match.
            db.insertArticle("a1", "f1", "box", "unrelated body")
            ftsManagerIndexed(driver)

            assertEquals(emptyList(), FtsSearch(driver).search("_x").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun percentInShortTermIsNotTreatedAsWildcard() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            // "50 percent off" contains "5" but never the literal substring "5%" -- if "%" were
            // passed through as a raw wildcard it would match any text after the "5".
            db.insertArticle("a1", "f1", "50 percent off", "unrelated body")
            ftsManagerIndexed(driver)

            assertEquals(emptyList(), FtsSearch(driver).search("5%").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun softDeletedArticleIsExcludedFromLikeFallback() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "AI one", "body")
            db.insertArticle("a2", "f1", "AI two", "body")
            driver.stampArticleDeleted("a2", deletedAt = 100)
            ftsManagerIndexed(driver)

            assertEquals(listOf("a1"), FtsSearch(driver).search("AI").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun likeFallbackOrdersResultsByPublishedAtDescending() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("older", "f1", "AI oldest", "body", publishedAt = 100L)
            db.insertArticle("newest", "f1", "AI newest", "body", publishedAt = 300L)
            db.insertArticle("middle", "f1", "AI middle", "body", publishedAt = 200L)
            ftsManagerIndexed(driver)

            assertEquals(listOf("newest", "middle", "older"), FtsSearch(driver).search("AI").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun likeFallbackResultsAreCappedAtLimit() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val total = SEARCH_FALLBACK_RESULT_LIMIT + 5
            for (i in 1..total) {
                db.insertArticle("a$i", "f1", "AI article $i", "body", publishedAt = i.toLong())
            }
            ftsManagerIndexed(driver)

            val ids = FtsSearch(driver).search("AI").map { it.id }
            assertEquals(SEARCH_FALLBACK_RESULT_LIMIT, ids.size)
            // The cap keeps the most recent ones (highest published_at), not an arbitrary subset.
            assertTrue("a$total" in ids)
            assertTrue("a1" !in ids)
        } finally {
            driver.close()
        }
    }
}

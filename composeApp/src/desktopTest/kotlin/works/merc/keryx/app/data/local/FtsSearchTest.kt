package works.merc.keryx.app.data.local

import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.ftsManager
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.stampArticleDeleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inserts an article directly (bypassing the repository) for FTS tests. */
private fun KeryxDatabase.insertArticle(id: String, feedId: String, title: String, content: String?) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = title,
        summary = null, content = content, author = null, published_at = null,
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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()
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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

            // Only the article containing BOTH terms matches; single-term articles are excluded.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Cats Dogs").map { it.id })
            // Order-independent: it's AND, not a phrase.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Dogs Cats").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun shortTermsAreExcludedFromAnd() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "Cats", "just cats, nothing else")
            ftsManager(driver).ensureIndexed()

            // "xy" is 2 chars (< SEARCH_MIN_TERM_LENGTH) so it's dropped; only "Cats" is required.
            assertEquals(listOf("a1"), FtsSearch(driver).search("Cats xy").map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun allShortTermsReturnEmpty() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertArticle("a1", "f1", "ab cd", "ab cd content")
            ftsManager(driver).ensureIndexed()

            // Every word is too short: no usable terms, so an empty list (no MATCH is built).
            assertEquals(emptyList(), FtsSearch(driver).search("ab cd").map { it.id })
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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
            ftsManager(driver).ensureIndexed()

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
}

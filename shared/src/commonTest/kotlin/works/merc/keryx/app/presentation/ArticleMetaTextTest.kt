package works.merc.keryx.app.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic cases for `articleMetaText`, shared so both the Compose reader
 * (`ArticleDetailPane.kt`) and the Apple app's reader (`ArticleDetailView.swift`) render the same
 * byline from the same function — split out of the former `ArticleDetailMetaLine` composable
 * (removed when the article detail pane's header moved into the reader WebView's own HTML — see
 * `docs/known-issues.md`). The rendering guarantee that a long author never eats the timestamp is
 * the WebView's `.article-meta` CSS (it wraps instead of clipping), so there is no
 * `longAuthorIsClippedWithoutEatingTheTimestamp` case to keep here.
 */
class ArticleMetaTextTest {

    private val publishedAt = 1_754_000_000_000L

    @Test
    fun articleMetaTextJoinsAuthorAndTimestamp() {
        assertEquals("Alice · ${formatTimestamp(publishedAt)}", articleMetaText("Alice", publishedAt))
    }

    @Test
    fun articleMetaTextOmitsLeadingSeparatorWhenAuthorIsAbsent() {
        val timestamp = formatTimestamp(publishedAt)
        assertEquals(timestamp, articleMetaText(null, publishedAt))
        // A non-null but blank author must not produce a dangling leading separator either.
        assertEquals(timestamp, articleMetaText("", publishedAt))
        assertEquals(timestamp, articleMetaText("   ", publishedAt))
    }
}

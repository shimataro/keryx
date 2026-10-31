package works.merc.keryx.app.ui.home

import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.presentation.article.ArticleHtmlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `readerDocument` builds one pager page's document from a list row plus the (possibly not yet
 * loaded) hydrated article: the not-loaded header-only state, the no-content notice, the title
 * fallbacks, which source wins for url/title, and when the feed favicon is shown.
 */
class ReaderDocumentTest {
    private val theme = ArticleHtmlTheme(
        surface = 0xFFFFFFFF.toInt(),
        onSurface = 0xFF000000.toInt(),
        linkColor = 0xFF0000FF.toInt(),
        mutedColor = 0xFF888888.toInt(),
        fontScale = 1f,
    )

    private val row = ArticleListRow(
        id = "a1",
        feed_id = "f1",
        title = "ROW-TITLE",
        url = "https://row.example/article",
        published_at = 1_700_000_000_000L,
        created_at = 1_700_000_000_000L,
        is_read = 0L,
        is_starred = 0L,
    )

    private fun full(
        title: String = "FULL-TITLE",
        url: String = "https://full.example/article",
        content: String? = "<p>FULL-BODY</p>",
        summary: String? = null,
    ) = ArticleReaderRow(
        id = "a1",
        feed_id = "f1",
        url = url,
        title = title,
        author = null,
        published_at = 1_700_000_000_000L,
        content = content,
        summary = summary,
    )

    private fun build(
        row: ArticleListRow = this.row,
        full: ArticleReaderRow?,
        feedName: String? = null,
        feedFaviconUrl: String? = null,
    ) = readerDocument(
        theme = theme,
        row = row,
        full = full,
        feedName = feedName,
        feedFaviconUrl = feedFaviconUrl,
        noTitleText = "NO-TITLE-TEXT",
        noContentText = "NO-CONTENT-TEXT",
        openInBrowserTooltip = "OPEN-TOOLTIP",
    )

    @Test
    fun notLoadedFull_hasEmptyBodyAndUsesRowUrlAndTitle() {
        val doc = build(full = null)

        assertEquals("", doc.body)
        assertEquals(row.url, doc.articleUrl)
        assertTrue(doc.html.contains("ROW-TITLE"))
        assertTrue(doc.html.contains(row.url))
        // The body is still on its way: the "no content" notice must not flash.
        assertFalse(doc.html.contains("NO-CONTENT-TEXT"))
    }

    @Test
    fun blankBody_rendersNoContentNotice() {
        val doc = build(full = full(content = "   ", summary = "  "))

        assertEquals("", doc.body.trim())
        assertTrue(doc.html.contains("NO-CONTENT-TEXT"))
    }

    @Test
    fun nullContentAndSummary_rendersNoContentNotice() {
        val doc = build(full = full(content = null, summary = null))

        assertEquals("", doc.body)
        assertTrue(doc.html.contains("NO-CONTENT-TEXT"))
    }

    @Test
    fun summaryIsUsedWhenContentBlank() {
        val doc = build(full = full(content = " ", summary = "<p>SUMMARY-BODY</p>"))

        assertEquals("<p>SUMMARY-BODY</p>", doc.body)
        assertTrue(doc.html.contains("SUMMARY-BODY"))
        assertFalse(doc.html.contains("NO-CONTENT-TEXT"))
    }

    @Test
    fun blankTitle_usesNoTitleText() {
        val doc = build(row = row.copy(title = ""), full = full(title = ""))

        assertTrue(doc.html.contains("NO-TITLE-TEXT"))
    }

    @Test
    fun blankRowTitleWhileNotLoaded_usesNoTitleText() {
        val doc = build(row = row.copy(title = "  "), full = null)

        assertTrue(doc.html.contains("NO-TITLE-TEXT"))
    }

    @Test
    fun fullUrlAndTitleTakePrecedenceOverRow() {
        val doc = build(full = full())

        assertEquals("https://full.example/article", doc.articleUrl)
        assertEquals("<p>FULL-BODY</p>", doc.body)
        assertTrue(doc.html.contains("FULL-TITLE"))
        assertTrue(doc.html.contains("https://full.example/article"))
        assertFalse(doc.html.contains("ROW-TITLE"))
        assertFalse(doc.html.contains("https://row.example/article"))
    }

    @Test
    fun nullFeedName_omitsFavicon() {
        val doc = build(full = full(), feedName = null, feedFaviconUrl = FAVICON)

        assertFalse(doc.html.contains(FAVICON))
    }

    @Test
    fun nonNullFeedName_showsFavicon() {
        val doc = build(full = full(), feedName = "FEED-NAME", feedFaviconUrl = FAVICON)

        assertTrue(doc.html.contains(FAVICON))
        assertTrue(doc.html.contains("FEED-NAME"))
    }

    @Test
    fun nonNullFeedName_showsFaviconInNoContentAndNotLoadedStates() {
        val noContent = build(full = full(content = null), feedName = "FEED-NAME", feedFaviconUrl = FAVICON)
        val notLoaded = build(full = null, feedName = "FEED-NAME", feedFaviconUrl = FAVICON)

        assertTrue(noContent.html.contains(FAVICON))
        assertTrue(notLoaded.html.contains(FAVICON))
    }

    private companion object {
        const val FAVICON = "https://favicon.example/icon.png"
    }
}

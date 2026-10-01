package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [articleUrlCopyPlan] is the one decision every "copy article URL" route in both UIs carries out
 * (Compose's `ArticleUrlCopier`, SwiftUI's `ArticleUrlCopy`), so each case is pinned here.
 */
class ArticleUrlCopyPlanTest {

    private val nothing = ArticleUrlCopyPlan(writeClipboard = false, flashCopied = false)

    @Test
    fun aMissingUrlDoesNothing() {
        assertEquals(nothing, articleUrlCopyPlan(url = null, articleId = "a1", displayedArticleId = "a1"))
    }

    @Test
    fun aBlankUrlDoesNothing() {
        assertEquals(nothing, articleUrlCopyPlan(url = "", articleId = "a1", displayedArticleId = "a1"))
        assertEquals(nothing, articleUrlCopyPlan(url = "   ", articleId = "a1", displayedArticleId = "a1"))
    }

    @Test
    fun copyingTheDisplayedArticleWritesAndFlashes() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = true),
            articleUrlCopyPlan(url = "https://example.com/a1", articleId = "a1", displayedArticleId = "a1"),
        )
    }

    @Test
    fun copyingAnotherArticleWritesWithoutFlashing() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false),
            articleUrlCopyPlan(url = "https://example.com/a2", articleId = "a2", displayedArticleId = "a1"),
        )
    }

    @Test
    fun copyingWithNoDisplayedArticleWritesWithoutFlashing() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false),
            articleUrlCopyPlan(url = "https://example.com/a2", articleId = "a2", displayedArticleId = null),
        )
    }
}

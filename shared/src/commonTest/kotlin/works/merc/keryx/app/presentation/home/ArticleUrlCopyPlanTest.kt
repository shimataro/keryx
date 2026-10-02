package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [articleUrlCopyPlan] is the one decision every "copy article URL" route in both UIs carries out
 * (Compose's `ArticleUrlCopier`, SwiftUI's `ArticleUrlCopy`), so each case is pinned here —
 * including the confirmation truth table per platform kind.
 */
class ArticleUrlCopyPlanTest {

    private val nothing = ArticleUrlCopyPlan(writeClipboard = false, flashCopied = false, confirmInApp = false)

    /** A desktop (macOS / Windows / Linux, SwiftUI on macOS): no OS confirmation, the ✓ confirms. */
    private fun desktop(url: String?, articleId: String, displayedArticleId: String?) =
        articleUrlCopyPlan(url, articleId, displayedArticleId, platformShowsOwnConfirmation = false, inlineCheckConfirms = true)

    /** A touch platform with no OS confirmation (Android below API 33, iOS). */
    private fun touch(url: String?, articleId: String, displayedArticleId: String?) =
        articleUrlCopyPlan(url, articleId, displayedArticleId, platformShowsOwnConfirmation = false, inlineCheckConfirms = false)

    /** Android 13+: the OS confirms every clipboard write. */
    private fun osConfirms(url: String?, articleId: String, displayedArticleId: String?) =
        articleUrlCopyPlan(url, articleId, displayedArticleId, platformShowsOwnConfirmation = true, inlineCheckConfirms = false)

    @Test
    fun aMissingUrlDoesNothing() {
        assertEquals(nothing, desktop(url = null, articleId = "a1", displayedArticleId = "a1"))
        assertEquals(nothing, touch(url = null, articleId = "a1", displayedArticleId = "a1"))
    }

    @Test
    fun aBlankUrlDoesNothing() {
        assertEquals(nothing, touch(url = "", articleId = "a1", displayedArticleId = "a2"))
        assertEquals(nothing, desktop(url = "   ", articleId = "a1", displayedArticleId = "a2"))
    }

    @Test
    fun onADesktopTheDisplayedArticlesCheckIsTheConfirmation() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = true, confirmInApp = false),
            desktop(url = "https://example.com/a1", articleId = "a1", displayedArticleId = "a1"),
        )
    }

    @Test
    fun onADesktopAnotherArticleIsConfirmedInApp() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false, confirmInApp = true),
            desktop(url = "https://example.com/a2", articleId = "a2", displayedArticleId = "a1"),
        )
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false, confirmInApp = true),
            desktop(url = "https://example.com/a2", articleId = "a2", displayedArticleId = null),
        )
    }

    @Test
    fun onATouchPlatformEveryCopyIsConfirmedInApp() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = true, confirmInApp = true),
            touch(url = "https://example.com/a1", articleId = "a1", displayedArticleId = "a1"),
        )
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false, confirmInApp = true),
            touch(url = "https://example.com/a2", articleId = "a2", displayedArticleId = "a1"),
        )
    }

    @Test
    fun whereTheOsConfirmsTheAppNeverDoes() {
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = true, confirmInApp = false),
            osConfirms(url = "https://example.com/a1", articleId = "a1", displayedArticleId = "a1"),
        )
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false, confirmInApp = false),
            osConfirms(url = "https://example.com/a2", articleId = "a2", displayedArticleId = null),
        )
        assertEquals(
            ArticleUrlCopyPlan(writeClipboard = true, flashCopied = false, confirmInApp = false),
            articleUrlCopyPlan(
                url = "https://example.com/a2",
                articleId = "a2",
                displayedArticleId = "a1",
                platformShowsOwnConfirmation = true,
                inlineCheckConfirms = true,
            ),
        )
    }
}

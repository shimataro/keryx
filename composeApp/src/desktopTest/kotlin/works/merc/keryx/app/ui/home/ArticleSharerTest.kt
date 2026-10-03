package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ArticleSharer] is the one handler behind every "Share" route (the reader's button, the article
 * row's context menu): it guards with the same rule the routes' enabled state reads, and hands the
 * platform share sheet exactly the article's URL and title.
 */
class ArticleSharerTest {

    private data class Launch(val text: String, val subject: String?, val chooserTitle: String)

    private val launches = mutableListOf<Launch>()
    private val sharer = ArticleSharer(chooserTitle = "Share article") { text, subject, chooserTitle ->
        launches += Launch(text, subject, chooserTitle)
    }

    @Test
    fun sharesTheUrlWithTheTitleAsSubject() {
        sharer.share("https://example.com/a", "An article")

        assertEquals(listOf(Launch("https://example.com/a", "An article", "Share article")), launches)
    }

    @Test
    fun aMissingOrBlankTitleSharesNoSubject() {
        sharer.share("https://example.com/a", null)
        sharer.share("https://example.com/b", "  ")

        assertEquals(listOf(null, null), launches.map { it.subject })
    }

    @Test
    fun anUnusableUrlSharesNothing() {
        sharer.share(null, "Title")
        sharer.share("", "Title")
        sharer.share("   ", "Title")

        assertTrue(launches.isEmpty())
    }

    @Test
    fun canShareIsTheSameRuleAsTheGuard() {
        assertFalse(sharer.canShare(null))
        assertFalse(sharer.canShare(" "))
        assertTrue(sharer.canShare("/relative"))
        sharer.share("/relative", "T")
        assertEquals(1, launches.size)
    }
}

package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [canShareArticleUrl] is the one enablement rule (and guard) every "Share" route reads — the same
 * rule as copying, since a share hands the URL over as plain text.
 */
class CanShareArticleUrlTest {

    @Test
    fun aMissingOrBlankUrlCannotBeShared() {
        assertFalse(canShareArticleUrl(null))
        assertFalse(canShareArticleUrl(""))
        assertFalse(canShareArticleUrl("   "))
    }

    @Test
    fun anyNonBlankUrlCanBeSharedLikeACopy() {
        for (url in listOf("https://example.com/a", "http://example.com/a", "/relative/path", "mailto:x@example.com")) {
            assertTrue(canShareArticleUrl(url), url)
            assertTrue(canShareArticleUrl(url) == hasUsableUrl(url), url)
        }
    }
}

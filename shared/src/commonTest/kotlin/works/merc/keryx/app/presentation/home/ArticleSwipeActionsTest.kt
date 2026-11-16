package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which action each edge of an article row's swipe shows, in every UI — the whole truth table. */
class ArticleSwipeActionsTest {

    @Test
    fun leadingEdgeMarksAnUnreadArticleRead() {
        assertEquals(ArticleSwipeAction.MarkRead, articleSwipeLeadingAction(isRead = false))
    }

    @Test
    fun leadingEdgeMarksAReadArticleUnread() {
        assertEquals(ArticleSwipeAction.MarkUnread, articleSwipeLeadingAction(isRead = true))
    }

    @Test
    fun trailingEdgeStarsAnUnstarredArticle() {
        assertEquals(ArticleSwipeAction.Star, articleSwipeTrailingAction(isStarred = false))
    }

    @Test
    fun trailingEdgeUnstarsAStarredArticle() {
        assertEquals(ArticleSwipeAction.Unstar, articleSwipeTrailingAction(isStarred = true))
    }
}

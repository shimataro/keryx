package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Articles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-function coverage for [isShownUnread], the rule behind the reader's read/unread button state. */
class ReaderReadStateTest {

    private fun article(id: String, isRead: Long): Articles = Articles(
        id = id,
        feed_id = "f1",
        guid = "g$id",
        url = "https://example.com/$id",
        title = "Article $id",
        summary = null,
        content = null,
        author = null,
        published_at = 1L,
        thumbnail_url = null,
        is_read = isRead,
        read_at = null,
        is_starred = 0L,
        starred_at = null,
        cached_at = 0L,
        search_text = "",
        updated_at = 0L,
        created_at = 0L,
        deleted_at = null,
        deleted_updated_at = null,
    )

    @Test
    fun anUnreadArticleUnderTheCursorIsShownUnread() {
        assertTrue(isShownUnread(article("a1", isRead = 0L), cursor = "a1"))
    }

    @Test
    fun aReadArticleUnderTheCursorIsShownRead() {
        assertFalse(isShownUnread(article("a1", isRead = 1L), cursor = "a1"))
    }

    @Test
    fun anUnreadArticleTheCursorHasMovedAwayFromIsShownRead() {
        assertFalse(isShownUnread(article("a1", isRead = 0L), cursor = "a2"))
    }

    @Test
    fun nothingSelectedIsShownRead() {
        assertFalse(isShownUnread(null, cursor = null))
        assertFalse(isShownUnread(article("a1", isRead = 0L), cursor = null))
    }
}

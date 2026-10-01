package works.merc.keryx.app.ui.home

import kotlinx.datetime.TimeZone
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.platform.NativeMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [articleRowMenuEntries]: the article row's context menu is labelled from the read state
 * the article has once the right-click's own `onOpen` (select → mark read) has run, and each item
 * requests exactly the state its label promises.
 */
class ArticleRowMenuTest {

    private val strings = ArticleRowStrings(
        markAsRead = "Mark as read",
        markAsUnread = "Mark as unread",
        star = "Star",
        unstar = "Unstar",
        copyUrl = "Copy URL",
        openInBrowser = "Open in Browser",
        noTitleFallback = "(no title)",
        zone = TimeZone.UTC,
    )

    private fun article(read: Boolean, starred: Boolean = false) = ArticleListRow(
        id = "a1",
        feed_id = "f1",
        title = "Article",
        url = "https://example.com/a1",
        published_at = 0L,
        created_at = 0L,
        is_read = if (read) 1L else 0L,
        is_starred = if (starred) 1L else 0L,
    )

    private fun entries(
        article: ArticleListRow,
        selectedByOpen: Boolean,
        onSetRead: (Boolean) -> Unit = {},
        onSetStarred: (Boolean) -> Unit = {},
    ): List<NativeMenuItem> = articleRowMenuEntries(
        article = article,
        selectedByOpen = selectedByOpen,
        strings = strings,
        onSetRead = onSetRead,
        onSetStarred = onSetStarred,
        onCopyUrl = {},
        onOpenInBrowser = {},
    ).map { it as NativeMenuItem }

    private fun List<NativeMenuItem>.readItem() = single { it.label == "Mark as read" || it.label == "Mark as unread" }

    @Test
    fun unreadRowOpenedBySelectingOffersMarkAsUnread() {
        // The right-click selected the row, which marks it read before the menu appears.
        val menu = entries(article(read = false), selectedByOpen = true)

        assertEquals("Mark as unread", menu.readItem().label)
    }

    @Test
    fun unreadRowNotSelectedByOpenOffersMarkAsRead() {
        // Already selected (e.g. the user just marked it unread): the right-click selects nothing,
        // so the article really is still unread.
        val menu = entries(article(read = false), selectedByOpen = false)

        assertEquals("Mark as read", menu.readItem().label)
    }

    @Test
    fun readItemInvokesSetReadWithTheValueItsLabelPromises() {
        val requested = mutableListOf<Boolean>()

        entries(article(read = false), selectedByOpen = true, onSetRead = { requested += it }).readItem().onClick()
        entries(article(read = false), selectedByOpen = false, onSetRead = { requested += it }).readItem().onClick()
        entries(article(read = true), selectedByOpen = false, onSetRead = { requested += it }).readItem().onClick()

        // "Mark as unread" → false, "Mark as read" → true, "Mark as unread" → false.
        assertEquals(listOf(false, true, false), requested)
    }

    @Test
    fun starItemInvokesSetStarredWithTheValueItsLabelPromises() {
        val requested = mutableListOf<Boolean>()

        val unstarred = entries(article(read = true, starred = false), false, onSetStarred = { requested += it })
        val starred = entries(article(read = true, starred = true), false, onSetStarred = { requested += it })
        assertEquals("Star", unstarred.first().label)
        assertEquals("Unstar", starred.first().label)
        unstarred.first().onClick()
        starred.first().onClick()

        assertEquals(listOf(true, false), requested)
    }
}

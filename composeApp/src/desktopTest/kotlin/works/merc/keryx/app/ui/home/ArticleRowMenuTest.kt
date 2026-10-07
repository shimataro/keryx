package works.merc.keryx.app.ui.home

import kotlinx.datetime.TimeZone
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.platform.NativeMenuEntry
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeMenuSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [articleRowMenuEntries]: the article row's context menu is labelled from the read state
 * the article has once the right-click's own `onOpen` (select → mark read) has run, each item
 * requests exactly the state its label promises, and the entries follow the menu bar's Article
 * menu. Also covers [articleRowContextMenuOpen], that `onOpen` itself.
 */
class ArticleRowMenuTest {

    private val strings = ArticleRowStrings(
        markAsRead = "Mark as read",
        markAsUnread = "Mark as unread",
        star = "Star",
        unstar = "Unstar",
        copyUrl = "Copy URL",
        openInBrowser = "Open in Browser",
        share = "Share",
        noTitleFallback = "(no title)",
        zone = TimeZone.UTC,
        stateUnread = "Unread",
        stateStarred = "Starred",
    )

    private fun article(read: Boolean, starred: Boolean = false, url: String = "https://example.com/a1") = ArticleListRow(
        id = "a1",
        feed_id = "f1",
        title = "Article",
        url = url,
        published_at = 0L,
        created_at = 0L,
        is_read = if (read) 1L else 0L,
        is_starred = if (starred) 1L else 0L,
    )

    private fun rawEntries(
        article: ArticleListRow,
        selectedByOpen: Boolean,
        onSetRead: (Boolean) -> Unit = {},
        onSetStarred: (Boolean) -> Unit = {},
        onShare: (() -> Unit)? = null,
    ): List<NativeMenuEntry> = articleRowMenuEntries(
        article = article,
        selectedByOpen = selectedByOpen,
        strings = strings,
        onSetRead = onSetRead,
        onSetStarred = onSetStarred,
        onCopyUrl = {},
        onOpenInBrowser = {},
        onShare = onShare,
    )

    private fun entries(
        article: ArticleListRow,
        selectedByOpen: Boolean,
        onSetRead: (Boolean) -> Unit = {},
        onSetStarred: (Boolean) -> Unit = {},
    ): List<NativeMenuItem> = rawEntries(article, selectedByOpen, onSetRead, onSetStarred).filterIsInstance<NativeMenuItem>()

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
        val unstarredItem = unstarred.single { it.label == "Star" }
        val starredItem = starred.single { it.label == "Unstar" }
        unstarredItem.onClick()
        starredItem.onClick()

        assertEquals(listOf(true, false), requested)
    }

    @Test
    fun openIsDisabledForANonHttpUrlWhileCopyStaysEnabled() {
        // Same rules as every other route: any non-blank URL can be copied, only http(s) is opened.
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "/relative/path")) {
            val menu = entries(article(read = true, url = url), selectedByOpen = false)

            assertTrue(menu.single { it.label == "Copy URL" }.enabled, "copy for $url")
            assertFalse(menu.single { it.label == "Open in Browser" }.enabled, "open for $url")
        }
        val http = entries(article(read = true), selectedByOpen = false)
        assertTrue(http.single { it.label == "Open in Browser" }.enabled)
    }

    @Test
    fun entriesFollowTheMenuBarsArticleMenuOrder() {
        // AppMenuTree's Article menu: star, read, separator, open in browser, copy URL.
        val labels = rawEntries(article(read = true), selectedByOpen = false).map { (it as? NativeMenuItem)?.label ?: "---" }

        assertEquals(listOf("Star", "Mark as unread", "---", "Open in Browser", "Copy URL"), labels)
        assertTrue(rawEntries(article(read = true), selectedByOpen = false)[2] === NativeMenuSeparator)
    }

    @Test
    fun withoutAShareHandlerThereIsNoShareItem() {
        // Desktop: no share sheet, so HomeScreen passes no handler and the item is left out.
        val labels = entries(article(read = true), selectedByOpen = false).map { it.label }

        assertFalse("Share" in labels)
    }

    @Test
    fun withAShareHandlerShareComesLastAndSharesThisRow() {
        var shared = 0
        val raw = rawEntries(article(read = true), selectedByOpen = false, onShare = { shared++ })
        val labels = raw.map { (it as? NativeMenuItem)?.label ?: "---" }

        assertEquals(listOf("Star", "Mark as unread", "---", "Open in Browser", "Copy URL", "Share"), labels)
        val share = raw.filterIsInstance<NativeMenuItem>().single { it.label == "Share" }
        assertTrue(share.enabled)
        share.onClick()
        assertEquals(1, shared)
    }

    @Test
    fun shareFollowsTheCopyRuleNotTheOpenRule() {
        // canShareArticleUrl: any non-blank URL, like copying — a share hands the text over.
        for (url in listOf("file:///etc/passwd", "/relative/path", "https://example.com/a")) {
            val menu = rawEntries(article(read = true, url = url), selectedByOpen = false, onShare = {})
                .filterIsInstance<NativeMenuItem>()
            assertTrue(menu.single { it.label == "Share" }.enabled, "share for $url")
        }
        for (url in listOf("", "   ")) {
            val menu = rawEntries(article(read = true, url = url), selectedByOpen = false, onShare = {})
                .filterIsInstance<NativeMenuItem>()
            assertFalse(menu.single { it.label == "Share" }.enabled, "share for '$url'")
        }
    }

    @Test
    fun openingTheMenuOnAnUnselectedRowSelectsIt() {
        val calls = mutableListOf<String>()

        val selectedByOpen = articleRowContextMenuOpen(selected = false, select = { calls += "select" }, activate = { calls += "activate" })

        assertTrue(selectedByOpen)
        assertEquals(listOf("select"), calls)
    }

    @Test
    fun openingTheMenuOnTheSelectedRowOnlyActivatesThePane() {
        // Re-selecting would mark read again an article just marked unread, but focus must still
        // follow the right-click into the article list.
        val calls = mutableListOf<String>()

        val selectedByOpen = articleRowContextMenuOpen(selected = true, select = { calls += "select" }, activate = { calls += "activate" })

        assertFalse(selectedByOpen)
        assertEquals(listOf("activate"), calls)
    }
}

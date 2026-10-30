package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/** [articleListModeFor] — which mode the list shows — and [listModeChecked] — which items the mode menus check. */
class ArticleListModeTest {
    private fun checked(mode: ArticleListMode, starredModeAvailable: Boolean) =
        ArticleListMode.entries.filter { listModeChecked(it, mode, starredModeAvailable) }

    @Test
    fun onlyTheCurrentModeIsCheckedWhereStarredCanBeChosen() {
        for (mode in ArticleListMode.entries) {
            assertEquals(listOf(mode), checked(mode, starredModeAvailable = true), "mode $mode")
        }
    }

    @Test
    fun starredStaysCheckedBesideTheCurrentModeWhereItCannotBeChosen() {
        assertEquals(listOf(ArticleListMode.All, ArticleListMode.Starred), checked(ArticleListMode.All, starredModeAvailable = false))
        assertEquals(listOf(ArticleListMode.Unread, ArticleListMode.Starred), checked(ArticleListMode.Unread, starredModeAvailable = false))
    }

    private val booleans = listOf(false, true)

    @Test
    fun underTheStarredFilterOnlyItsOwnUnreadStateDecides() {
        for (unreadOnly in booleans) for (unreadOnlyStarred in booleans) for (starredMode in booleans) {
            assertEquals(
                if (unreadOnlyStarred) ArticleListMode.Unread else ArticleListMode.All,
                articleListModeFor(ArticleFilter.Starred, unreadOnly, unreadOnlyStarred, starredMode),
                "unreadOnly=$unreadOnly unreadOnlyStarred=$unreadOnlyStarred starredMode=$starredMode",
            )
        }
    }

    @Test
    fun elsewhereStarredModeWinsOverUnreadOnlyAndTheStarredFilterStateIsIgnored() {
        val filters = listOf(ArticleFilter.All, ArticleFilter.Feed("f"), ArticleFilter.Tag("t"), ArticleFilter.Folder("d"))
        for (filter in filters) for (unreadOnly in booleans) for (unreadOnlyStarred in booleans) for (starredMode in booleans) {
            val expected = when {
                starredMode -> ArticleListMode.Starred
                unreadOnly -> ArticleListMode.Unread
                else -> ArticleListMode.All
            }
            assertEquals(
                expected,
                articleListModeFor(filter, unreadOnly, unreadOnlyStarred, starredMode),
                "filter=$filter unreadOnly=$unreadOnly unreadOnlyStarred=$unreadOnlyStarred starredMode=$starredMode",
            )
        }
    }
}

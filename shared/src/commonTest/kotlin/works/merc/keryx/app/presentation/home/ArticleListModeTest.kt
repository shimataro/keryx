package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** [listModeChecked] — which items the mode menus check. */
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
}

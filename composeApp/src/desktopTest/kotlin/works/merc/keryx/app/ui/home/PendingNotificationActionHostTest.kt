package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where acting on a "feed gone" / "feed URL changed" notification actually lands.
 *
 * `ShowFeedDetail` used to always focus the feed list, which reads as "select that feed" only
 * where the article list is on screen beside it. At `PaneLayout.Single` it instead navigated
 * *backwards*, onto a list whose selection highlight isn't even painted — so acting on the alert
 * looked like nothing had happened. See `HomePaneLayout.kt`'s `paneForFeedDetail`.
 */
@OptIn(ExperimentalTestApi::class)
class PendingNotificationActionHostTest {

    private fun showFeedDetail(feedId: String) = AppNotification(
        id = "n",
        level = AppNotificationLevel.WARNING,
        message = "msg",
        timestampMillis = 0L,
        action = AppNotificationAction.ShowFeedDetail(feedId),
    )

    private fun focusedPaneAfterShowFeedDetail(layout: PaneLayout): Pair<HomePane?, ArticleFilter> {
        val (driver, db) = inMemoryDb()
        val fixture = newHomeViewModel(driver, db)
        var focused: HomePane? = null
        try {
            runDesktopComposeUiTest {
                val notifVm = NotificationCenterViewModel(NotificationCenter())
                setContent {
                    PendingNotificationActionHost(fixture.vm, notifVm, layout, onFocusPane = { focused = it })
                }
                waitForIdle()

                notifVm.requestAction(showFeedDetail("feed-1"))
                waitForIdle()
            }
            return focused to fixture.vm.filter.value
        } finally {
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun showFeedDetailFocusesTheFeedListWhereTheArticleListIsBesideIt() {
        for (layout in listOf(PaneLayout.Triple, PaneLayout.Dual)) {
            val (focused, filter) = focusedPaneAfterShowFeedDetail(layout)
            assertEquals(HomePane.FeedList, focused, layout.name)
            assertEquals(ArticleFilter.Feed("feed-1"), filter, layout.name)
        }
    }

    @Test
    fun showFeedDetailAdvancesToTheFeedsArticlesAtASinglePaneWidth() {
        val (focused, filter) = focusedPaneAfterShowFeedDetail(PaneLayout.Single)
        assertEquals(HomePane.ArticleList, focused)
        assertEquals(ArticleFilter.Feed("feed-1"), filter)
    }
}

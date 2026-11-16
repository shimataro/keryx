package works.merc.keryx.app.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.presentation.home.ArticleSwipeAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [articleRowSwipe] through [ArticleRow]: past the threshold a swipe runs its edge's action
 * without selecting the row, a short drag runs nothing, TalkBack gets both actions, and a
 * non-touch-primary row (desktop) neither swipes nor exposes the actions.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleRowSwipeTest {

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

    private val article = ArticleListRow(
        id = "a1",
        feed_id = "f1",
        title = "Article a1",
        url = "https://example.com/a1",
        published_at = 0L,
        created_at = 0L,
        is_read = 0L,
        is_starred = 0L,
    )

    private class Recorded {
        val actions = mutableListOf<ArticleSwipeAction>()
        var clicks = 0
    }

    private fun ComposeUiTest.showRow(isTouchPrimary: Boolean): Recorded {
        val recorded = Recorded()
        setContent {
            ArticleRow(
                article = article,
                feedTitle = "Feed",
                feedFavicon = null,
                selected = false,
                focused = true,
                rowHeight = 48.dp,
                faviconSize = 20.dp,
                onClick = { recorded.clicks++ },
                onSetRead = {},
                onSetStarred = {},
                onCopyUrl = {},
                onOpenInBrowser = {},
                strings = strings,
                isTouchPrimary = isTouchPrimary,
                onSwipeAction = { recorded.actions += it },
            )
        }
        waitForIdle()
        return recorded
    }

    private fun ComposeUiTest.row(): SemanticsNodeInteraction = onNodeWithTag("article-a1")

    @Test
    fun swipingRightMarksAnUnreadRowReadWithoutSelectingIt() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput { swipeRight() }
        waitForIdle()

        assertEquals(listOf(ArticleSwipeAction.MarkRead), recorded.actions)
        assertEquals(0, recorded.clicks)
    }

    @Test
    fun swipingLeftStarsAnUnstarredRow() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput { swipeLeft() }
        waitForIdle()

        assertEquals(listOf(ArticleSwipeAction.Star), recorded.actions)
        assertEquals(0, recorded.clicks)
    }

    @Test
    fun aDragShortOfTheThresholdRunsNothing() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput {
            // Well past the touch slop, well short of ARTICLE_SWIPE_THRESHOLD (56dp).
            swipeRight(startX = centerX, endX = centerX + 30.dp.toPx())
        }
        waitForIdle()

        assertEquals(emptyList(), recorded.actions)
    }

    @Test
    fun theSwipeActionsAreAccessibilityCustomActions() = runDesktopComposeUiTest {
        showRow(isTouchPrimary = true)

        val labels = row().fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Mark as read", "Star"), labels)
    }

    @Test
    fun aNonTouchPrimaryRowDoesNotSwipe() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = false)

        row().performTouchInput { swipeRight() }
        row().performTouchInput { swipeLeft() }
        waitForIdle()

        assertEquals(emptyList(), recorded.actions)
        assertNull(row().fetchSemanticsNode().config.getOrElseNullable(SemanticsActions.CustomActions) { null })
    }
}

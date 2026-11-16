package works.merc.keryx.app.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.presentation.home.ArticleSwipeAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [articleRowSwipe] through [ArticleRow]: past the threshold a swipe runs its edge's action
 * without selecting the row (mirrored in a right-to-left layout); a short drag, a drag taken back
 * below the threshold, a diagonal drag and a swipe right after a vertical scroll run nothing; a second
 * swipe during the first one's spring-back still runs; TalkBack gets both actions; and a
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

    private fun ComposeUiTest.showRow(
        isTouchPrimary: Boolean,
        row: ArticleListRow = article,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): Recorded {
        val recorded = Recorded()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                ArticleRow(
                    article = row,
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
                    swipeLockout = remember { VerticalGestureLockout() },
                    strings = strings,
                    isTouchPrimary = isTouchPrimary,
                    onSwipeAction = { recorded.actions += it },
                )
            }
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
    fun aDiagonalDragRunsNothing() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput {
            // A 45-degree flick — a vertical scroll that drifted sideways, not a swipe.
            swipe(start = center, end = center + Offset(100.dp.toPx(), 100.dp.toPx()))
        }
        waitForIdle()

        assertEquals(emptyList(), recorded.actions)
    }

    @Test
    fun aHorizontalSwipeRightAfterAVerticalOneRunsNothingUntilTheLockoutPasses() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput {
            swipeDown()
            // Begins as soon as the scroll ends — inside SWIPE_AFTER_VERTICAL_LOCKOUT_MS (400 ms).
            swipeRight()
        }
        waitForIdle()
        assertEquals(emptyList(), recorded.actions)

        row().performTouchInput {
            advanceEventTime(1_000L)
            swipeRight()
        }
        waitForIdle()
        assertEquals(listOf(ArticleSwipeAction.MarkRead), recorded.actions)
    }

    @Test
    fun aRightToLeftLayoutMirrorsTheEdges() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true, layoutDirection = LayoutDirection.Rtl)

        // In a right-to-left layout the leading edge is on the right, so a leftward drag reveals it.
        row().performTouchInput { swipeRight() }
        waitForIdle()
        row().performTouchInput {
            advanceEventTime(1_000L)
            swipeLeft()
        }
        waitForIdle()

        assertEquals(listOf(ArticleSwipeAction.Star, ArticleSwipeAction.MarkRead), recorded.actions)
    }

    @Test
    fun aDragPastTheThresholdAndBackRunsNothing() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        row().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(10.dp.toPx(), 0f)) }
            repeat(10) { moveBy(Offset(-10.dp.toPx(), 0f)) }
            up()
        }
        waitForIdle()

        assertEquals(emptyList(), recorded.actions)
    }

    @Test
    fun aReadStarredRowSwipesToMarkUnreadAndUnstar() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true, row = article.copy(is_read = 1L, is_starred = 1L))

        row().performTouchInput { swipeRight() }
        waitForIdle()
        row().performTouchInput {
            advanceEventTime(1_000L)
            swipeLeft()
        }
        waitForIdle()

        assertEquals(listOf(ArticleSwipeAction.MarkUnread, ArticleSwipeAction.Unstar), recorded.actions)
    }

    @Test
    fun theAccessibilityActionsRunTheSwipeActions() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)

        val actions = row().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnUiThread { actions.forEach { it.action() } }

        assertEquals(listOf(ArticleSwipeAction.MarkRead, ArticleSwipeAction.Star), recorded.actions)
    }

    @Test
    fun aSecondSwipeDuringTheSpringBackIsNotLost() = runDesktopComposeUiTest {
        val recorded = showRow(isTouchPrimary = true)
        mainClock.autoAdvance = false

        row().performTouchInput { swipeRight() }
        // A few frames into the first swipe's spring-back, well short of its end.
        repeat(5) { mainClock.advanceTimeByFrame() }
        // The second drag runs frame by frame, so a spring-back still writing the offset would
        // overwrite each move and leave the row short of the threshold on release.
        row().performTouchInput { down(center) }
        repeat(10) {
            row().performTouchInput { moveBy(Offset(-40.dp.toPx(), 0f)) }
            mainClock.advanceTimeByFrame()
        }
        row().performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(listOf(ArticleSwipeAction.MarkRead, ArticleSwipeAction.Star), recorded.actions)
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

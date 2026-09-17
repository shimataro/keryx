package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composed-level coverage for [ArticleReaderPagerSync] — the glue `ArticlePagerSyncTest`'s pure
 * functions are wired into — driven through real gestures and a real [PagerState], the same way
 * [ArticleSwipeGestureTest] drives [articleSwipeNavigation] itself. Neither of those two other
 * suites exercises the two `LaunchedEffect`s together with a live pager, which is exactly where a
 * key that fails to restart, or two effects that undo each other, would surface.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleReaderPagerSyncTest {

    private val hostWidthDp = 400.dp
    private val commitDragDp = 200f

    private fun rows(vararg ids: String): List<ArticleListRow> = ids.map { id ->
        ArticleListRow(
            id = id, feed_id = "f1", title = "Article $id", url = "https://example.com/$id",
            published_at = 1L, created_at = 1L, is_read = 0L, is_starred = 0L,
        )
    }

    @Test
    fun aCommittedSwipeReportsTheRowItLandedOnThroughOnPageSettled() = runDesktopComposeUiTest {
        val pages = rows("a1", "a2", "a3")
        var selectedId by mutableStateOf<String?>("a2")
        val settled = mutableListOf<String>()

        setContent {
            val pagerState = rememberPagerState(initialPage = 1) { pages.size }
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) { row -> settled += row.id; selectedId = row.id }
            Box(
                Modifier.testTag("root").size(hostWidthDp, 500.dp)
                    .onSizeChanged { controller.widthPx = it.width.toFloat() }
                    .articleSwipeNavigation(controller),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        fun Dp.toPxOffset(): Float = with(density) { toPx() }
        val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())
        onNodeWithTag("root").performTouchInput {
            down(start)
            advanceEventTime(16L)
            moveBy(Offset((-commitDragDp).dp.toPxOffset(), 0f))
            up()
        }
        waitForIdle()

        assertEquals(listOf("a3"), settled)
    }

    @Test
    fun aCancelledSwipeNeverCallsOnPageSettled() = runDesktopComposeUiTest {
        val pages = rows("a1", "a2", "a3")
        val selectedId by mutableStateOf<String?>("a2")
        val settled = mutableListOf<String>()

        setContent {
            val pagerState = rememberPagerState(initialPage = 1) { pages.size }
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) { row -> settled += row.id }
            Box(
                Modifier.testTag("root").size(hostWidthDp, 500.dp)
                    .onSizeChanged { controller.widthPx = it.width.toFloat() }
                    .articleSwipeNavigation(controller),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        fun Dp.toPxOffset(): Float = with(density) { toPx() }
        val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())
        // Short, slow drag: well under the commit threshold, spread across several steps so its
        // velocity also stays under the fling threshold.
        onNodeWithTag("root").performTouchInput {
            down(start)
            repeat(10) {
                advanceEventTime(30L)
                moveBy(Offset((-4f).dp.toPxOffset(), 0f))
            }
            up()
        }
        waitForIdle()

        assertTrue(settled.isEmpty())
    }

    /**
     * Regression test for the case `settledPageSelects`'s `isSwipeTarget` gate exists to prevent:
     * `PagerState` clamps its own current page when the backing list shrinks below it (a search, a
     * sync-merge tombstone, "unread only" hiding the article just read), with no swipe involved.
     * Before that gate, this clamp would select — and mark read — whatever article landed at the
     * clamped index.
     */
    @Test
    fun theListShrinkingWithNoGestureNeverCallsOnPageSettled() = runDesktopComposeUiTest {
        var pages by mutableStateOf(rows("a1", "a2", "a3"))
        val selectedId by mutableStateOf<String?>("a3")
        val settled = mutableListOf<String>()
        var pagerStateRef: PagerState? = null

        setContent {
            val pagerState = rememberPagerState(initialPage = 2) { pages.size }
            pagerStateRef = pagerState
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) { row -> settled += row.id }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.size(hostWidthDp, 500.dp),
                userScrollEnabled = false,
            ) { Box(Modifier.fillMaxSize()) }
        }
        waitForIdle()
        assertEquals(2, pagerStateRef?.currentPage)

        // Shrinks out from under the current page with no gesture at all.
        pages = rows("a1")
        waitForIdle()

        assertTrue(settled.isEmpty())
    }

    @Test
    fun aSelectionMadeElsewhereMovesThePagerToIt() = runDesktopComposeUiTest {
        val pages = rows("a1", "a2", "a3")
        var selectedId by mutableStateOf<String?>("a1")
        var pagerStateRef: PagerState? = null

        setContent {
            val pagerState = rememberPagerState(initialPage = 0) { pages.size }
            pagerStateRef = pagerState
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) {}
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.size(hostWidthDp, 500.dp),
                userScrollEnabled = false,
            ) { Box(Modifier.fillMaxSize()) }
        }
        waitForIdle()

        // J/K, a list click, or an accessibility action — anything that isn't this pager's own swipe.
        selectedId = "a3"
        waitForIdle()

        assertEquals(2, pagerStateRef?.currentPage)
    }

    /**
     * Regression test for the race `settleJob?.cancel()` opens: cancellation does not wait for the
     * cancelled job's `finally` to run, so a settle abandoned by a newer gesture can still clear
     * [ArticleSwipeController.gestureInProgress] after that newer gesture has already set it `true`
     * — see `ArticleSwipeController.gestureGeneration`'s own KDoc. The clock is frozen so the first
     * settle is caught mid-flight (suspended in `animateScrollToPage`, before its `finally`), then
     * stepped one frame at a time while a second gesture is in progress: the fixed code must never
     * let the abandoned settle's own `finally` win that race.
     */
    @Test
    fun aSettleCancelledByANewerGestureCannotClearItsGestureInProgress() = runDesktopComposeUiTest {
        val pages = rows("a1", "a2", "a3")
        var selectedId by mutableStateOf<String?>("a2")
        var controllerRef: ArticleSwipeController? = null

        setContent {
            val pagerState = rememberPagerState(initialPage = 1) { pages.size }
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            controllerRef = controller
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) { row -> selectedId = row.id }
            Box(
                Modifier.testTag("root").size(hostWidthDp, 500.dp)
                    .onSizeChanged { controller.widthPx = it.width.toFloat() }
                    .articleSwipeNavigation(controller),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        fun Dp.toPxOffset(): Float = with(density) { toPx() }
        val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())

        // A fast, short flick (see ArticleSwipeGestureTest's own
        // aFastShortFlickBelowTheCommitDistanceStillTurnsThePageViaFlingVelocity) commits via
        // velocity alone, leaving almost the whole pane width still to animate — unlike a drag that
        // already crosses the commit fraction, whose settle has almost nowhere left to travel and
        // can finish within a single evaluated frame. Freezing the clock right after is what turns
        // that long remaining distance into a settle reliably caught mid-flight, before it ever
        // reaches its `finally`.
        mainClock.autoAdvance = false
        onNodeWithTag("root").performTouchInput {
            down(start)
            advanceEventTime(4L)
            moveBy(Offset((-50f).dp.toPxOffset(), 0f))
            up()
        }
        waitForIdle()
        assertTrue(controllerRef!!.gestureInProgress)

        // A second gesture starts (and, via onDragStart, cancels the still-frozen first settle)
        // before that settle's `finally` has ever run.
        onNodeWithTag("root").performTouchInput {
            down(start)
            advanceEventTime(4L)
            moveBy(Offset((-50f).dp.toPxOffset(), 0f))
            up()
        }
        waitForIdle()

        // Step the frozen clock: this is where the cancelled first settle's `finally` actually gets
        // to run. The buggy code clears gestureInProgress here even though the second gesture's own
        // settle is still in flight (it has just as much distance left to animate); the fix must not.
        repeat(5) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue(controllerRef!!.gestureInProgress)
        }

        mainClock.autoAdvance = true
        waitForIdle()

        // The second swipe still resolves normally once let through to completion — both gestures
        // dragged from the same still-frozen anchor (a2), so this is the second gesture's own
        // settle actually landing, not the cancelled first one.
        assertEquals("a3", selectedId)
        assertTrue(!controllerRef!!.gestureInProgress)
    }

    @Test
    fun aGestureInFlightIsNeverInterruptedByAConcurrentSelectionChange() = runDesktopComposeUiTest {
        val pages = rows("a1", "a2", "a3")
        var selectedId by mutableStateOf<String?>("a2")

        setContent {
            val pagerState = rememberPagerState(initialPage = 1) { pages.size }
            val controller = rememberArticleSwipeController(pagerState, { true }, { true })
            ArticleReaderPagerSync(pagerState, pages, selectedId, controller) { row -> selectedId = row.id }
            Box(
                Modifier.testTag("root").size(hostWidthDp, 500.dp)
                    .onSizeChanged { controller.widthPx = it.width.toFloat() }
                    .articleSwipeNavigation(controller),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        fun Dp.toPxOffset(): Float = with(density) { toPx() }
        val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())

        // Split across two performTouchInput calls: the gesture is still held after the first.
        onNodeWithTag("root").performTouchInput {
            down(start)
            advanceEventTime(16L)
            moveBy(Offset((-commitDragDp).dp.toPxOffset(), 0f))
        }
        // A background write (a sync merge propagating a read from another device) changes the
        // selection while the finger is still down. This must not yank the pager elsewhere —
        // ArticlePagerSync's own `gestureInProgress` gate exists precisely for this window.
        selectedId = "a1"
        waitForIdle()
        onNodeWithTag("root").performTouchInput { up() }
        waitForIdle()

        // The swipe still resolves to the page the drag was actually turning to (a3, one past the
        // start), not to "a1" — the concurrent selection change was deferred, not obeyed mid-drag.
        assertEquals("a3", selectedId)
    }

}

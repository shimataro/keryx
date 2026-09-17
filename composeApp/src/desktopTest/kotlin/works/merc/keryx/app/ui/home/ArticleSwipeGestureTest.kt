package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end Compose UI tests for [articleSwipeNavigation], driven through [performTouchInput]
 * against a bare host: a real [HorizontalPager] with no content, since what the gesture produces is
 * a page change and nothing else — selecting the article that page holds is `ArticlePagerSync`'s
 * job, covered separately. The gesture logic itself is entirely `HomeViewModel`-agnostic (see
 * `ArticleSwipeNavTest` for the pure-function distance/velocity/direction coverage this
 * complements).
 */
@OptIn(ExperimentalTestApi::class)
class ArticleSwipeGestureTest {

    private val hostWidthDp = 400.dp

    /** Comfortably past `SWIPE_COMMIT_FRACTION` (30%) of [hostWidthDp] — crossing it alone commits,
     * regardless of how fast the drag arrives there, so a single fast jump is fine here. */
    private val commitDragDp = 200f

    /** Well short of the commit distance. Spread across several slow steps (see [fireEvents]) so its
     * velocity also stays under the fling threshold — otherwise a single fast synthetic jump over
     * even a short distance could commit via velocity alone, which is not what this case means to
     * exercise (that path has its own coverage in `ArticleSwipeNavTest`). */
    private val cancelDragDp = 40f

    /** The page the host pager starts on, with one page either side of it to move to. */
    private val initialPage = 1

    /**
     * Drives one gesture whose [totalDrag] (in dp) is delivered as [steps] equal moves.
     *
     * @return The page the pager came to rest on.
     */
    private fun fireEvents(
        enabled: Boolean = true,
        canNext: Boolean = true,
        canPrevious: Boolean = true,
        totalDrag: Offset,
        steps: Int = 1,
        stepDurationMillis: Long = 16L,
    ): Int = fireGestures(
        enabled = enabled,
        canNext = canNext,
        canPrevious = canPrevious,
        gestures = listOf(List(steps) { Offset(totalDrag.x / steps, totalDrag.y / steps) }),
        stepDurationMillis = stepDurationMillis,
    )

    /**
     * Drives one or more consecutive gestures against a single composition.
     *
     * @param gestures One entry per gesture, each a list of per-move deltas **in dp**. Spelling the
     *   moves out individually (rather than a total plus a step count, as [fireEvents] does) is
     *   what lets a single gesture change direction partway through.
     * @param gestureGapMillis Event-time advanced between one gesture's `up` and the next one's
     *   `down` — what `SWIPE_AFTER_VERTICAL_LOCKOUT_MS` is measured against.
     * @return The page the pager came to rest on after every gesture had settled.
     */
    private fun fireGestures(
        enabled: Boolean = true,
        canNext: Boolean = true,
        canPrevious: Boolean = true,
        gestures: List<List<Offset>>,
        stepDurationMillis: Long = 16L,
        gestureGapMillis: Long = 0L,
    ): Int {
        var pager: PagerState? = null
        runDesktopComposeUiTest {
            setContent {
                val pagerState = rememberPagerState(initialPage = initialPage) { 3 }
                pager = pagerState
                val controller = rememberArticleSwipeController(
                    pagerState = pagerState,
                    canSelectNext = { canNext },
                    canSelectPrevious = { canPrevious },
                )
                Box(
                    Modifier.testTag("root").size(hostWidthDp, 500.dp)
                        .onSizeChanged { controller.widthPx = it.width.toFloat() }
                        .let { if (enabled) it.articleSwipeNavigation(controller) else it },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false,
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
            waitForIdle()
            fun Dp.toPxOffset(): Float = with(density) { toPx() }
            val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())
            onNodeWithTag("root").performTouchInput {
                gestures.forEachIndexed { index, steps ->
                    if (index > 0) advanceEventTime(gestureGapMillis)
                    down(start)
                    steps.forEach { step ->
                        advanceEventTime(stepDurationMillis)
                        moveBy(Offset(step.x.dp.toPxOffset(), step.y.dp.toPxOffset()))
                    }
                    up()
                }
            }
            waitForIdle()
        }
        return requireNotNull(pager).settledPage
    }

    @Test
    fun leftDragPastTheCommitThresholdTurnsToTheNextPage() {
        assertEquals(initialPage + 1, fireEvents(totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun rightDragPastTheCommitThresholdTurnsToThePreviousPage() {
        assertEquals(initialPage - 1, fireEvents(totalDrag = Offset(commitDragDp, 0f)))
    }

    @Test
    fun slowDragShortOfTheCommitThresholdTurnsNoPage() {
        assertEquals(
            initialPage,
            fireEvents(totalDrag = Offset(-cancelDragDp, 0f), steps = 10, stepDurationMillis = 30L),
        )
    }

    @Test
    fun aMostlyVerticalDragSelectsNothingRegardlessOfDistance() {
        assertEquals(initialPage, fireEvents(totalDrag = Offset(-cancelDragDp / 2, commitDragDp)))
    }

    @Test
    fun disabledModifierTurnsNoPage() {
        assertEquals(initialPage, fireEvents(enabled = false, totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun leftDragIsIgnoredWhenThereIsNoNextArticle() {
        assertEquals(initialPage, fireEvents(canNext = false, totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun rightDragIsIgnoredWhenThereIsNoPreviousArticle() {
        assertEquals(initialPage, fireEvents(canPrevious = false, totalDrag = Offset(commitDragDp, 0f)))
    }

    @Test
    fun aFastShortFlickBelowTheCommitDistanceStillTurnsThePageViaFlingVelocity() {
        // 50dp is well under SWIPE_COMMIT_FRACTION (30% of 400dp = 120dp), so only the
        // fling-velocity path (SWIPE_FLING_VELOCITY_DP_PER_S = 800dp/s) can commit this one — and
        // a 50dp move over a handful of milliseconds comfortably clears it. A single post-slop
        // move+up (fireEvents' default steps = 1) used to record only one VelocityTracker sample,
        // so calculateVelocity() returned 0 and this incorrectly cancelled.
        assertEquals(initialPage + 1, fireEvents(totalDrag = Offset(-50f, 0f)))
    }

    // --- Misfire guards: direction ratio, vertical latch, post-scroll lockout ---

    @Test
    fun aDiagonalDragTheOldGateWouldHaveAcceptedTurnsNoPage() {
        // 200dp across, 160dp down. The previous gate was `abs(x) > abs(y)`, which this clears, so
        // a flick meant as a scroll used to turn the page. SWIPE_DIRECTION_RATIO (1.5) needs
        // 160 * 1.5 = 240dp of horizontal travel before the same drag counts as horizontal.
        assertEquals(initialPage, fireEvents(totalDrag = Offset(-commitDragDp, commitDragDp * 0.8f)))
    }

    @Test
    fun aDragThatStartsVerticalThenTurnsHorizontalTurnsNoPage() {
        // Once past the slop while pointing down, the gesture is latched vertical for good — the
        // horizontal leg that follows would otherwise satisfy the ratio gate on its own (see the
        // companion case below) and commit a page turn out of what began as a scroll.
        assertEquals(
            initialPage,
            fireGestures(gestures = listOf(listOf(Offset(0f, 60f), Offset(-commitDragDp, 0f)))),
        )
    }

    @Test
    fun theSameTwoLegsInTheOppositeOrderStillTurnThePage() {
        // Same total travel as the case above, horizontal leg first: nothing latches, so this is a
        // page turn. Pins that the latch — not the totals — is what rejects the other ordering.
        assertEquals(
            initialPage + 1,
            fireGestures(gestures = listOf(listOf(Offset(-commitDragDp, 0f), Offset(0f, 60f)))),
        )
    }

    @Test
    fun aSwipeImmediatelyAfterAVerticalGestureIsLockedOut() {
        // The typical misfire: flicking down through a long article, one flick in the run comes
        // out sideways. The first gesture here is that scroll; the second is the stray swipe.
        assertEquals(
            initialPage,
            fireGestures(
                gestures = listOf(listOf(Offset(0f, commitDragDp)), listOf(Offset(-commitDragDp, 0f))),
                gestureGapMillis = 0L,
            ),
        )
    }

    @Test
    fun aSwipeWellAfterAVerticalGestureIsNotLockedOut() {
        // Same two gestures, a second apart — past SWIPE_AFTER_VERTICAL_LOCKOUT_MS (400ms), so a
        // deliberate page turn after reading a while is unaffected.
        assertEquals(
            initialPage + 1,
            fireGestures(
                gestures = listOf(listOf(Offset(0f, commitDragDp)), listOf(Offset(-commitDragDp, 0f))),
                gestureGapMillis = 1_000L,
            ),
        )
    }
}

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end Compose UI tests for [articleSwipeNavigation], driven through [performTouchInput]
 * against a bare host — the same lean, callback-recording shape as [KeyboardNavTest], since the
 * gesture logic itself is entirely `HomeViewModel`-agnostic (see `ArticleSwipeNavTest` for the
 * pure-function distance/velocity threshold coverage this complements).
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

    private fun fireEvents(
        enabled: Boolean = true,
        canNext: Boolean = true,
        canPrevious: Boolean = true,
        totalDrag: Offset,
        steps: Int = 1,
        stepDurationMillis: Long = 16L,
    ): List<String> {
        val fired = mutableListOf<String>()
        runDesktopComposeUiTest {
            var articleId by mutableStateOf("article-1")
            setContent {
                val controller = rememberArticleSwipeController(
                    canSelectNext = { canNext },
                    canSelectPrevious = { canPrevious },
                    onSelectNext = { fired += "next"; articleId = "article-2" },
                    onSelectPrevious = { fired += "previous"; articleId = "article-0" },
                    currentArticleId = { articleId },
                )
                Box(
                    Modifier.testTag("root").size(hostWidthDp, 500.dp)
                        .onSizeChanged { controller.widthPx = it.width.toFloat() }
                        .let { if (enabled) it.articleSwipeNavigation(controller) else it },
                )
            }
            waitForIdle()
            fun Dp.toPxOffset(): Float = with(density) { toPx() }
            val start = Offset(hostWidthDp.toPxOffset() / 2, 250.dp.toPxOffset())
            val stepDrag = Offset(totalDrag.x / steps, totalDrag.y / steps)
            onNodeWithTag("root").performTouchInput {
                down(start)
                repeat(steps) {
                    advanceEventTime(stepDurationMillis)
                    moveBy(Offset(stepDrag.x.dp.toPxOffset(), stepDrag.y.dp.toPxOffset()))
                }
                up()
            }
            waitForIdle()
        }
        return fired
    }

    @Test
    fun leftDragPastTheCommitThresholdSelectsNext() {
        assertEquals(listOf("next"), fireEvents(totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun rightDragPastTheCommitThresholdSelectsPrevious() {
        assertEquals(listOf("previous"), fireEvents(totalDrag = Offset(commitDragDp, 0f)))
    }

    @Test
    fun slowDragShortOfTheCommitThresholdSelectsNothing() {
        assertEquals(
            emptyList(),
            fireEvents(totalDrag = Offset(-cancelDragDp, 0f), steps = 10, stepDurationMillis = 30L),
        )
    }

    @Test
    fun aMostlyVerticalDragSelectsNothingRegardlessOfDistance() {
        assertEquals(emptyList(), fireEvents(totalDrag = Offset(-cancelDragDp / 2, commitDragDp)))
    }

    @Test
    fun disabledModifierNeverFires() {
        assertEquals(emptyList(), fireEvents(enabled = false, totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun leftDragIsIgnoredWhenThereIsNoNextArticle() {
        assertEquals(emptyList(), fireEvents(canNext = false, totalDrag = Offset(-commitDragDp, 0f)))
    }

    @Test
    fun rightDragIsIgnoredWhenThereIsNoPreviousArticle() {
        assertEquals(emptyList(), fireEvents(canPrevious = false, totalDrag = Offset(commitDragDp, 0f)))
    }

    @Test
    fun aFastShortFlickBelowTheCommitDistanceStillSelectsNextViaFlingVelocity() {
        // 50dp is well under SWIPE_COMMIT_FRACTION (30% of 400dp = 120dp), so only the
        // fling-velocity path (SWIPE_FLING_VELOCITY_DP_PER_S = 800dp/s) can commit this one — and
        // a 50dp move over a handful of milliseconds comfortably clears it. A single post-slop
        // move+up (fireEvents' default steps = 1) used to record only one VelocityTracker sample,
        // so calculateVelocity() returned 0 and this incorrectly cancelled.
        assertEquals(listOf("next"), fireEvents(totalDrag = Offset(-50f, 0f)))
    }
}

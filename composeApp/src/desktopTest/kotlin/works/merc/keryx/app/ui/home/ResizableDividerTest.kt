package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ResizableDivider] must lose its drag affordance entirely on a touch-primary platform — see its
 * own KDoc for why (8dp is well under any reasonable touch target, and M3 has no touch-oriented
 * pane-splitter idiom).
 */
@OptIn(ExperimentalTestApi::class)
class ResizableDividerTest {

    /** Comfortably above the default `ViewConfiguration.touchSlop`, so a single move reliably
     * crosses the drag-start threshold regardless of test-environment density rounding. */
    private val androidx.compose.ui.test.ComposeUiTest.dragThresholdCrossPx: Float
        get() = with(density) { 32.dp.toPx() }

    @Test
    fun dragUpdatesWidthWhenNotTouchPrimary() = runDesktopComposeUiTest {
        var totalDelta = 0f
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("divider").size(8.dp, 200.dp)) {
                    ResizableDivider(onDrag = { totalDelta += it }, isTouchPrimary = false)
                }
            }
        }
        val delta = dragThresholdCrossPx

        onNodeWithTag("divider").performMouseInput {
            moveTo(center)
            press()
            // The first move past the drag threshold is consumed overcoming touch slop and reports
            // no delta of its own — a second move is what actually generates a reported delta.
            moveTo(center + Offset(delta, 0f))
            moveTo(center + Offset(delta * 2, 0f))
            release()
        }

        assertTrue(totalDelta != 0f, "expected onDrag to fire when not touch-primary")
    }

    @Test
    fun dragDoesNothingWhenTouchPrimary() = runDesktopComposeUiTest {
        var totalDelta = 0f
        setContent {
            MaterialTheme {
                Box(Modifier.testTag("divider").size(8.dp, 200.dp)) {
                    ResizableDivider(onDrag = { totalDelta += it }, isTouchPrimary = true)
                }
            }
        }
        val delta = dragThresholdCrossPx

        onNodeWithTag("divider").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(delta, 0f))
            release()
        }

        assertEquals(0f, totalDelta, "expected onDrag to never fire when touch-primary")
    }
}

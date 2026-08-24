package works.merc.keryx.app.ui.theme

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformThemeTest {

    private fun countAfter(interactions: List<Interaction>): Int =
        interactions.fold(0) { count, interaction -> nextPressCount(count, interaction) }

    @Test
    fun pressIncrementsCount() {
        assertEquals(1, countAfter(listOf(PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero))))
    }

    @Test
    fun releaseAfterMatchingPressReturnsToZero() {
        val press = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        assertEquals(0, countAfter(listOf(press, PressInteraction.Release(press))))
    }

    @Test
    fun oneReleaseDuringTwoOverlappingPressesStaysPressed() {
        val first = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        val second = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        assertEquals(1, countAfter(listOf(first, second, PressInteraction.Release(first))))
    }

    @Test
    fun releaseWithoutMatchingPressDoesNotGoNegative() {
        val stray = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        assertEquals(0, countAfter(listOf(PressInteraction.Release(stray))))
    }

    @Test
    fun repeatedUnmatchedReleasesStayAtZero() {
        val stray = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        assertEquals(
            0,
            countAfter(listOf(PressInteraction.Release(stray), PressInteraction.Cancel(stray), PressInteraction.Release(stray))),
        )
    }

    @Test
    fun pressAfterUnmatchedReleasesRendersFeedbackImmediately() {
        val stray = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        val real = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
        assertEquals(1, countAfter(listOf(PressInteraction.Release(stray), PressInteraction.Cancel(stray), real)))
    }

    @Test
    fun dragStartAndStopBehaveLikePress() {
        val drag = DragInteraction.Start()
        assertEquals(1, countAfter(listOf(drag)))
        assertEquals(0, countAfter(listOf(drag, DragInteraction.Stop(drag))))
    }

    @Test
    fun unmatchedDragCancelDoesNotGoNegative() {
        val drag = DragInteraction.Start()
        assertEquals(0, countAfter(listOf(DragInteraction.Cancel(drag))))
    }

    @Test
    fun hoverAndFocusInteractionsLeaveCountUnchanged() {
        assertEquals(
            0,
            countAfter(
                listOf(
                    HoverInteraction.Enter(),
                    HoverInteraction.Exit(HoverInteraction.Enter()),
                    FocusInteraction.Focus(),
                    FocusInteraction.Unfocus(FocusInteraction.Focus()),
                ),
            ),
        )
    }
}

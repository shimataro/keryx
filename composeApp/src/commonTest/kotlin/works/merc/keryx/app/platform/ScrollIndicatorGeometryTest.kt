package works.merc.keryx.app.platform

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOLERANCE = 1e-4f

private fun assertApprox(expected: Float, actual: Float, message: String = "") =
    assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")

class ScrollIndicatorGeometryTest {
    @Test
    fun noThumbWhenContentFitsTheViewport() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 1000, minLengthFraction = 0.1f))
    }

    @Test
    fun noThumbWhenContentIsSmallerThanTheViewport() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = 500, viewportSize = 1000, minLengthFraction = 0.1f))
    }

    @Test
    fun noThumbBeforeTheFirstMeasurePass() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 0, minLengthFraction = 0.1f))
    }

    @Test
    fun noThumbWhenScrollOffsetIsUnknown() {
        assertNull(scrollIndicatorThumb(scrollOffset = Int.MAX_VALUE, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f))
    }

    @Test
    fun noThumbWhenContentSizeIsUnknown() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = Int.MAX_VALUE, viewportSize = 400, minLengthFraction = 0.1f))
    }

    @Test
    fun noThumbWhenViewportSizeIsUnknown() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = Int.MAX_VALUE, minLengthFraction = 0.1f))
    }

    @Test
    fun thumbAtTheTopStartsAtZero() {
        val thumb = scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox(0f, thumb!!.startFraction)
    }

    @Test
    fun thumbAtTheBottomEndsAtTheTrackEnd() {
        val thumb = scrollIndicatorThumb(scrollOffset = 600, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox(1f, thumb!!.startFraction + thumb.lengthFraction)
    }

    @Test
    fun thumbLengthIsTheVisibleFraction() {
        val thumb = scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox(0.4f, thumb!!.lengthFraction)
    }

    @Test
    fun thumbAtTheMidpointIsCenteredOnTheTrack() {
        val thumb = scrollIndicatorThumb(scrollOffset = 300, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox((1f - thumb!!.lengthFraction) / 2f, thumb.startFraction)
    }

    @Test
    fun thumbLengthIsClampedToTheMinimum() {
        val thumb = scrollIndicatorThumb(scrollOffset = 0, contentSize = 100_000, viewportSize = 100, minLengthFraction = 0.1f)
        assertApprox(0.1f, thumb!!.lengthFraction)
    }

    @Test
    fun aClampedThumbStillEndsExactlyAtTheTrackEnd() {
        val thumb = scrollIndicatorThumb(scrollOffset = 99_900, contentSize = 100_000, viewportSize = 100, minLengthFraction = 0.1f)
        assertApprox(1f, thumb!!.startFraction + thumb.lengthFraction, "clamped thumb must not overshoot the track")
    }

    @Test
    fun noThumbWhenTheMinimumLengthCoversTheWholeTrack() {
        assertNull(scrollIndicatorThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 400, minLengthFraction = 1f))
    }

    @Test
    fun scrollOffsetPastTheRangeIsClamped() {
        val thumb = scrollIndicatorThumb(scrollOffset = 10_000, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox(1f, thumb!!.startFraction + thumb.lengthFraction)
    }

    @Test
    fun negativeScrollOffsetIsClamped() {
        val thumb = scrollIndicatorThumb(scrollOffset = -50, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
        assertApprox(0f, thumb!!.startFraction)
    }

    @Test
    fun thumbStartIsMonotonicInScrollOffset() {
        val range = 600
        var previous = -1f
        for (step in 0..20) {
            val offset = (range * step) / 20
            val thumb = scrollIndicatorThumb(scrollOffset = offset, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
            assertTrue(thumb!!.startFraction >= previous - TOLERANCE, "thumb start regressed at step $step")
            previous = thumb.startFraction
        }
    }

    @Test
    fun articleListAtTheEndFillsTheTrack() {
        // 500 rows of 72px, a 1800px viewport, and a 96px bottom content padding for the
        // navigation bar inset — the same shape ArticleListPane's LazyColumn produces.
        val contentSize = 500 * 72 + 96
        val viewportSize = 1800
        val thumb = scrollIndicatorThumb(
            scrollOffset = contentSize - viewportSize,
            contentSize = contentSize,
            viewportSize = viewportSize,
            minLengthFraction = 0.05f,
        )
        assertApprox(1f, thumb!!.startFraction + thumb.lengthFraction)
    }

    @Test
    fun feedListWithMixedRowHeightsStillProducesAValidThumb() {
        // FeedListPane mixes sticky headers, folder headers, feed rows and dividers of different
        // heights; scrollIndicatorState only ever hands over an estimate, but the thumb must never
        // leave the track regardless of where in the estimate it lands.
        val rowHeights = listOf(56, 12, 40, 56, 56, 12, 40, 56, 56)
        val contentSize = rowHeights.sum()
        val viewportSize = 200
        for (offset in 0..(contentSize - viewportSize) step 7) {
            val thumb = scrollIndicatorThumb(offset, contentSize, viewportSize, minLengthFraction = 0.1f)
            assertTrue(thumb != null, "expected a thumb at offset $offset")
            assertTrue(thumb.startFraction >= 0f, "start below 0 at offset $offset")
            assertTrue(thumb.startFraction + thumb.lengthFraction <= 1f + TOLERANCE, "thumb overshoots at offset $offset")
        }
    }

    @Test
    fun thumbStartPlusLengthNeverExceedsOne() {
        assertEquals(true, listOf(0, 100, 500, 999, 1000).all { offset ->
            val thumb = scrollIndicatorThumb(offset, contentSize = 1000, viewportSize = 400, minLengthFraction = 0.1f)
            thumb == null || thumb.startFraction + thumb.lengthFraction <= 1f + TOLERANCE
        })
    }
}

package works.merc.keryx.app.platform

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val TOLERANCE = 1e-4f
private const val MIN_LENGTH_FRACTION = 0.1f

private fun assertApprox(expected: Float, actual: Float, message: String = "") =
    assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")

class ScrollIndicatorGeometryTest {
    @Test
    fun noLengthWhenContentFitsTheViewport() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 1000, MIN_LENGTH_FRACTION))
    }

    @Test
    fun noLengthWhenContentIsSmallerThanTheViewport() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = 500, viewportSize = 1000, MIN_LENGTH_FRACTION))
    }

    @Test
    fun noLengthBeforeTheFirstMeasurePass() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 0, MIN_LENGTH_FRACTION))
    }

    @Test
    fun noLengthWhenContentSizeIsUnknown() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = Int.MAX_VALUE, viewportSize = 400, MIN_LENGTH_FRACTION))
    }

    @Test
    fun noLengthWhenViewportSizeIsUnknown() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = Int.MAX_VALUE, MIN_LENGTH_FRACTION))
    }

    @Test
    fun noLengthWhenTheMinimumLengthCoversTheWholeTrack() {
        assertApprox(0f, scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, minLengthFraction = 1f))
    }

    @Test
    fun lengthIsTheVisibleFraction() {
        assertApprox(0.4f, scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION))
    }

    @Test
    fun lengthIsClampedToTheMinimum() {
        assertApprox(0.1f, scrollIndicatorLengthFraction(contentSize = 100_000, viewportSize = 100, MIN_LENGTH_FRACTION))
    }

    @Test
    fun startAtTheTopIsZero() {
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        assertApprox(0f, scrollIndicatorStartFraction(scrollOffset = 0, contentSize = 1000, viewportSize = 400, length))
    }

    @Test
    fun thumbAtTheBottomEndsAtTheTrackEnd() {
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        val start = scrollIndicatorStartFraction(scrollOffset = 600, contentSize = 1000, viewportSize = 400, length)
        assertApprox(1f, start + length)
    }

    @Test
    fun thumbAtTheMidpointIsCenteredOnTheTrack() {
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        val start = scrollIndicatorStartFraction(scrollOffset = 300, contentSize = 1000, viewportSize = 400, length)
        assertApprox((1f - length) / 2f, start)
    }

    @Test
    fun aClampedThumbStillEndsExactlyAtTheTrackEnd() {
        val length = scrollIndicatorLengthFraction(contentSize = 100_000, viewportSize = 100, MIN_LENGTH_FRACTION)
        val start = scrollIndicatorStartFraction(scrollOffset = 99_900, contentSize = 100_000, viewportSize = 100, length)
        assertApprox(1f, start + length, "clamped thumb must not overshoot the track")
    }

    @Test
    fun scrollOffsetPastTheRangeIsClamped() {
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        val start = scrollIndicatorStartFraction(scrollOffset = 10_000, contentSize = 1000, viewportSize = 400, length)
        assertApprox(1f, start + length)
    }

    @Test
    fun negativeScrollOffsetIsClamped() {
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        assertApprox(0f, scrollIndicatorStartFraction(scrollOffset = -50, contentSize = 1000, viewportSize = 400, length))
    }

    @Test
    fun anUnknownScrollOffsetClampsToTheTrackEnd() {
        // Int.MAX_VALUE (ScrollIndicatorState's own "not known yet" sentinel) divided by any
        // positive range is a huge ratio, which coerceIn(0f, 1f) simply clamps to 1 — the thumb
        // lands at the track's end rather than at a wrong mid-track position. In practice this
        // sentinel is never seen here without contentSize/viewportSize being unmeasured too (which
        // scrollIndicatorLengthFraction already turns into a zero length upstream), so this pins
        // the fallback behavior rather than a reachable production case.
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        val start = scrollIndicatorStartFraction(scrollOffset = Int.MAX_VALUE, contentSize = 1000, viewportSize = 400, length)
        assertApprox(1f, start + length)
    }

    @Test
    fun thumbStartIsMonotonicInScrollOffset() {
        val range = 600
        val length = scrollIndicatorLengthFraction(contentSize = 1000, viewportSize = 400, MIN_LENGTH_FRACTION)
        var previous = -1f
        for (step in 0..20) {
            val offset = (range * step) / 20
            val start = scrollIndicatorStartFraction(offset, contentSize = 1000, viewportSize = 400, length)
            assertTrue(start >= previous - TOLERANCE, "thumb start regressed at step $step")
            previous = start
        }
    }

    @Test
    fun articleListAtTheEndFillsTheTrack() {
        // 500 rows of 72px, a 1800px viewport, and a 96px bottom content padding for the
        // navigation bar inset — the same shape ArticleListPane's LazyColumn produces.
        val contentSize = 500 * 72 + 96
        val viewportSize = 1800
        val length = scrollIndicatorLengthFraction(contentSize, viewportSize, minLengthFraction = 0.05f)
        val start = scrollIndicatorStartFraction(contentSize - viewportSize, contentSize, viewportSize, length)
        assertApprox(1f, start + length)
    }

    @Test
    fun feedListWithMixedRowHeightsStillProducesAValidThumb() {
        // FeedListPane mixes sticky headers, folder headers, feed rows and dividers of different
        // heights; scrollIndicatorState only ever hands over an estimate, but the thumb must never
        // leave the track regardless of where in the estimate it lands.
        val rowHeights = listOf(56, 12, 40, 56, 56, 12, 40, 56, 56)
        val contentSize = rowHeights.sum()
        val viewportSize = 200
        val length = scrollIndicatorLengthFraction(contentSize, viewportSize, MIN_LENGTH_FRACTION)
        assertTrue(length > 0f, "expected a drawable thumb for this content/viewport shape")
        for (offset in 0..(contentSize - viewportSize) step 7) {
            val start = scrollIndicatorStartFraction(offset, contentSize, viewportSize, length)
            assertTrue(start >= 0f, "start below 0 at offset $offset")
            assertTrue(start + length <= 1f + TOLERANCE, "thumb overshoots at offset $offset")
        }
    }

    @Test
    fun minLengthFractionIsOneWhenTheTrackIsNotYetMeasured() {
        assertApprox(1f, minLengthFraction(minLengthPx = 32f, trackLengthPx = 0f))
    }

    @Test
    fun minLengthFractionIsOneWhenTheFloorExceedsTheTrack() {
        assertApprox(1f, minLengthFraction(minLengthPx = 500f, trackLengthPx = 100f))
    }

    @Test
    fun minLengthFractionIsTheFloorOverTheTrackLength() {
        assertApprox(0.32f, minLengthFraction(minLengthPx = 32f, trackLengthPx = 100f))
    }
}

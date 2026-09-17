package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleSwipeNavTest {

    private val width = 1000f
    private val flingThreshold = 800f

    // --- swipeDragOffset ---

    @Test
    fun swipeDragOffsetFollowsTheFingerOneToOneWhenMovable() {
        assertEquals(120f, swipeDragOffset(rawDragPx = 120f, movable = true, maxRubberBandPx = 48f))
        assertEquals(-500f, swipeDragOffset(rawDragPx = -500f, movable = true, maxRubberBandPx = 48f))
    }

    @Test
    fun swipeDragOffsetDampsAndCapsWhenNotMovable() {
        // Damped by the fixed divisor (4) below the cap.
        assertEquals(25f, swipeDragOffset(rawDragPx = 100f, movable = false, maxRubberBandPx = 48f))
        // A far drag still clamps at the cap rather than growing unbounded.
        assertEquals(48f, swipeDragOffset(rawDragPx = 10_000f, movable = false, maxRubberBandPx = 48f))
        assertEquals(-48f, swipeDragOffset(rawDragPx = -10_000f, movable = false, maxRubberBandPx = 48f))
    }

    // --- resolveSwipeOutcome: distance threshold ---

    @Test
    fun resolveSwipeOutcomeCommitsNextPastTheDistanceThreshold() {
        val outcome = resolveSwipeOutcome(
            offsetPx = -310f,
            velocityPxPerSec = 0f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Next, outcome)
    }

    @Test
    fun resolveSwipeOutcomeCommitsPreviousPastTheDistanceThreshold() {
        val outcome = resolveSwipeOutcome(
            offsetPx = 310f,
            velocityPxPerSec = 0f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Previous, outcome)
    }

    @Test
    fun resolveSwipeOutcomeCancelsBelowBothTheDistanceAndVelocityThresholds() {
        val outcome = resolveSwipeOutcome(
            offsetPx = -100f,
            velocityPxPerSec = -50f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Cancel, outcome)
    }

    // --- resolveSwipeOutcome: velocity (fling) threshold ---

    @Test
    fun resolveSwipeOutcomeCommitsNextOnAFlingEvenBelowTheDistanceThreshold() {
        val outcome = resolveSwipeOutcome(
            offsetPx = -50f,
            velocityPxPerSec = -900f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Next, outcome)
    }

    @Test
    fun resolveSwipeOutcomeCommitsPreviousOnAFlingEvenBelowTheDistanceThreshold() {
        val outcome = resolveSwipeOutcome(
            offsetPx = 50f,
            velocityPxPerSec = 900f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Previous, outcome)
    }

    // --- resolveSwipeOutcome: end-of-list guard ---

    @Test
    fun resolveSwipeOutcomeCancelsTowardsNextWhenThereIsNoNextArticle() {
        val outcome = resolveSwipeOutcome(
            offsetPx = -900f,
            velocityPxPerSec = -2000f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = false,
        )
        assertEquals(ArticleSwipeOutcome.Cancel, outcome)
    }

    @Test
    fun resolveSwipeOutcomeCancelsTowardsPreviousWhenThereIsNoPreviousArticle() {
        val outcome = resolveSwipeOutcome(
            offsetPx = 900f,
            velocityPxPerSec = 2000f,
            widthPx = width,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = false,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Cancel, outcome)
    }

    @Test
    fun resolveSwipeOutcomeCancelsWhenWidthIsNotYetKnown() {
        val outcome = resolveSwipeOutcome(
            offsetPx = -900f,
            velocityPxPerSec = -2000f,
            widthPx = 0f,
            flingVelocityPxPerSec = flingThreshold,
            canPrevious = true,
            canNext = true,
        )
        assertEquals(ArticleSwipeOutcome.Cancel, outcome)
    }

    // --- swipeArmsHorizontally ---

    /** The platform touch slop these cases are measured against; any plausible value works. */
    private val touchSlop = 20f

    @Test
    fun swipeArmsHorizontallyRequiresClearingTheTouchSlop() {
        // Horizontal enough by ratio, but not yet a drag at all.
        assertFalse(swipeArmsHorizontally(dx = touchSlop, dy = 0f, touchSlop = touchSlop))
        assertTrue(swipeArmsHorizontally(dx = touchSlop + 1f, dy = 0f, touchSlop = touchSlop))
    }

    @Test
    fun swipeArmsHorizontallyRejectsTheDiagonalThatTheOldRuleAccepted() {
        // The previous gate was `abs(dx) > abs(dy)`, which this clears (30 > 25) — exactly the
        // near-45-degree flick a user means as a scroll. The ratio gate needs 25 * 1.5 = 37.5.
        assertFalse(swipeArmsHorizontally(dx = 30f, dy = 25f, touchSlop = touchSlop))
        assertTrue(swipeArmsHorizontally(dx = 38f, dy = 25f, touchSlop = touchSlop))
    }

    @Test
    fun swipeArmsHorizontallyAcceptsAClearlyHorizontalDragWithSomeWobble() {
        assertTrue(swipeArmsHorizontally(dx = 100f, dy = 20f, touchSlop = touchSlop))
    }

    @Test
    fun swipeArmsHorizontallyIgnoresDirection() {
        // Towards the previous article is the same gesture mirrored; both axes are magnitudes.
        assertTrue(swipeArmsHorizontally(dx = -100f, dy = -20f, touchSlop = touchSlop))
        assertFalse(swipeArmsHorizontally(dx = -30f, dy = 25f, touchSlop = touchSlop))
    }

    // --- swipeLockedOut ---

    @Test
    fun swipeLockedOutIsFalseBeforeAnyVerticalGesture() {
        assertFalse(swipeLockedOut(nowMillis = 1_000L, lastVerticalEndMillis = null, lockoutMs = 400L))
    }

    @Test
    fun swipeLockedOutCoversTheWindowAndNotItsFarEdge() {
        assertTrue(swipeLockedOut(nowMillis = 1_000L, lastVerticalEndMillis = 1_000L, lockoutMs = 400L))
        assertTrue(swipeLockedOut(nowMillis = 1_399L, lastVerticalEndMillis = 1_000L, lockoutMs = 400L))
        // The window is half-open: a gesture exactly one window later is free again.
        assertFalse(swipeLockedOut(nowMillis = 1_400L, lastVerticalEndMillis = 1_000L, lockoutMs = 400L))
        assertFalse(swipeLockedOut(nowMillis = 5_000L, lastVerticalEndMillis = 1_000L, lockoutMs = 400L))
    }

    @Test
    fun swipeLockedOutTreatsANegativeSpanAsOutsideTheWindow() {
        // Can only happen if the two times came from different clocks; refusing the swipe on a
        // value that can't be reasoned about would strand the gesture for no defensible reason.
        assertFalse(swipeLockedOut(nowMillis = 900L, lastVerticalEndMillis = 1_000L, lockoutMs = 400L))
    }
}

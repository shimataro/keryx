package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

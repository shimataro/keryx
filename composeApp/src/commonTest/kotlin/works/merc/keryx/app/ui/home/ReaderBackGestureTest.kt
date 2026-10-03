package works.merc.keryx.app.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.platform.BackProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reader back preview's pure state machine and pane offsets, plus [ReaderBackController]
 * driving them from a progress flow (the platform's own predictive back callback is Android-only
 * and checked on a device).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBackGestureTest {

    private val left = BackProgress(0.4f, fromRightEdge = false)
    private val right = BackProgress(0.4f, fromRightEdge = true)

    // --- state machine ---

    @Test
    fun progressStartsTrackingFromIdle() {
        val state = ReaderBackGesture.Idle.onProgress(left)
        assertEquals(ReaderBackGesture.Tracking(0.4f, fromRightEdge = false), state)
        assertTrue(state.showsListBehind)
        assertTrue(state.acceptsNewGesture)
    }

    @Test
    fun progressUpdatesTrackingAndClampsToUnitRange() {
        val state = ReaderBackGesture.Idle.onProgress(left).onProgress(BackProgress(1.7f, fromRightEdge = true))
        assertEquals(ReaderBackGesture.Tracking(1f, fromRightEdge = true), state)
    }

    @Test
    fun releasedTrackingCommitsOrCancelsThroughASettleAnimation() {
        val tracking = ReaderBackGesture.Idle.onProgress(right)
        assertEquals(
            ReaderBackStep(ReaderBackGesture.Settling(true, true, 0.4f), ReaderBackEffect.AnimateCommit),
            tracking.onRelease(commit = true),
        )
        assertEquals(
            ReaderBackStep(ReaderBackGesture.Settling(false, true, 0.4f), ReaderBackEffect.AnimateCancel),
            tracking.onRelease(commit = false),
        )
    }

    @Test
    fun aBackWithNoPreviewPopsAtOnce() {
        assertEquals(ReaderBackStep(ReaderBackGesture.Idle, ReaderBackEffect.PopNow), ReaderBackGesture.Idle.onRelease(true))
        assertEquals(ReaderBackStep(ReaderBackGesture.Idle, ReaderBackEffect.None), ReaderBackGesture.Idle.onRelease(false))
    }

    @Test
    fun aCommitInFlightIgnoresAnyFurtherGesture() {
        val committing = ReaderBackGesture.Settling(commit = true, fromRightEdge = false, progress = 0.6f)
        assertFalse(committing.acceptsNewGesture)
        assertEquals(committing, committing.onProgress(right))
        assertEquals(ReaderBackStep(committing, ReaderBackEffect.None), committing.onRelease(true))
        assertEquals(ReaderBackStep(committing, ReaderBackEffect.None), committing.onRelease(false))
    }

    @Test
    fun aNewGestureTakesOverFromACancelAnimation() {
        val cancelling = ReaderBackGesture.Settling(commit = false, fromRightEdge = false, progress = 0.2f)
        assertTrue(cancelling.acceptsNewGesture)
        assertEquals(ReaderBackGesture.Tracking(0.4f, fromRightEdge = true), cancelling.onProgress(right))
        // A plain (unpreviewed) back during the cancel animation still pops.
        assertEquals(ReaderBackStep(ReaderBackGesture.Idle, ReaderBackEffect.PopNow), cancelling.onRelease(true))
    }

    @Test
    fun settleFramesAndCompletionOnlyApplyWhileSettling() {
        val settling = ReaderBackGesture.Settling(commit = false, fromRightEdge = true, progress = 0.5f)
        assertEquals(settling.copy(progress = 0.25f), settling.onSettleFrame(0.25f))
        assertEquals(ReaderBackGesture.Idle, settling.onSettled())

        val tracking = ReaderBackGesture.Tracking(0.3f, fromRightEdge = false)
        assertEquals(tracking, tracking.onSettleFrame(0f))
        assertEquals(tracking, tracking.onSettled())
        assertEquals(ReaderBackGesture.Idle, ReaderBackGesture.Idle.onSettled())
        assertFalse(ReaderBackGesture.Idle.showsListBehind)
    }

    // --- offsets ---

    @Test
    fun readerFollowsTheSwipeDirectionAndLeavesFullyAtOne() {
        assertEquals(0f, readerBackReaderOffset(0f, fromRightEdge = false))
        assertEquals(0.5f, readerBackReaderOffset(0.5f, fromRightEdge = false))
        assertEquals(-0.5f, readerBackReaderOffset(0.5f, fromRightEdge = true))
        assertEquals(1f, readerBackReaderOffset(1f, fromRightEdge = false))
        assertEquals(-1f, readerBackReaderOffset(2f, fromRightEdge = true))
    }

    @Test
    fun listParallaxStartsOnTheUncoveredSideAndSettlesAtZero() {
        assertEquals(-READER_BACK_LIST_PARALLAX_FRACTION, readerBackListOffset(0f, fromRightEdge = false))
        assertEquals(READER_BACK_LIST_PARALLAX_FRACTION, readerBackListOffset(0f, fromRightEdge = true))
        assertEquals(-READER_BACK_LIST_PARALLAX_FRACTION / 2, readerBackListOffset(0.5f, fromRightEdge = false))
        assertEquals(0f, readerBackListOffset(1f, fromRightEdge = true))
    }

    @Test
    fun theListBehindTheReaderNeverReplaysTheReturnFlash() {
        assertEquals(0, readerBackListPulse(3, listBehind = true))
        assertEquals(3, readerBackListPulse(3, listBehind = false))
    }

    // --- controller ---

    private class Recorder {
        var commits = 0
        val frames = mutableListOf<Pair<Float, Float>>()
    }

    private fun controller(scope: CoroutineScope, recorder: Recorder) =
        ReaderBackController(scope, onCommit = { recorder.commits++ }) { from, to, onFrame ->
            recorder.frames += from to to
            onFrame(to)
        }

    @Test
    fun aPreviewedCommitAnimatesOutThenPopsOnce() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val controller = controller(this, recorder)

        controller.handle(flowOf(BackProgress(0.2f, false), BackProgress(0.45f, false)))

        assertEquals(listOf(0.45f to 1f), recorder.frames)
        assertEquals(1, recorder.commits)
        assertEquals(ReaderBackGesture.Idle, controller.gesture)
    }

    @Test
    fun aBackWithoutProgressPopsImmediatelyWithoutAnimating() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val controller = controller(this, recorder)

        controller.handle(emptyFlow())

        assertTrue(recorder.frames.isEmpty())
        assertEquals(1, recorder.commits)
        assertEquals(ReaderBackGesture.Idle, controller.gesture)
    }

    @Test
    fun aCancelledGestureAnimatesBackAndRethrows() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val controller = controller(this, recorder)
        val events = Channel<BackProgress>(Channel.UNLIMITED)
        val rethrown = CompletableDeferred<Throwable?>()

        val gesture = launch {
            try {
                controller.handle(events.consumeAsFlow())
                rethrown.complete(null)
            } catch (e: Throwable) {
                rethrown.complete(e)
                throw e
            }
        }
        events.send(BackProgress(0.3f, fromRightEdge = true))
        assertIs<ReaderBackGesture.Tracking>(controller.gesture)

        gesture.cancel()

        assertIs<kotlinx.coroutines.CancellationException>(rethrown.await())
        assertEquals(listOf(0.3f to 0f), recorder.frames)
        assertEquals(0, recorder.commits)
        assertEquals(ReaderBackGesture.Idle, controller.gesture)
    }

    @Test
    fun aSecondBackDuringTheCommitAnimationDoesNotPopAgain() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        val release = CompletableDeferred<Unit>()
        val controller = ReaderBackController(this, onCommit = { recorder.commits++ }) { _, to, onFrame ->
            release.await()
            onFrame(to)
        }

        controller.handle(flowOf(BackProgress(0.5f, false)))
        assertEquals(ReaderBackGesture.Settling(true, false, 0.5f), controller.gesture)

        // A gesture (previewed or not) arriving while the reader is still animating out.
        controller.handle(flowOf(BackProgress(0.2f, true)))
        controller.handle(emptyFlow())
        assertEquals(0, recorder.commits)

        release.complete(Unit)
        assertEquals(1, recorder.commits)
        assertEquals(ReaderBackGesture.Idle, controller.gesture)
    }
}

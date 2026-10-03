package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import works.merc.keryx.app.platform.BackProgress

/**
 * How far the article list sits off its resting position at the very start of a reader back
 * preview, as a fraction of the pane width. It slides the rest of the way in as the reader slides
 * out (a parallax), reaching `0` exactly when the reader has left the screen.
 */
internal const val READER_BACK_LIST_PARALLAX_FRACTION = 0.3f

/** Duration of the animation that finishes (or undoes) a released reader back gesture. */
internal const val READER_BACK_SETTLE_MILLIS = 250

/**
 * The predictive back gesture from the reader to the article list at [PaneLayout.Single] (the one
 * place `homeBackAction` resolves [HomeBackAction.PopPane]), as a pure state machine.
 *
 * - [Idle]: no gesture. Only the reader is composed.
 * - [Tracking]: the finger is down and the OS is reporting progress; the article list is composed
 *   behind the reader, which follows the gesture.
 * - [Settling]: the gesture was released and the screen is animating to its outcome — the reader
 *   leaving ([Settling.commit], after which the pane is actually popped) or coming back.
 *
 * The visual progress lives in the state itself ([visualProgress]) so a single value drives both
 * panes' offsets ([readerBackReaderOffset]/[readerBackListOffset]).
 */
sealed interface ReaderBackGesture {
    data object Idle : ReaderBackGesture
    data class Tracking(val progress: Float, val fromRightEdge: Boolean) : ReaderBackGesture
    data class Settling(val commit: Boolean, val fromRightEdge: Boolean, val progress: Float) : ReaderBackGesture
}

/** What a released gesture asks the caller to do, alongside the next [ReaderBackGesture]. */
enum class ReaderBackEffect {
    /** Nothing to do. */
    None,

    /** Pop the pane right away: a back with no preview (3-button navigation, older Android). */
    PopNow,

    /** Animate the reader off screen, then pop the pane. */
    AnimateCommit,

    /** Animate the reader back into place, then drop the article list behind it. */
    AnimateCancel,
}

/** The next [ReaderBackGesture] plus the [ReaderBackEffect] a release produced. */
data class ReaderBackStep(val state: ReaderBackGesture, val effect: ReaderBackEffect)

/** Whether the article list must be composed behind the reader right now. */
val ReaderBackGesture.showsListBehind: Boolean get() = this !is ReaderBackGesture.Idle

/**
 * Whether a new back action may start. Not while a committed gesture is still animating out:
 * the pop is already on its way, and a second one would land on the article list instead.
 */
val ReaderBackGesture.acceptsNewGesture: Boolean
    get() = !(this is ReaderBackGesture.Settling && commit)

/** The progress the panes are drawn at: `0` = reader fully in place, `1` = reader gone. */
val ReaderBackGesture.visualProgress: Float
    get() = when (this) {
        ReaderBackGesture.Idle -> 0f
        is ReaderBackGesture.Tracking -> progress
        is ReaderBackGesture.Settling -> progress
    }

/** Which edge the gesture started from; `false` (left) when there is none. */
val ReaderBackGesture.fromRightEdge: Boolean
    get() = when (this) {
        ReaderBackGesture.Idle -> false
        is ReaderBackGesture.Tracking -> fromRightEdge
        is ReaderBackGesture.Settling -> fromRightEdge
    }

/**
 * Applies one progress [event]. Starts (or continues) [ReaderBackGesture.Tracking]; a new gesture
 * also takes over from a cancel animation still in flight. Ignored while a commit is animating out
 * (see [acceptsNewGesture]).
 */
fun ReaderBackGesture.onProgress(event: BackProgress): ReaderBackGesture =
    if (!acceptsNewGesture) this
    else ReaderBackGesture.Tracking(event.progress.coerceIn(0f, 1f), event.fromRightEdge)

/**
 * The gesture ended — committed ([commit] = the progress flow completed) or cancelled.
 *
 * A gesture that never reported progress leaves the state untouched by [onProgress], so a commit
 * from [ReaderBackGesture.Idle] (or from a cancel animation still settling) is a back with nothing
 * previewed and pops at once, exactly like a plain back press.
 */
fun ReaderBackGesture.onRelease(commit: Boolean): ReaderBackStep = when (this) {
    is ReaderBackGesture.Tracking ->
        if (commit) ReaderBackStep(ReaderBackGesture.Settling(true, fromRightEdge, progress), ReaderBackEffect.AnimateCommit)
        else ReaderBackStep(ReaderBackGesture.Settling(false, fromRightEdge, progress), ReaderBackEffect.AnimateCancel)
    is ReaderBackGesture.Settling ->
        if (this.commit || !commit) ReaderBackStep(this, ReaderBackEffect.None)
        else ReaderBackStep(ReaderBackGesture.Idle, ReaderBackEffect.PopNow)
    ReaderBackGesture.Idle ->
        ReaderBackStep(this, if (commit) ReaderBackEffect.PopNow else ReaderBackEffect.None)
}

/** One frame of the settle animation; only applies while [ReaderBackGesture.Settling]. */
fun ReaderBackGesture.onSettleFrame(progress: Float): ReaderBackGesture =
    if (this is ReaderBackGesture.Settling) copy(progress = progress.coerceIn(0f, 1f)) else this

/** The settle animation finished; a gesture that took over in the meantime is left alone. */
fun ReaderBackGesture.onSettled(): ReaderBackGesture =
    if (this is ReaderBackGesture.Settling) ReaderBackGesture.Idle else this

/**
 * The reader's horizontal offset as a fraction of the pane width (positive = rightwards) at
 * [progress]: it moves with the swipe — rightwards for a swipe from the left edge, leftwards for
 * one from the right edge — and is fully off screen at `1`.
 */
fun readerBackReaderOffset(progress: Float, fromRightEdge: Boolean): Float =
    (if (fromRightEdge) -1f else 1f) * progress.coerceIn(0f, 1f)

/**
 * The article list's horizontal offset behind the reader, as a fraction of the pane width: it
 * starts [READER_BACK_LIST_PARALLAX_FRACTION] away on the side the reader is uncovering and
 * reaches `0` when the reader has left (`progress == 1`).
 */
fun readerBackListOffset(progress: Float, fromRightEdge: Boolean): Float =
    (if (fromRightEdge) 1f else -1f) * (1f - progress.coerceIn(0f, 1f)) * READER_BACK_LIST_PARALLAX_FRACTION

/**
 * The article list's ripple pulse while it's composed only as a preview behind the reader: `0`,
 * so mounting it for a preview never replays the "you were here" flash. The flash plays once, on
 * commit, when `goBack()` bumps [pulse] in the same frame the gesture returns to Idle.
 *
 * @param listBehind [ReaderBackGesture.showsListBehind] for the current gesture.
 */
fun readerBackListPulse(pulse: Int, listBehind: Boolean): Int = if (listBehind) 0 else pulse

/**
 * Runs [ReaderBackGesture] for `HomeScreen`'s `PredictiveBackHandler`: feeds it the gesture's
 * progress, animates the release in [scope], and calls [onCommit] — `HomeScreen`'s own `goBack()`,
 * the one implementation of popping the pane — once a commit has finished animating, or at once
 * when there was nothing to preview.
 *
 * @param settleAnimation Animates from one progress to another, reporting each frame. Replaced
 *   in tests, which have no frame clock.
 */
@Stable
internal class ReaderBackController(
    private val scope: CoroutineScope,
    private val onCommit: () -> Unit,
    private val settleAnimation: suspend (from: Float, to: Float, onFrame: (Float) -> Unit) -> Unit = ::animateSettle,
) {
    var gesture: ReaderBackGesture by mutableStateOf(ReaderBackGesture.Idle)
        private set

    private var settleJob: Job? = null

    /** The body of one back action (see [works.merc.keryx.app.platform.PredictiveBackHandler]). */
    suspend fun handle(progress: Flow<BackProgress>) {
        try {
            progress.collect { event ->
                if (!gesture.acceptsNewGesture) return@collect
                // A new gesture taking over from a cancel animation: stop that animation first.
                if (gesture is ReaderBackGesture.Settling) settleJob?.cancel()
                gesture = gesture.onProgress(event)
            }
        } catch (e: CancellationException) {
            release(commit = false)
            throw e
        }
        release(commit = true)
    }

    private fun release(commit: Boolean) {
        val step = gesture.onRelease(commit)
        gesture = step.state
        when (step.effect) {
            ReaderBackEffect.None -> {}
            ReaderBackEffect.PopNow -> {
                settleJob?.cancel()
                onCommit()
            }
            ReaderBackEffect.AnimateCommit -> settle(target = 1f, commit = true)
            ReaderBackEffect.AnimateCancel -> settle(target = 0f, commit = false)
        }
    }

    private fun settle(target: Float, commit: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            settleAnimation(gesture.visualProgress, target) { gesture = gesture.onSettleFrame(it) }
            // Pop before returning to Idle, in the same frame: the list behind is already the one
            // the pop lands on, so it stays composed throughout (no remount).
            if (commit) onCommit()
            gesture = gesture.onSettled()
        }
    }
}

private suspend fun animateSettle(from: Float, to: Float, onFrame: (Float) -> Unit) {
    animate(from, to, animationSpec = tween(READER_BACK_SETTLE_MILLIS, easing = FastOutSlowInEasing)) { value, _ ->
        onFrame(value)
    }
}

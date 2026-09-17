package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_next
import works.merc.keryx.app.resources.article_prev

/**
 * Fraction of the pane's width a drag must cross before it resolves to [ArticleSwipeOutcome.Next]/
 * [ArticleSwipeOutcome.Previous] rather than snapping back — independent of the fling-velocity
 * threshold, which can resolve the same outcome from a much shorter drag.
 */
private const val SWIPE_COMMIT_FRACTION = 0.3f

/** A release velocity faster than this (in either direction) commits regardless of how far the
 * drag has travelled. */
private const val SWIPE_FLING_VELOCITY_DP_PER_S = 800f

/**
 * Minimum ratio of horizontal to vertical travel before a drag is claimed as a horizontal swipe.
 *
 * This used to be an implicit `1.0` (`abs(x) > abs(y)`) — a +/-45 degree cone, which a diagonal
 * flick meant as a vertical scroll clears about as often as not, sending the reader to another
 * article when the user only wanted to scroll further down this one. `1.5f` narrows it to roughly
 * +/-34 degrees.
 */
private const val SWIPE_DIRECTION_RATIO = 1.5f

/**
 * How long after a vertical gesture ends a new gesture is refused as a horizontal swipe.
 *
 * Covers the other half of the same misfire: while flicking down through a long article, one flick
 * in the run comes out diagonal. A gesture starting this soon after a vertical one is far more
 * likely to be the next scroll in that run than a deliberate page turn. The window is *extended* by
 * every vertical gesture, including ones that were themselves refused by it, so a run of scrolls
 * faster than this interval never opens a gap between flicks.
 */
private const val SWIPE_AFTER_VERTICAL_LOCKOUT_MS = 400L

/** Divisor applied to the raw drag distance once the gesture points towards a direction with
 * nothing to move to, so the content still visibly resists rather than either refusing to move at
 * all or sliding as freely as an in-bounds drag. */
private const val SWIPE_RUBBER_BAND_DIVISOR = 4f

/** Furthest an out-of-bounds drag may pull the content, regardless of how far the finger travels. */
private const val SWIPE_RUBBER_BAND_MAX_DP = 48f

/** The sample count Compose's [VelocityTracker] (`Lsq2` strategy, its default) requires before
 * `calculateVelocity()` returns anything but `0f` — see [ArticleSwipeController.resolveDragVelocityPxPerSec]. */
private const val LSQ2_MIN_SAMPLE_COUNT = 3

/**
 * Resolution of a completed horizontal drag on the article reader.
 */
internal enum class ArticleSwipeOutcome { Next, Previous, Cancel }

/**
 * Decides what a completed horizontal drag on the article reader should do, given how far and how
 * fast it travelled.
 *
 * A drag towards a direction with nothing to move to ([canNext]/[canPrevious] gate their own
 * direction only) always resolves to [ArticleSwipeOutcome.Cancel], regardless of distance or
 * velocity — see [swipeDragOffset] for how that direction is made to visibly resist while dragging.
 *
 * @param offsetPx The drag's net horizontal offset in pixels at release. Negative moves content
 *   left (towards the next article); positive moves it right (towards the previous one).
 * @param velocityPxPerSec The drag's horizontal velocity at release, same sign convention.
 * @param widthPx The reader pane's width in pixels, against which [SWIPE_COMMIT_FRACTION] is measured.
 * @param flingVelocityPxPerSec The release-velocity threshold ([SWIPE_FLING_VELOCITY_DP_PER_S]
 *   converted to px by the caller) that commits regardless of distance.
 * @param canPrevious Whether there is a preceding article to move to (`HomeViewModel.canSelectPrevious`).
 * @param canNext Whether there is a following article to move to (`HomeViewModel.canSelectNext`).
 */
internal fun resolveSwipeOutcome(
    offsetPx: Float,
    velocityPxPerSec: Float,
    widthPx: Float,
    flingVelocityPxPerSec: Float,
    canPrevious: Boolean,
    canNext: Boolean,
): ArticleSwipeOutcome {
    if (widthPx <= 0f) return ArticleSwipeOutcome.Cancel
    val commitPx = widthPx * SWIPE_COMMIT_FRACTION
    val towardsNext = offsetPx < 0f && (-offsetPx >= commitPx || -velocityPxPerSec >= flingVelocityPxPerSec)
    val towardsPrevious = offsetPx > 0f && (offsetPx >= commitPx || velocityPxPerSec >= flingVelocityPxPerSec)
    return when {
        towardsNext && canNext -> ArticleSwipeOutcome.Next
        towardsPrevious && canPrevious -> ArticleSwipeOutcome.Previous
        else -> ArticleSwipeOutcome.Cancel
    }
}

/**
 * Whether a drag's cumulative travel is horizontal enough to be claimed as a swipe.
 *
 * Both conditions matter: the touch slop keeps a stationary press from ever arming, and
 * [SWIPE_DIRECTION_RATIO] keeps a diagonal flick — the common misfire, a vertical scroll that
 * drifted sideways — from counting as horizontal.
 *
 * @param dx The drag's cumulative horizontal travel, in pixels.
 * @param dy The drag's cumulative vertical travel, in pixels.
 * @param touchSlop The platform's own touch slop, in pixels.
 */
internal fun swipeArmsHorizontally(dx: Float, dy: Float, touchSlop: Float): Boolean =
    abs(dx) > touchSlop && abs(dx) > abs(dy) * SWIPE_DIRECTION_RATIO

/**
 * Whether a gesture beginning at [nowMillis] still falls inside the lockout window a preceding
 * vertical gesture opened — see [SWIPE_AFTER_VERTICAL_LOCKOUT_MS].
 *
 * @param nowMillis The new gesture's own down-event time, on the same clock as
 *   [lastVerticalEndMillis] (`PointerInputChange.uptimeMillis`).
 * @param lastVerticalEndMillis When the last vertical gesture ended, or `null` when there has not
 *   been one yet — nothing to lock out against.
 * @param lockoutMs The width of the window.
 */
internal fun swipeLockedOut(nowMillis: Long, lastVerticalEndMillis: Long?, lockoutMs: Long): Boolean {
    val since = nowMillis - (lastVerticalEndMillis ?: return false)
    // A negative span would mean the two came from different clocks; treat it as "not in the
    // window" rather than locking the gesture out on a value that can't be reasoned about.
    return since in 0 until lockoutMs
}

/**
 * Maps a raw cumulative drag distance to the content's actual on-screen offset, applying
 * rubber-band resistance once the drag points towards a direction with nothing to move to.
 *
 * @param rawDragPx The raw, undamped cumulative drag distance in pixels (negative = towards next,
 *   positive = towards previous).
 * @param movable Whether the direction [rawDragPx] currently points towards has somewhere to go —
 *   `true` follows the finger 1:1; `false` (an end of the list) heavily damps and caps the offset,
 *   so the gesture visibly "gives" rather than either doing nothing or moving as freely as a real
 *   page turn.
 * @param maxRubberBandPx The maximum offset a damped drag may reach, in pixels
 *   ([SWIPE_RUBBER_BAND_MAX_DP] converted to px by the caller).
 */
internal fun swipeDragOffset(rawDragPx: Float, movable: Boolean, maxRubberBandPx: Float): Float {
    if (movable) return rawDragPx
    return (rawDragPx / SWIPE_RUBBER_BAND_DIVISOR).coerceIn(-maxRubberBandPx, maxRubberBandPx)
}

/**
 * Drives the article reader's swipe-to-navigate offset: a single [Animatable] that a drag gesture
 * ([articleSwipeNavigation]) writes to directly while dragging, and that a commit/cancel resolution
 * animates to its resting or off-pane position afterwards.
 *
 * Only ever constructed by [rememberArticleSwipeController] — the density-derived px thresholds in
 * the constructor are computed there from [LocalDensity], not recomputed per gesture.
 */
@Stable
internal class ArticleSwipeController(
    private val scope: CoroutineScope,
    density: Density,
    private val pagerState: PagerState,
    private val canSelectNext: () -> Boolean,
    private val canSelectPrevious: () -> Boolean,
) {
    /**
     * The reader's own horizontal translation, used **only** for the rubber band at either end of
     * the list. While there is somewhere to go, the movement on screen is the pager's own scroll
     * (see [onDrag]) and this stays at 0 — the pager cannot itself move past its first or last
     * page, so a drag that points at nothing needs its own way to give.
     */
    val offset = Animatable(0f)

    /** The reader pane's own width, kept in sync by `Modifier.onSizeChanged` at the call site. */
    var widthPx by mutableFloatStateOf(0f)

    private val maxRubberBandPx = with(density) { SWIPE_RUBBER_BAND_MAX_DP.dp.toPx() }
    private val flingVelocityPxPerSec = with(density) { SWIPE_FLING_VELOCITY_DP_PER_S.dp.toPx() }

    /**
     * `true` from the moment a drag is claimed until its settle (or page turn) animation has
     * finished. The pane's selection→page synchronization reads this rather than
     * `PagerState.isScrollInProgress`: the user owns the pager for the whole gesture, including the
     * gap between releasing the finger and the animation starting, and a list update arriving in
     * that gap must not yank the pager somewhere else.
     *
     * Only cleared by the settle job whose [gestureGeneration] is still current — `Job.cancel()`
     * (see [onDragStart]) does not run the cancelled job's `finally` synchronously, so without that
     * check a settle from an *already-abandoned* gesture could still flip this back to `false` after
     * the new gesture has set it `true`, letting a list update steal the pager mid-drag.
     */
    var gestureInProgress by mutableStateOf(false)
        private set

    /** Bumped on every [onDragStart], so a settle job cancelled by a newer gesture can tell it is
     * no longer the one that owns [gestureInProgress] — see that property's own KDoc. */
    private var gestureGeneration = 0L

    private var dragTotalPx = 0f
    private val velocityTracker = VelocityTracker()

    /**
     * The page the current gesture started from, and the only anchor its resolution is measured
     * against. Deliberately not `PagerState.settledPage`/`currentPage`: `currentPage` flips at the
     * halfway mark, so a cancelled drag that had travelled more than half a page would otherwise
     * settle *onto* the neighbour it was supposed to abandon.
     */
    private var anchorPage = 0

    /**
     * The drag's single scroll session and the channel feeding it.
     *
     * One `PagerState.scroll { }` per gesture, not one `scrollBy` per pointer sample.
     * `ScrollableState` mutations go through a `MutatorMutex`, which **cancels** the mutation in
     * flight rather than queueing behind it, so a per-sample call would drop the deltas of every
     * sample that arrives while the previous one is still running — and because a pager scrolls by
     * a relative delta (unlike the absolute `Animatable.snapTo` this replaced), a dropped delta is
     * lost for good and the content drifts away from the finger. Holding one session open also
     * keeps `isScrollInProgress` true for the whole drag, which is what stops `settledPage` from
     * moving under a finger that has not been lifted yet.
     */
    private var scrollSession: Job? = null
    private var dragDeltas: Channel<Float>? = null

    /** The settle/page-turn animation, cancelled if a new gesture starts before it finishes. */
    private var settleJob: Job? = null

    /**
     * The page a committed swipe is turning to, until the pager settles there; `null` at every
     * other moment.
     *
     * This is what tells `ArticlePagerSync.settledPageSelects` that a settle came from the user
     * rather than from `PagerState` clamping itself against a list that shrank — see that
     * function's own KDoc for what selecting on a clamp would cost.
     */
    private var pendingSelectionPage: Int? = null

    /**
     * Whether [page] is the page a committed swipe was turning to, clearing the expectation either
     * way once the pager has settled on it.
     *
     * @param page The page the pager has just settled on.
     */
    fun consumePendingSelection(page: Int): Boolean {
        if (pendingSelectionPage != page) return false
        pendingSelectionPage = null
        return true
    }

    /** Time of the drag's first recorded point ([onDragStart]) and its most recently recorded
     * point ([onDrag]), plus how many points [onDrag] has recorded since — used by [onDragEnd]'s
     * fallback below. */
    private var dragStartUptimeMillis = 0L
    private var dragLastUptimeMillis = 0L
    private var dragSampleCount = 0

    /**
     * The offset the drag has resolved to *so far* — kept alongside [offset] rather than read back
     * from it, since [offset]'s value is only updated once the `scope.launch { offset.snapTo(...) }`
     * queued by [onDrag] actually runs, which is not guaranteed by the time [onDragEnd] fires for
     * the very same gesture (a fast synthetic drag in a test, or a fast real one, can deliver its
     * up event before that launch is dispatched). [onDragEnd] therefore resolves the outcome from
     * this field, which [onDrag] updates synchronously, while [offset] remains purely the value the
     * reader's own position is drawn from.
     */
    private var lastAppliedOffsetPx = 0f

    /**
     * When the last gesture that resolved as vertical ended, on `PointerInputChange.uptimeMillis`'s
     * clock. `null` until one has. Lives on the controller rather than in the gesture loop because
     * `pointerInput`'s block restarts, and this has to outlive a single gesture to be of any use.
     */
    private var lastVerticalEndUptimeMillis: Long? = null

    /**
     * Records that a gesture ended having been claimed as vertical, opening the lockout window
     * [isLockedOut] then refuses new swipes inside.
     */
    fun onVerticalGestureEnd(uptimeMillis: Long) {
        lastVerticalEndUptimeMillis = uptimeMillis
    }

    /** Whether a gesture beginning at [uptimeMillis] still falls inside that window. */
    fun isLockedOut(uptimeMillis: Long): Boolean =
        swipeLockedOut(uptimeMillis, lastVerticalEndUptimeMillis, SWIPE_AFTER_VERTICAL_LOCKOUT_MS)

    fun onDragStart(startUptimeMillis: Long) {
        dragTotalPx = 0f
        lastAppliedOffsetPx = 0f
        velocityTracker.resetTracking()
        dragStartUptimeMillis = startUptimeMillis
        dragLastUptimeMillis = startUptimeMillis
        dragSampleCount = 0
        // Before cancelling: cancel() does not wait for the old job's `finally` to run, so that
        // `finally` (see animateToPage) has to be able to tell it no longer owns gestureInProgress.
        gestureGeneration++
        // A settle still running from the previous gesture would otherwise wake up mid-drag and
        // scroll the pager back to where that gesture had left it.
        settleJob?.cancel()
        pendingSelectionPage = null
        anchorPage = pagerState.currentPage
        gestureInProgress = true
        val deltas = Channel<Float>(Channel.UNLIMITED)
        dragDeltas = deltas
        scrollSession = scope.launch {
            pagerState.scroll { for (delta in deltas) scrollBy(-delta) }
        }
    }

    /**
     * Plain (non-suspend) by necessity: the gesture loop that calls this runs inside
     * `AwaitPointerEventScope`, a `@RestrictsSuspension` receiver that only permits calling its own
     * member/extension suspend functions — an arbitrary suspend call would not compile there. This
     * hands the sample to the gesture's own scroll session through a channel instead of starting a
     * coroutine of its own; see [scrollSession] for why per-sample scrolling would lose deltas.
     *
     * @param deltaX This sample's own horizontal movement (the arming call passes the whole
     *   accumulated pre-slop travel, which is the same thing for a pager that has not moved yet).
     * @param uptimeMillis The sample's event time, for the release-velocity fit.
     */
    fun onDrag(deltaX: Float, uptimeMillis: Long) {
        dragTotalPx += deltaX
        velocityTracker.addPosition(uptimeMillis, Offset(dragTotalPx, 0f))
        dragLastUptimeMillis = uptimeMillis
        dragSampleCount++
        val towardsNext = dragTotalPx < 0f
        val movable = if (towardsNext) canSelectNext() else canSelectPrevious()
        // Still the whole drag's conceptual offset, damped at the ends — onDragEnd resolves the
        // outcome from it, whichever of the two ways below actually moved the content.
        lastAppliedOffsetPx = swipeDragOffset(dragTotalPx, movable, maxRubberBandPx)
        if (movable) {
            // Handed to the gesture's own scroll session, which applies the sign flip (the content
            // scrolls left as the finger moves left). UNLIMITED, so this never suspends or fails.
            dragDeltas?.trySend(deltaX)
            // The drag reversed out of an end of the list and now has somewhere to go: drop the
            // rubber band, or its offset would ride on top of the pager's own scroll.
            if (offset.value != 0f) scope.launch { offset.snapTo(0f) }
        } else {
            val target = lastAppliedOffsetPx
            scope.launch { offset.snapTo(target) }
        }
    }

    fun onDragEnd() {
        val velocity = resolveDragVelocityPxPerSec()
        val outcome = resolveSwipeOutcome(
            offsetPx = lastAppliedOffsetPx,
            velocityPxPerSec = velocity,
            widthPx = widthPx,
            flingVelocityPxPerSec = flingVelocityPxPerSec,
            canPrevious = canSelectPrevious(),
            canNext = canSelectNext(),
        )
        closeDragSession()
        val target = when (outcome) {
            ArticleSwipeOutcome.Cancel -> anchorPage
            ArticleSwipeOutcome.Next -> anchorPage + 1
            ArticleSwipeOutcome.Previous -> anchorPage - 1
        }
        // Only a real page turn may promote the resulting settle into a selection.
        if (target != anchorPage) pendingSelectionPage = target
        animateToPage(target)
    }

    /**
     * [VelocityTracker]'s default `Lsq2` fitting strategy needs at least [LSQ2_MIN_SAMPLE_COUNT]
     * samples before `calculateVelocity()` returns anything but `0f` — a short, fast flick (one
     * move past touch slop, then immediately `up`) can supply only one, silently reporting zero
     * velocity for a drag that plainly wasn't stationary. When [dragSampleCount] is below that
     * floor, fall back to this drag's own average velocity (its net offset over its own recorded
     * span) instead of trusting the tracker's guaranteed-zero answer; once there are enough
     * samples, the tracker's own (more accurate, deceleration-aware) fit is used unchanged.
     */
    private fun resolveDragVelocityPxPerSec(): Float {
        val trackerVelocity = velocityTracker.calculateVelocity().x
        if (dragSampleCount >= LSQ2_MIN_SAMPLE_COUNT) return trackerVelocity
        val elapsedMillis = dragLastUptimeMillis - dragStartUptimeMillis
        if (elapsedMillis <= 0L) return trackerVelocity
        return dragTotalPx / elapsedMillis * 1000f
    }

    /** The gesture was abandoned mid-drag (node detach / composition teardown) rather than released
     * normally — settle back exactly as a [ArticleSwipeOutcome.Cancel] would. */
    fun onDragCancel() {
        closeDragSession()
        pendingSelectionPage = null
        animateToPage(anchorPage)
    }

    /** Ends the gesture's scroll session, which lets [animateToPage] take the pager over. */
    private fun closeDragSession() {
        dragDeltas?.close()
        dragDeltas = null
    }

    /**
     * Resolves the gesture: springs the rubber band back and animates the pager to [page].
     *
     * The two run **in parallel**, not one after the other — a settle that waited out the rubber
     * band's spring before touching the pager would still be pending when the next gesture began,
     * and would then steal the pager back from it. [SWIPE_COMMIT_FRACTION] has already refused a
     * direction with nothing to move to, so [page] is in range; the clamp only guards against a
     * list that shrank underneath mid-drag.
     */
    private fun animateToPage(page: Int) {
        val generation = gestureGeneration
        settleJob = scope.launch {
            try {
                // Let the drag session close out first, so the pager is not still held by it.
                scrollSession?.join()
                launch { offset.animateTo(0f, animationSpec = spring()) }
                val lastPage = (pagerState.pageCount - 1).coerceAtLeast(0)
                pagerState.animateScrollToPage(page.coerceIn(0, lastPage))
            } finally {
                // A newer gesture has already started (and cancelled this job) by the time this
                // runs: it owns gestureInProgress now, and this settle must not clear it out from
                // under it.
                if (gestureGeneration == generation) gestureInProgress = false
            }
        }
    }
}

/**
 * Sibling-article navigation supplied by the caller when the reader is a narrow-layout
 * destination (a phone-width or tablet-width [PaneLayout], as opposed to [PaneLayout.Triple]'s
 * permanent, keyboard-driven pane). `null` is how a caller signals "no swipe here" — the same
 * null-means-Triple idiom every other narrow-layout affordance in this codebase uses (see the
 * `ui-guidelines` skill's "Adaptive pane layout & touch affordances"), just expressed as its own
 * type instead of piggybacking on `onNavigateUp`'s presence: a reader that has nowhere to
 * navigate *back* to (e.g. a permanently visible detail pane beside its list) can still have
 * somewhere to swipe *to*, so the two are independent signals a caller sets separately.
 */
data class ArticleSwipeNavigation(
    val onSelectNext: () -> Unit,
    val onSelectPrevious: () -> Unit,
    val canSelectNext: () -> Boolean,
    val canSelectPrevious: () -> Boolean,
)

/**
 * Bundles [HomeViewModel]'s next/previous-article operations into an [ArticleSwipeNavigation] for
 * [ArticleDetailPane] to pass down. A stable [remember]ed instance so passing it doesn't force a
 * recomposition of the reader below on every call.
 */
@Composable
internal fun rememberArticleSwipeNavigation(vm: HomeViewModel): ArticleSwipeNavigation =
    remember(vm) {
        ArticleSwipeNavigation(
            onSelectNext = { vm.selectNext() },
            onSelectPrevious = { vm.selectPrevious() },
            canSelectNext = { vm.canSelectNext() },
            canSelectPrevious = { vm.canSelectPrevious() },
        )
    }

/**
 * Creates and remembers an [ArticleSwipeController] for the article reader.
 *
 * @param pagerState The reader pager's own state — what a confirmed drag actually scrolls.
 * @param canSelectNext `HomeViewModel.canSelectNext`. Read fresh on every drag event (not snapshot
 *   at gesture start), so a list change mid-drag (e.g. sync merge) is reflected immediately.
 * @param canSelectPrevious `HomeViewModel.canSelectPrevious`.
 */
@Composable
internal fun rememberArticleSwipeController(
    pagerState: PagerState,
    canSelectNext: () -> Boolean,
    canSelectPrevious: () -> Boolean,
): ArticleSwipeController {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density, pagerState) {
        ArticleSwipeController(scope, density, pagerState, canSelectNext, canSelectPrevious)
    }
}

/**
 * Adds swipe-to-navigate (previous/next article) to the article reader's outer container.
 *
 * Attached to the reader's non-scrolling wrapper, *outside* the native WebView it hosts (see
 * `ArticleDetailPaneContent`) — the WebView is a heavyweight, in-tree native view on Android that
 * consumes touch input itself, so this modifier watches [PointerEventPass.Initial] (which reaches
 * an ancestor before a descendant's own interop handling, unlike the default `Main` pass) and
 * deliberately leaves every event unconsumed until the drag is confirmed horizontal. This mirrors
 * `FeedListDragGestures.feedListReorderDrag`'s own Initial-pass idiom. Once confirmed, subsequent
 * events are consumed, which cancels the WebView's own gesture (an Android `ACTION_CANCEL`) so the
 * two never fight over the same pointer.
 *
 * A vertical or diagonal drag is left untouched throughout, so the WebView's own scroll is never
 * interrupted; only a drag that clears [swipeArmsHorizontally] is claimed. Two further guards keep
 * a scroll from turning into a page turn by accident: a gesture that passes the touch slop while
 * pointing more vertically than horizontally is latched vertical for its whole remaining life, and
 * a gesture beginning within [SWIPE_AFTER_VERTICAL_LOCKOUT_MS] of a vertical one never arms at all.
 */
internal fun Modifier.articleSwipeNavigation(controller: ArticleSwipeController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            var dragging = false
            var total = Offset.Zero
            // Latched for the rest of the gesture once its travel passes the touch slop while
            // pointing more down/up than sideways: a drag that started as a scroll must not become
            // a page turn by curving sideways later, however far it eventually travels
            // horizontally.
            var verticalClaimed = false
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // A gesture beginning right after a vertical one is far more likely to be the next
                // scroll in a run than a page turn — see SWIPE_AFTER_VERTICAL_LOCKOUT_MS. Resolved
                // once, at the down event, so the answer can't change mid-gesture.
                val lockedOut = controller.isLockedOut(down.uptimeMillis)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (dragging) {
                            change.consume()
                            // Position hasn't moved since the last recorded drag sample, but this
                            // still records the true release time (up can fire later than the
                            // last move) for onDragEnd's low-sample-count velocity fallback.
                            controller.onDrag(0f, change.uptimeMillis)
                            controller.onDragEnd()
                            dragging = false
                        } else if (verticalClaimed) {
                            controller.onVerticalGestureEnd(change.uptimeMillis)
                        }
                        break
                    }
                    if (dragging) {
                        change.consume()
                        controller.onDrag(change.positionChangeIgnoreConsumed().x, change.uptimeMillis)
                        continue
                    }
                    // Someone below claimed the gesture (the WebView's own scroll, or a link tap) —
                    // stand down rather than fighting it for the same pointer.
                    if (change.isConsumed) break
                    val delta = change.positionChangeIgnoreConsumed()
                    total += delta
                    // Evaluated before the lockout is consulted, and independently of whether this
                    // gesture could ever arm: a vertical flick that happens to fall inside the
                    // lockout must still *extend* it, or a run of fast scrolls would open a window
                    // between flicks for a stray diagonal one to turn the page through.
                    // `abs(y) > abs(x)` keeps a horizontal drag with a little vertical wobble from
                    // latching itself shut.
                    if (!verticalClaimed &&
                        abs(total.y) > viewConfiguration.touchSlop &&
                        abs(total.y) > abs(total.x)
                    ) {
                        verticalClaimed = true
                    }
                    if (lockedOut || verticalClaimed) continue
                    if (swipeArmsHorizontally(total.x, total.y, viewConfiguration.touchSlop)) {
                        change.consume()
                        dragging = true
                        controller.onDragStart(down.uptimeMillis)
                        controller.onDrag(total.x, change.uptimeMillis)
                    }
                }
            } finally {
                // Node detach / composition teardown cancels this coroutine mid-drag; without this
                // the reader would be left stranded off-center with no gesture left to settle it.
                if (dragging) controller.onDragCancel()
            }
        }
    }

/**
 * Exposes previous/next-article navigation as [CustomAccessibilityAction]s, for the pointer-only
 * gesture [articleSwipeNavigation] provides no other way to reach — see the `ui-guidelines` skill's
 * "A pointer-only gesture … needs a `CustomAccessibilityAction` equivalent" rule. Mirrors
 * `FeedListRowParts.kt`'s `reorderAccessibilityActions` in shape.
 *
 * A direction with nothing to move to ([canNext]/[canPrevious]) exposes no action for that
 * direction at all, rather than one that would do nothing.
 *
 * @param enabled Gated by the caller on the same conditions [articleSwipeNavigation] itself is
 *   gated on (`isTouchPrimary && swipeNavigation != null && article != null`). Checked before
 *   resolving the string resources below, so a desktop composition (where this is always `false`)
 *   never pays for two `stringResource` lookups on every recomposition.
 */
@Composable
internal fun Modifier.articleSwipeAccessibilityActions(
    enabled: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier {
    if (!enabled || (!canNext && !canPrevious)) return this
    val nextLabel = stringResource(Res.string.article_next)
    val previousLabel = stringResource(Res.string.article_prev)
    val actions = buildList {
        if (canPrevious) add(CustomAccessibilityAction(previousLabel) { onPrevious(); true })
        if (canNext) add(CustomAccessibilityAction(nextLabel) { onNext(); true })
    }
    return this.semantics { customActions = actions }
}

package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** Divisor applied to the raw drag distance once the gesture points towards a direction with
 * nothing to move to, so the content still visibly resists rather than either refusing to move at
 * all or sliding as freely as an in-bounds drag. */
private const val SWIPE_RUBBER_BAND_DIVISOR = 4f

/** Furthest an out-of-bounds drag may pull the content, regardless of how far the finger travels. */
private const val SWIPE_RUBBER_BAND_MAX_DP = 48f

/** How long the settle-back (cancel) / settle-in (after a commit) animation takes. */
private const val SWIPE_SETTLE_ANIMATION_MS = 220

/** How long the outgoing article gets to finish sliding off-pane before the incoming one is
 * snapped in from the opposite edge. */
private const val SWIPE_EXIT_ANIMATION_MS = 160

/** Safety bound on how long a commit waits for the ViewModel's selection to actually change before
 * giving up and settling back to center anyway — guards against a commit whose target turned out
 * to be a tombstoned/deleted row (see `HomeViewModel.selectArticle`), which leaves the selection
 * exactly where it was and would otherwise strand the content off-pane forever. */
private const val SWIPE_SELECTION_CHANGE_TIMEOUT_MS = 1_000L

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
    private val canSelectNext: () -> Boolean,
    private val canSelectPrevious: () -> Boolean,
    private val onSelectNext: () -> Unit,
    private val onSelectPrevious: () -> Unit,
    private val currentArticleId: () -> String?,
) {
    val offset = Animatable(0f)

    /** The reader pane's own width, kept in sync by `Modifier.onSizeChanged` at the call site. */
    var widthPx by mutableFloatStateOf(0f)

    private val maxRubberBandPx = with(density) { SWIPE_RUBBER_BAND_MAX_DP.dp.toPx() }
    private val flingVelocityPxPerSec = with(density) { SWIPE_FLING_VELOCITY_DP_PER_S.dp.toPx() }

    private var dragTotalPx = 0f
    private val velocityTracker = VelocityTracker()

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

    fun onDragStart() {
        dragTotalPx = 0f
        lastAppliedOffsetPx = 0f
        velocityTracker.resetTracking()
    }

    /**
     * Plain (non-suspend) by necessity: the gesture loop that calls this runs inside
     * `AwaitPointerEventScope`, a `@RestrictsSuspension` receiver that only permits calling its own
     * member/extension suspend functions — an arbitrary suspend call (like `Animatable.snapTo`)
     * would not compile there. Each call queues its own `snapTo` on [scope]; `Animatable` serializes
     * concurrent writers on its own mutex, so a burst of drag events still applies in order with no
     * torn offset, just as a burst of `scope.launch { controller.move(...) }` calls would for
     * `FeedListDragController`.
     */
    fun onDrag(deltaX: Float, uptimeMillis: Long) {
        dragTotalPx += deltaX
        velocityTracker.addPosition(uptimeMillis, Offset(dragTotalPx, 0f))
        val towardsNext = dragTotalPx < 0f
        val movable = if (towardsNext) canSelectNext() else canSelectPrevious()
        lastAppliedOffsetPx = swipeDragOffset(dragTotalPx, movable, maxRubberBandPx)
        val target = lastAppliedOffsetPx
        scope.launch { offset.snapTo(target) }
    }

    fun onDragEnd() {
        val velocity = velocityTracker.calculateVelocity().x
        val outcome = resolveSwipeOutcome(
            offsetPx = lastAppliedOffsetPx,
            velocityPxPerSec = velocity,
            widthPx = widthPx,
            flingVelocityPxPerSec = flingVelocityPxPerSec,
            canPrevious = canSelectPrevious(),
            canNext = canSelectNext(),
        )
        when (outcome) {
            ArticleSwipeOutcome.Cancel -> settleToCenter()
            ArticleSwipeOutcome.Next -> commit(exitTowards = -widthPx, select = onSelectNext)
            ArticleSwipeOutcome.Previous -> commit(exitTowards = widthPx, select = onSelectPrevious)
        }
    }

    /** The gesture was abandoned mid-drag (node detach / composition teardown) rather than released
     * normally — settle back exactly as a [ArticleSwipeOutcome.Cancel] would. */
    fun onDragCancel() = settleToCenter()

    private fun settleToCenter() = scope.launch { offset.animateTo(0f, animationSpec = spring()) }

    private fun commit(exitTowards: Float, select: () -> Unit) = scope.launch {
        offset.animateTo(exitTowards, animationSpec = tween(SWIPE_EXIT_ANIMATION_MS))
        val idBefore = currentArticleId()
        select()
        withTimeoutOrNull(SWIPE_SELECTION_CHANGE_TIMEOUT_MS) {
            snapshotFlow(currentArticleId).first { it != idBefore }
        }
        offset.snapTo(-exitTowards)
        offset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_ANIMATION_MS))
    }
}

/**
 * Creates and remembers an [ArticleSwipeController] for the article reader.
 *
 * @param canSelectNext `HomeViewModel.canSelectNext`. Read fresh on every drag event (not snapshot
 *   at gesture start), so a list change mid-drag (e.g. sync merge) is reflected immediately.
 * @param canSelectPrevious `HomeViewModel.canSelectPrevious`.
 * @param onSelectNext `HomeViewModel.selectNext`.
 * @param onSelectPrevious `HomeViewModel.selectPrevious`.
 * @param currentArticleId The id of the article currently shown, used to detect when a commit's
 *   `onSelectNext`/`onSelectPrevious` call has actually taken effect.
 */
@Composable
internal fun rememberArticleSwipeController(
    canSelectNext: () -> Boolean,
    canSelectPrevious: () -> Boolean,
    onSelectNext: () -> Unit,
    onSelectPrevious: () -> Unit,
    currentArticleId: () -> String?,
): ArticleSwipeController {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density) {
        ArticleSwipeController(scope, density, canSelectNext, canSelectPrevious, onSelectNext, onSelectPrevious, currentArticleId)
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
 * interrupted; only a drag whose horizontal travel first exceeds both the touch slop and the
 * vertical travel is claimed.
 */
internal fun Modifier.articleSwipeNavigation(controller: ArticleSwipeController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            var dragging = false
            var total = Offset.Zero
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (dragging) {
                            change.consume()
                            controller.onDragEnd()
                            dragging = false
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
                    if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y)) {
                        change.consume()
                        dragging = true
                        controller.onDragStart()
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
 *   gated on (`isTouchPrimary && onNavigateUp != null && article != null`). Checked before
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

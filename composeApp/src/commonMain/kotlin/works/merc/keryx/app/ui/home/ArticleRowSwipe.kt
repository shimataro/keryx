package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import works.merc.keryx.app.presentation.home.ArticleSwipeAction
import works.merc.keryx.app.presentation.home.articleSwipeLeadingAction
import works.merc.keryx.app.presentation.home.articleSwipeTrailingAction
import works.merc.keryx.app.ui.common.KeryxIcons
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far a row must be dragged for its swipe to act on release — M3 `SwipeToDismissBox`'s own positional threshold. */
internal val ARTICLE_SWIPE_THRESHOLD = 56.dp

private val SWIPE_ICON_SIZE = 24.dp

/** How much of [StarredColor] the armed star strip lays over the neutral strip — a container's tone, not a full fill. */
private const val STARRED_CONTAINER_ALPHA = 0.3f
private val SWIPE_ICON_INSET = 20.dp

/**
 * An article row's swipe (`external-spec.md` §9 "Swipe on article rows"): dragged past
 * [ARTICLE_SWIPE_THRESHOLD] and released, the row runs its leading ([articleSwipeLeadingAction],
 * read/unread) or trailing ([articleSwipeTrailingAction], star/unstar) action and springs back — it
 * never leaves the list and never selects the row. A haptic tick marks crossing the threshold, and
 * both actions are also exposed as accessibility custom actions, since TalkBack cannot drag.
 *
 * **Which drags swipe the row** follows the reader's page turn (`articleSwipeNavigation`), since the
 * row sits on a scrolling list the same way the reader's page does. A `pointerInput` gesture loop on
 * the default [PointerEventPass.Main] pass sees each event before the enclosing `LazyColumn` (and the
 * narrow layout's `ModalNavigationDrawer`) and consumes nothing until the drag arms, so a scroll
 * reaches the list untouched. The drag arms only when [swipeArmsHorizontally] holds — past the touch
 * slop and clearly more horizontal than vertical, so a diagonal flick stays a scroll. A gesture that
 * passes the touch slop pointing more vertically than horizontally is latched vertical for the rest
 * of its life, one that an ancestor has already claimed (the list's scroll, the drawer) never arms,
 * and one that begins inside [swipeLockout]'s window after a vertical gesture never arms at all.
 * Once armed, every move is consumed — which also cancels the row's tap and long press, as a
 * consumed move always does.
 *
 * Releasing past the threshold runs the action, judged on the offset the row is drawn at; the row
 * then springs back. A new gesture that arms during that spring-back cancels it and carries on from
 * the position the row has reached, so a quick second swipe is neither lost nor fought over by the
 * animation. The haptic tick comes only from the drag, never from the spring-back.
 *
 * Short of the threshold the revealed strip is neutral with the action's icon in the action's color;
 * past it the strip turns to a tonal container of that color (M3's container roles: `primaryContainer`,
 * and for star [StarredColor] laid thinly over the neutral strip) with the icon in the content color
 * that reads on it, so the armed state shows as well as being felt without a full-strength fill. From release until the row is back in place the strip
 * keeps the actions as they were at release, rather than swapping to the opposite action the moment
 * the one just run flips the row's state.
 *
 * Built from modifiers on the row's own node rather than M3's `SwipeToDismissBox` — "Standard
 * components over custom drawing" exception 3 (`ui-guidelines`): `SwipeToDismissBox` wraps the row in
 * two to three extra `LayoutNode`s, and the article row sits next to the node-count-sensitive
 * `LazyColumn` reuse-pool crash in `docs/known-issues.md` ("Article list crashes the UI thread during
 * heavy scroll + selection churn"). `pointerInput` is a modifier node, not a `LayoutNode`. Move to
 * `SwipeToDismissBox` once that upstream defect is fixed.
 *
 * Modifier order is load-bearing: the drag is detected on the unmoved node, the revealed strip is
 * drawn behind it there, and only what follows [absoluteOffset] (the row's surface and content) moves
 * with the finger. `absoluteOffset` keeps the offset in screen space, so a rightward drag is the
 * leading edge in a left-to-right layout and the trailing one in a right-to-left one ([showsLeading]).
 *
 * @param enabled Whether the row swipes at all (touch-primary only); `false` leaves the row unchanged.
 * @param isRead The row's read state, which picks the leading action.
 * @param isStarred The row's starred state, which picks the trailing action.
 * @param strings The labels for the accessibility actions — the context menu's own.
 * @param swipeLockout The after-a-scroll lockout, shared by every row of one list — a run of flicks
 *   moves a different row under the finger each time, so a per-row one would never see the last flick.
 * @param onSwipeAction Runs the chosen action ([works.merc.keryx.app.presentation.home.HomeViewModel.performSwipeAction]).
 */
@Composable
internal fun Modifier.articleRowSwipe(
    enabled: Boolean,
    isRead: Boolean,
    isStarred: Boolean,
    strings: ArticleRowStrings,
    swipeLockout: VerticalGestureLockout,
    onSwipeAction: (ArticleSwipeAction) -> Unit,
): Modifier {
    if (!enabled) return this
    val leading = articleSwipeLeadingAction(isRead)
    val trailing = articleSwipeTrailingAction(isStarred)
    val currentLeading by rememberUpdatedState(leading)
    val currentTrailing by rememberUpdatedState(trailing)
    val currentOnSwipeAction by rememberUpdatedState(onSwipeAction)
    val currentHaptics by rememberUpdatedState(LocalHapticFeedback.current)
    val scope = rememberCoroutineScope()
    val swipe = remember { RowSwipeState() }
    val thresholdPx = with(LocalDensity.current) { ARTICLE_SWIPE_THRESHOLD.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentIsRead by rememberUpdatedState(isRead)
    val currentIsStarred by rememberUpdatedState(isStarred)
    val stripColor = MaterialTheme.colorScheme.surfaceContainerHighest
    // The strip shows the actions as they were at release until the row is back in place: the
    // action just run flips the row's state, and the strip must not swap to the opposite action
    // while it slides away.
    val shown = swipe.frozen ?: SwipeShownState(isRead, isStarred)
    val leadingStyle = SwipeStyle(
        icon = painterResource(swipeIcon(articleSwipeLeadingAction(shown.isRead))),
        tint = MaterialTheme.colorScheme.primary,
        armedFill = MaterialTheme.colorScheme.primaryContainer,
        armedIcon = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    val trailingStyle = SwipeStyle(
        icon = painterResource(swipeIcon(articleSwipeTrailingAction(shown.isStarred))),
        tint = StarredColor,
        // M3 has no amber container role, so one is made the way a container sits on its surface.
        armedFill = StarredColor.copy(alpha = STARRED_CONTAINER_ALPHA).compositeOver(stripColor),
        armedIcon = MaterialTheme.colorScheme.onSurface,
    )
    val leadingLabel = swipeLabel(leading, strings)
    val trailingLabel = swipeLabel(trailing, strings)
    return this
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(leadingLabel) { currentOnSwipeAction(currentLeading); true },
                CustomAccessibilityAction(trailingLabel) { currentOnSwipeAction(currentTrailing); true },
            )
        }
        .pointerInput(swipe, swipeLockout, thresholdPx, rtl) {
            awaitEachGesture {
                var dragging = false
                var total = Offset.Zero
                // Latched for the rest of the gesture once its travel passes the touch slop while
                // pointing more down/up than sideways: a scroll must not become a swipe by curving
                // sideways later.
                var verticalClaimed = false
                // An ancestor (the list's scroll, the drawer) took the gesture first.
                var claimedElsewhere = false
                try {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Resolved once, at the down event, so the answer can't change mid-gesture.
                    val lockedOut = swipeLockout.isLockedOut(down.uptimeMillis)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            if (dragging) {
                                change.consume()
                                dragging = false
                                val released = swipe.offset.floatValue
                                if (abs(released) >= thresholdPx) {
                                    currentOnSwipeAction(
                                        if (showsLeading(released, rtl)) currentLeading else currentTrailing,
                                    )
                                }
                                swipe.freeze(SwipeShownState(currentIsRead, currentIsStarred))
                                swipe.springBack(scope)
                            } else if (verticalClaimed) {
                                swipeLockout.onVerticalGestureEnd(change.uptimeMillis)
                            }
                            break
                        }
                        if (dragging) {
                            change.consume()
                            swipe.dragBy(change.positionChangeIgnoreConsumed().x, thresholdPx, currentHaptics)
                            continue
                        }
                        total += change.positionChangeIgnoreConsumed()
                        // Evaluated before the lockout is consulted, so a vertical flick inside the
                        // window still extends it — see VerticalGestureLockout.
                        if (!verticalClaimed &&
                            abs(total.y) > viewConfiguration.touchSlop &&
                            abs(total.y) > abs(total.x)
                        ) {
                            verticalClaimed = true
                        }
                        if (lockedOut || verticalClaimed || claimedElsewhere) continue
                        if (swipeArmsHorizontally(total.x, total.y, viewConfiguration.touchSlop)) {
                            change.consume()
                            dragging = true
                            swipe.arm()
                            swipe.dragBy(total.x, thresholdPx, currentHaptics)
                            continue
                        }
                        // The same event's Final pass comes after every ancestor's Main pass: a
                        // consumed change there means the list started scrolling (or the drawer
                        // started opening) on it, and the row must not then arm as well.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.firstOrNull { it.id == down.id }?.isConsumed == true) {
                            claimedElsewhere = true
                        }
                    }
                } finally {
                    // A cancelled gesture (node detached, pointer lost) runs nothing, but must not
                    // leave the row stranded off its place either.
                    if (dragging) swipe.springBack(scope)
                }
            }
        }
        .drawBehind {
            val x = swipe.offset.floatValue
            if (x == 0f) return@drawBehind
            drawSwipeStrip(
                x = x,
                stripColor = stripColor,
                style = if (showsLeading(x, rtl)) leadingStyle else trailingStyle,
                armed = abs(x) >= thresholdPx,
            )
        }
        .absoluteOffset { IntOffset(swipe.offset.floatValue.roundToInt(), 0) }
}

/**
 * Whether a row drawn at [offset] reveals — and on release runs — its leading action: a rightward
 * offset is the leading edge unless the layout is right-to-left.
 */
private fun showsLeading(offset: Float, rtl: Boolean): Boolean = (offset > 0f) != rtl

/** The read and starred state the strip draws its actions from. */
private data class SwipeShownState(val isRead: Boolean, val isStarred: Boolean)

/**
 * The row's horizontal offset, the spring-back animating it home and the state the strip is frozen
 * at meanwhile — all outliving any one gesture.
 */
private class RowSwipeState {
    val offset = mutableFloatStateOf(0f)

    /** The state at the last release, drawn until the spring-back ends or a new drag arms; `null` otherwise. */
    var frozen by mutableStateOf<SwipeShownState?>(null)
        private set

    private var springBack: Job? = null

    /**
     * A new drag took the row: stop the previous spring-back where it is, so the two never both
     * write [offset], and draw the row's live state again — the new drag acts on it.
     */
    fun arm() {
        springBack?.cancel()
        springBack = null
        frozen = null
    }

    fun freeze(state: SwipeShownState) {
        frozen = state
    }

    /** Moves the row by [delta], ticking the haptic when the move crosses [thresholdPx] either way. */
    fun dragBy(delta: Float, thresholdPx: Float, haptics: HapticFeedback) {
        val before = offset.floatValue
        val after = before + delta
        offset.floatValue = after
        if ((abs(before) >= thresholdPx) != (abs(after) >= thresholdPx)) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    fun springBack(scope: CoroutineScope) {
        val from = offset.floatValue
        springBack = scope.launch {
            animate(initialValue = from, targetValue = 0f) { value, _ -> offset.floatValue = value }
            frozen = null
        }
    }
}

/**
 * How one edge's action is drawn: [icon] in [tint] on the neutral strip short of the threshold, and
 * once past it ([drawSwipeStrip]'s `armed`) the strip filled with [armedFill] under [icon] in
 * [armedIcon] — so that releasing now will act is visible, not only felt through the haptic tick.
 */
private class SwipeStyle(val icon: Painter, val tint: Color, val armedFill: Color, val armedIcon: Color)

/**
 * Fills the strip a drag of [x] pixels uncovers and draws [style]'s icon near its outer edge, in the
 * armed colors while [armed] (the row is past [ARTICLE_SWIPE_THRESHOLD]).
 */
private fun DrawScope.drawSwipeStrip(x: Float, stripColor: Color, style: SwipeStyle, armed: Boolean) {
    val left = if (x > 0f) 0f else size.width + x
    val right = if (x > 0f) x else size.width
    clipRect(left = left, right = right) {
        drawRect(if (armed) style.armedFill else stripColor, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        val iconPx = SWIPE_ICON_SIZE.toPx()
        val insetPx = SWIPE_ICON_INSET.toPx()
        val iconLeft = if (x > 0f) insetPx else size.width - insetPx - iconPx
        translate(left = iconLeft, top = (size.height - iconPx) / 2f) {
            with(style.icon) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(if (armed) style.armedIcon else style.tint)) }
        }
    }
}

/** The icon of each action, matching the reader toolbar's read and star buttons. */
private fun swipeIcon(action: ArticleSwipeAction) = when (action) {
    ArticleSwipeAction.MarkRead -> KeryxIcons.Circle
    ArticleSwipeAction.MarkUnread -> KeryxIcons.CircleFilled
    ArticleSwipeAction.Star -> KeryxIcons.Star
    ArticleSwipeAction.Unstar -> KeryxIcons.StarBorder
}

/** The label of each action — the row context menu's own. */
private fun swipeLabel(action: ArticleSwipeAction, strings: ArticleRowStrings) = when (action) {
    ArticleSwipeAction.MarkRead -> strings.markAsRead
    ArticleSwipeAction.MarkUnread -> strings.markAsUnread
    ArticleSwipeAction.Star -> strings.star
    ArticleSwipeAction.Unstar -> strings.unstar
}

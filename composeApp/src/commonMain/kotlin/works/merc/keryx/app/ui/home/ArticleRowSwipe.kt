package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
private val SWIPE_ICON_INSET = 20.dp

/**
 * An article row's swipe (`external-spec.md` §9 "Swipe on article rows"): dragged past
 * [ARTICLE_SWIPE_THRESHOLD] and released, the row runs its leading ([articleSwipeLeadingAction],
 * read/unread) or trailing ([articleSwipeTrailingAction], star/unstar) action and springs back — it
 * never leaves the list and never selects the row. A haptic tick marks crossing the threshold, and
 * both actions are also exposed as accessibility custom actions, since TalkBack cannot drag.
 *
 * Built from modifiers on the row's own node rather than M3's `SwipeToDismissBox` — "Standard
 * components over custom drawing" exception 3 (`ui-guidelines`): `SwipeToDismissBox` wraps the row in
 * two to three extra `LayoutNode`s, and the article row sits next to the node-count-sensitive
 * `LazyColumn` reuse-pool crash in `docs/known-issues.md` ("Article list crashes the UI thread during
 * heavy scroll + selection churn"). Move to `SwipeToDismissBox` once that upstream defect is fixed.
 *
 * Modifier order is load-bearing: the drag is detected on the unmoved node, the revealed strip is
 * drawn behind it there, and only what follows [absoluteOffset] (the row's surface and content) moves
 * with the finger. `absoluteOffset` keeps the offset in screen space, so a rightward drag is the
 * leading edge in a left-to-right layout and the trailing one in a right-to-left one.
 *
 * @param enabled Whether the row swipes at all (touch-primary only); `false` leaves the row unchanged.
 * @param isRead The row's read state, which picks the leading action.
 * @param isStarred The row's starred state, which picks the trailing action.
 * @param strings The labels for the accessibility actions — the context menu's own.
 * @param onSwipeAction Runs the chosen action ([works.merc.keryx.app.presentation.home.HomeViewModel.performSwipeAction]).
 */
@Composable
internal fun Modifier.articleRowSwipe(
    enabled: Boolean,
    isRead: Boolean,
    isStarred: Boolean,
    strings: ArticleRowStrings,
    onSwipeAction: (ArticleSwipeAction) -> Unit,
): Modifier {
    if (!enabled) return this
    val leading = articleSwipeLeadingAction(isRead)
    val trailing = articleSwipeTrailingAction(isStarred)
    val currentLeading by rememberUpdatedState(leading)
    val currentTrailing by rememberUpdatedState(trailing)
    val currentOnSwipeAction by rememberUpdatedState(onSwipeAction)
    val offset = remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val thresholdPx = with(LocalDensity.current) { ARTICLE_SWIPE_THRESHOLD.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val stripColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val leadingStyle = SwipeStyle(painterResource(swipeIcon(leading)), MaterialTheme.colorScheme.primary)
    val trailingStyle = SwipeStyle(painterResource(swipeIcon(trailing)), StarredColor)
    val draggableState = rememberDraggableState { delta ->
        val before = offset.floatValue
        val after = before + delta
        offset.floatValue = after
        if ((abs(before) >= thresholdPx) != (abs(after) >= thresholdPx)) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
    val leadingLabel = swipeLabel(leading, strings)
    val trailingLabel = swipeLabel(trailing, strings)
    return this
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(leadingLabel) { onSwipeAction(leading); true },
                CustomAccessibilityAction(trailingLabel) { onSwipeAction(trailing); true },
            )
        }
        .draggable(
            state = draggableState,
            orientation = Orientation.Horizontal,
            onDragStopped = {
                val released = offset.floatValue
                if (abs(released) >= thresholdPx) {
                    // Rightward is the leading edge unless the layout is right-to-left.
                    val leadingSide = (released > 0f) != rtl
                    currentOnSwipeAction(if (leadingSide) currentLeading else currentTrailing)
                }
                animate(initialValue = released, targetValue = 0f) { value, _ -> offset.floatValue = value }
            },
        )
        .drawBehind {
            val x = offset.floatValue
            if (x == 0f) return@drawBehind
            // The style of the edge the drag reveals: the left strip on a rightward drag.
            val style = if ((x > 0f) != rtl) leadingStyle else trailingStyle
            drawSwipeStrip(x, stripColor, style)
        }
        .absoluteOffset { IntOffset(offset.floatValue.roundToInt(), 0) }
}

private class SwipeStyle(val icon: Painter, val tint: Color)

/** Fills the strip a drag of [x] pixels uncovers and draws [style]'s icon near its outer edge. */
private fun DrawScope.drawSwipeStrip(x: Float, stripColor: Color, style: SwipeStyle) {
    val left = if (x > 0f) 0f else size.width + x
    val right = if (x > 0f) x else size.width
    clipRect(left = left, right = right) {
        drawRect(stripColor, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        val iconPx = SWIPE_ICON_SIZE.toPx()
        val insetPx = SWIPE_ICON_INSET.toPx()
        val iconLeft = if (x > 0f) insetPx else size.width - insetPx - iconPx
        translate(left = iconLeft, top = (size.height - iconPx) / 2f) {
            with(style.icon) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(style.tint)) }
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

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.dp

/** Distance a mouse pointer must move past the initial press before a drag starts. Small,
 * because a mouse click is naturally precise. */
private const val MOUSE_DRAG_THRESHOLD_DP = 4f

/** Distance a touch/stylus pointer must move past the initial press before a drag starts.
 * Reuses Compose Foundation's own `defaultTouchSlop` value as-is. */
private const val TOUCH_DRAG_THRESHOLD_DP = 18f

/**
 * The feed list's reorder gesture, hosted on the pane's single non-virtualized drag-host `Box`
 * (never on a row — see [FeedListDragController] for why) and resolving what a press would drag by
 * hit-testing that press against the list's layout info.
 *
 * Everything runs on [PointerEventPass.Initial]: this host is an *ancestor* of the `LazyColumn`'s
 * own scrollable, and the initial pass reaches ancestors first, so the gesture can claim the
 * pointer before list scrolling consumes it. Below the drag threshold nothing is consumed, so a
 * plain press still selects the row and a press that isn't on a draggable row still scrolls the
 * list normally.
 *
 * Once dragging, **every** change is consumed. That is what suppresses the row's own click on
 * release and what keeps a right-click mid-drag from opening the native context menu
 * (`Modifier.nativeContextMenu` only opens on an *unconsumed* secondary press). A secondary or
 * middle press never starts a drag in the first place.
 */
internal fun Modifier.feedListReorderDrag(controller: FeedListDragController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            var dragging = false
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val buttons = currentEvent.buttons
                if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return@awaitEachGesture
                val grab = controller.sourceAt(down.position.y) ?: return@awaitEachGesture
                val thresholdPx = (
                    if (down.type == PointerType.Mouse) MOUSE_DRAG_THRESHOLD_DP else TOUCH_DRAG_THRESHOLD_DP
                    ).dp.toPx()
                var total = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (dragging) {
                            change.consume()
                            controller.end(change.position)
                            dragging = false
                        }
                        break
                    }
                    if (dragging) {
                        change.consume()
                        controller.move(change.position)
                        continue
                    }
                    // Someone below claimed the gesture (a nested clickable/scrollable) — stand down
                    // rather than fighting it for the same pointer.
                    if (change.isConsumed) break
                    total += change.positionChangeIgnoreConsumed()
                    if (total.getDistance() >= thresholdPx) {
                        change.consume()
                        dragging = true
                        controller.start(
                            item = grab.item,
                            pos = change.position,
                            grabOffset = Offset(down.position.x, grab.grabOffsetY),
                            rowHeightPx = grab.rowHeightPx,
                        )
                    }
                }
            } finally {
                // Node detach / composition teardown cancels this coroutine mid-drag; without this
                // the ghost would be left floating with no gesture left to dismiss it.
                if (dragging) controller.cancel()
            }
        }
    }

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

/**
     * Adds feed-row reordering gestures to a non-virtualized drag host.
     *
     * Secondary and tertiary presses are ignored, and dragging begins only after the pointer moves
     * beyond the applicable drag threshold. Active drags consume pointer events and are ended or
     * cancelled when the gesture finishes.
     *
     * @param controller The controller that manages feed-row drag state.
     * @param enabled Whether presses may start a drag at all. Callers switch this off while a row
     *   hosts a focused text editor, which owns its own press-and-sweep (text selection) and must
     *   not have it stolen by this ancestor's `Initial`-pass gesture.
     * @return A modifier that handles feed-row reordering gestures.
     */
internal fun Modifier.feedListReorderDrag(controller: FeedListDragController, enabled: Boolean = true): Modifier =
    pointerInput(controller, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            var dragging = false
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val buttons = currentEvent.buttons
                if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return@awaitEachGesture
                val grab = controller.sourceAt(down.position.y) ?: return@awaitEachGesture
                val thresholdPx = if (down.type == PointerType.Mouse) {
                    MOUSE_DRAG_THRESHOLD_DP.dp.toPx()
                } else {
                    viewConfiguration.touchSlop
                }
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

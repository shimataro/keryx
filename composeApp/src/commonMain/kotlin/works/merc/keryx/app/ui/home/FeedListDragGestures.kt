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
 * Width of the touch-only drag-start band, measured from a row's trailing edge — see
 * [DragHandle]'s KDoc. Comfortably wider than the handle icon's own visible footprint (20dp icon +
 * 8dp leading padding), matching Material's general touch-target sizing guidance so the band is
 * reliably hittable with a thumb without needing to also grow the row's height.
 */
private const val TOUCH_DRAG_HANDLE_BAND_DP = 44f

/**
 * Adds feed-row reordering gestures to a non-virtualized drag host.
 *
 * Secondary and tertiary presses are ignored, and dragging begins only after the pointer moves
 * beyond the applicable drag threshold. Active drags consume pointer events and are ended or
 * cancelled when the gesture finishes.
 *
 * When [isTouchPrimary], a press must additionally land within [TOUCH_DRAG_HANDLE_BAND_DP] of the
 * drag host's trailing edge (which — since every draggable row fills the host's width — is the
 * same as the row's own trailing edge) to start a drag at all; everywhere else on the row, touch
 * input falls through untouched to the `LazyColumn`'s own scroll gesture. Without this gate, any
 * touch press anywhere on a draggable row would compete with scrolling the list, since both
 * gestures start from the same kind of press-and-move. A mouse has no such ambiguity (clicking
 * and dragging are already distinguished by button state and precision), so it keeps the
 * historical "drag from anywhere on the row" convention.
 *
 * @param controller The controller that manages feed-row drag state.
 * @param enabled Whether presses may start a drag at all. Callers switch this off while a row
 *   hosts a focused text editor, which owns its own press-and-sweep (text selection) and must
 *   not have it stolen by this ancestor's `Initial`-pass gesture.
 * @param isTouchPrimary Overridable for tests only (mirrors `NativeMenu.desktop.kt`'s
 *   `defaultPopupHandle`'s `macOs` parameter) — production call sites always use the platform
 *   default from `platform/PlatformOs.kt`, which is always `false` on desktop, so exercising the
 *   touch-only handle gate here needs a way to simulate it without an actual Android target.
 * @return A modifier that handles feed-row reordering gestures.
 */
internal fun Modifier.feedListReorderDrag(
    controller: FeedListDragController,
    enabled: Boolean = true,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
): Modifier =
    pointerInput(controller, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            var dragging = false
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val buttons = currentEvent.buttons
                if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return@awaitEachGesture
                val grab = controller.sourceAt(down.position.y) ?: return@awaitEachGesture
                if (isTouchPrimary && down.position.x < size.width - TOUCH_DRAG_HANDLE_BAND_DP.dp.toPx()) {
                    return@awaitEachGesture
                }
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

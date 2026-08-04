package works.merc.keryx.app.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/** Distance a mouse pointer must move past the initial press before a drag starts. Small,
 * because a mouse click is naturally precise. */
private const val MOUSE_DRAG_THRESHOLD_DP = 4f

/** Distance a touch/stylus pointer must move past the initial press before a drag starts.
 * Reuses Compose Foundation's own `defaultTouchSlop` value as-is. */
private const val TOUCH_DRAG_THRESHOLD_DP = 18f

/**
 * A drop-in replacement for [androidx.compose.foundation.draganddrop.dragAndDropSource] that
 * requires the pointer to move past a small threshold before the platform drag-and-drop session
 * actually starts, instead of starting on the first pixel of movement.
 *
 * The stock modifier has no public way to configure this: its `detectDragStart` parameter is
 * `internal` (`// TODO: Expose this as public argument` in the library source), and its default
 * detector hardcodes an intentionally tiny slop for [PointerType.Mouse] (`0.125.dp`), which is why
 * a single pixel of mouse movement was enough to start a drag. This modifier reimplements the same
 * wiring the stock one uses — [DragAndDropSourceModifierNode] and
 * [SuspendingPointerInputModifierNode], both public — with our own configurable threshold instead.
 */
internal fun Modifier.dragAndDropSourceWithThreshold(
    drawDragDecoration: DrawScope.() -> Unit,
    transferData: (Offset) -> DragAndDropTransferData?,
): Modifier = this then DragAndDropSourceWithThresholdElement(drawDragDecoration, transferData)

private data class DragAndDropSourceWithThresholdElement(
    val drawDragDecoration: DrawScope.() -> Unit,
    val transferData: (Offset) -> DragAndDropTransferData?,
) : ModifierNodeElement<DragAndDropSourceWithThresholdNode>() {
    override fun create() = DragAndDropSourceWithThresholdNode(drawDragDecoration, transferData)

    override fun update(node: DragAndDropSourceWithThresholdNode) {
        node.drawDragDecoration = drawDragDecoration
        node.transferData = transferData
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dragAndDropSourceWithThreshold"
    }
}

private class DragAndDropSourceWithThresholdNode(
    var drawDragDecoration: DrawScope.() -> Unit,
    var transferData: (Offset) -> DragAndDropTransferData?,
) : DelegatingNode(), LayoutAwareModifierNode {

    private var size: IntSize = IntSize.Zero

    private val dragAndDropModifierNode = delegate(
        DragAndDropSourceModifierNode { offset ->
            transferData(offset)?.let { data ->
                startDragAndDropTransfer(data, size.toSize(), drawDragDecoration)
            }
        },
    )

    private var inputNode: PointerInputModifierNode? = null

    override fun onAttach() {
        if (!dragAndDropModifierNode.isRequestDragAndDropTransferRequired) return
        inputNode = delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val thresholdPx = with(this) {
                        (if (down.type == PointerType.Mouse) MOUSE_DRAG_THRESHOLD_DP else TOUCH_DRAG_THRESHOLD_DP).dp.toPx()
                    }
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) break
                        total += change.positionChangeIgnoreConsumed()
                        if (total.getDistance() >= thresholdPx) {
                            dragAndDropModifierNode.requestDragAndDropTransfer(change.position)
                            break
                        }
                    }
                }
            },
        )
    }

    override fun onDetach() {
        inputNode?.let { undelegate(it) }
        inputNode = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        dragAndDropModifierNode.onPlaced(coordinates)
    }

    override fun onRemeasured(size: IntSize) {
        this.size = size
        dragAndDropModifierNode.onRemeasured(size)
    }
}

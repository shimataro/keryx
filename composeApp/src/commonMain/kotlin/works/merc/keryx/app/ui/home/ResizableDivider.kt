package works.merc.keryx.app.ui.home

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.platform.CursorIcons

/**
 * A vertical divider between two panes with a wider invisible drag hit-target.
 *
 * M3 has no touch-oriented pane-splitter idiom, and 8dp is well under any reasonable touch
 * target, so on a touch-primary platform ([isTouchPrimary]) this renders as a plain static
 * divider with no hover/drag affordances at all — pane widths stay at whatever
 * `local_settings` last recorded. Desktop's mouse-driven hover/drag behavior is unchanged.
 *
 * The outer `width(8.dp)` is kept even on touch so callers (`HomeScreen`'s
 * `TRIPLE_PANE_MIN_WIDTH`/`triplePaneWidths` math) don't need a separate touch-width case.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default.
 */
@Composable
internal fun ResizableDivider(
    onDrag: (deltaPx: Float) -> Unit,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    if (isTouchPrimary) {
        Box(modifier = Modifier.fillMaxHeight().width(8.dp), contentAlignment = Alignment.Center) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(8.dp)
            .pointerHoverIcon(CursorIcons.horizontalResize)
            .hoverable(interactionSource)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(delta) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) {
            VerticalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

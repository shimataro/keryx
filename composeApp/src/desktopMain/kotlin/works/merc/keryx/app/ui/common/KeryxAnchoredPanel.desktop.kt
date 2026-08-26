package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Desktop `actual`: the former raw `Popup` call, unchanged — see the `expect`'s KDoc in
 * `commonMain`.
 */
@Composable
actual fun KeryxAnchoredPanel(
    onDismissRequest: () -> Unit,
    alignment: Alignment,
    anchorOffsetY: Dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = alignment,
        offset = IntOffset(x = 0, y = with(density) { anchorOffsetY.roundToPx() }),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        content()
    }
}

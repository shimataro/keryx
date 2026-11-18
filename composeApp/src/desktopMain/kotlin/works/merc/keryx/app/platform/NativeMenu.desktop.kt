package works.merc.keryx.app.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** A [LazyNativePopup] for [window], disposed once it leaves composition or [window] changes. */
@Composable
private fun rememberLazyNativePopup(window: NativeWindowHandle?): LazyNativePopup {
    val popup = remember(window) { LazyNativePopup(window = window) }
    DisposableEffect(popup) { onDispose { popup.dispose() } }
    return popup
}

/** Converts a window-pixel coordinate into the Java user-space coordinate the native menus take. */
private fun Float.toUserSpace(density: Density): Int = (this / density.density).toInt()

/**
 * Adds a native context menu that opens when the secondary mouse button is pressed.
 *
 * @param items Provides the current menu entries.
 * @param onOpen Called when the context menu is requested.
 * @return A modifier that handles native context menu interaction.
 */
@Composable
actual fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit,
    hitTest: ((Offset) -> Boolean)?,
): Modifier {
    val window = LocalNativeWindow.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentOnOpen by rememberUpdatedState(onOpen)
    // A plain holder, not a snapshot state: this is written from onGloballyPositioned on every
    // layout of every attached element (so, per row per scroll frame) and only ever read from the
    // suspending pointer handler below, never from composition. As snapshot state it would record
    // a write per layout for no reader.
    val elementPosition = remember { FloatArray(2) }

    // Nothing native is built until the first right-click — see LazyNativePopup.
    val popup = rememberLazyNativePopup(window)

    return this
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            elementPosition[0] = position.x
            elementPosition[1] = position.y
        }
        .pointerInput(window, popup) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent()
                    if (
                        event.type == PointerEventType.Press &&
                        event.buttons.isSecondaryPressed &&
                        event.changes.none { it.isConsumed }
                    ) {
                        val localPosition = event.changes.first().position
                        if (hitTest?.invoke(localPosition) == false) continue
                        event.changes.forEach { it.consume() }
                        currentOnOpen()
                        val entries = currentItems()
                        if (entries.isNotEmpty()) {
                            val win = window ?: continue
                            val x = (elementPosition[0] + localPosition.x).toUserSpace(density)
                            val y = (elementPosition[1] + localPosition.y).toUserSpace(density)
                            // Whether this is synchronous is the backend's call — see
                            // SwingPopupHandle.show.
                            popup.showFor(entries, win.contentPane, x, y)
                        }
                    }
                }
            }
        }
}

/**
 * Desktop `actual`: the same [LazyNativePopup] [nativeContextMenu] uses, shown at the anchor's
 * bottom-left corner instead of at the pointer.
 */
@Composable
actual fun NativeMenuAnchor(
    items: () -> List<NativeMenuEntry>,
    content: @Composable (open: () -> Unit) -> Unit,
) {
    val window = LocalNativeWindow.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    // Bottom-left corner in window pixels; a plain holder for the same reason as nativeContextMenu's.
    val anchorCorner = remember { FloatArray(2) }
    val popup = rememberLazyNativePopup(window)

    Box(
        Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            anchorCorner[0] = position.x
            anchorCorner[1] = position.y + coordinates.size.height
        },
    ) {
        content {
            val entries = currentItems()
            val win = window
            if (entries.isNotEmpty() && win != null) {
                val x = anchorCorner[0].toUserSpace(density)
                val y = anchorCorner[1].toUserSpace(density)
                popup.showFor(entries, win.contentPane, x, y)
            }
        }
    }
}

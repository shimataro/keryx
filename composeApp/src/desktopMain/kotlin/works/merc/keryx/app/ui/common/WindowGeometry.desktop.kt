package works.merc.keryx.app.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.abs

/** Slack (in Dp/AWT points) when comparing the native window's size against the requested one, so
 * rounding between Compose's Dp and AWT's integer point sizes doesn't look like a mismatch. */
private const val FIT_TOLERANCE = 2f

/** Offset (from the captured cursor position) at which a dialog opens, similar to a context menu
 * appearing slightly below-and-right of the click rather than directly under the pointer. */
private val CURSOR_OFFSET = 16.dp

/**
 * Initial window size before the first auto-fit pass, for a given [initialWidth] (see
 * `DesktopModalWindow`'s parameter of the same name). Width must match whatever fixed width the
 * caller's content actually requests — `Modifier.width(x)` (unlike `requiredWidth`) clamps to the
 * *incoming* max-width constraint, so if the window starts narrower than `x`, the content measures
 * (and gets stuck) at that narrower width forever: `onSizeChanged` only re-fires when the measured
 * size changes, and content laid out inside an already-too-narrow window keeps reporting that same
 * narrow size on every subsequent pass. [KeryxAlertDialog] relies on the default
 * (`KERYX_ALERT_DIALOG_WIDTH`); [KeryxTabDialog] passes its own wider fixed width explicitly so it
 * doesn't inherit — and get stuck at — the alert dialog's narrower one. Height is a rough
 * placeholder that auto-fit immediately corrects (no equivalent trap for a too-narrow width).
 */
internal fun placeholderSize(initialWidth: Dp) = DpSize(initialWidth, 240.dp)

/** Whether [window] has actually reached [target]. `DialogState.size` (Dp) and AWT window bounds
 * (points) are the same unit on desktop, so these compare directly. */
internal fun windowMatches(window: Window, target: DpSize): Boolean =
    abs(window.width - target.width.value) <= FIT_TOLERANCE &&
        abs(window.height - target.height.value) <= FIT_TOLERANCE

/** Computes a [WindowPosition.Absolute] that centers a window of [size] over [owner], falling
 * back to a platform-default (screen-centered) position when there is no owner window yet.
 * [owner]'s AWT bounds are in "points" (the same density-independent space Compose's Dp uses at
 * density 1.0 — see `MAX_HEIGHT_FRACTION`'s usage), so this stays entirely in Dp; no density
 * conversion is needed (or correct) here. */
internal fun centeredPosition(owner: Window?, size: DpSize): WindowPosition {
    if (owner == null) return WindowPosition.PlatformDefault
    val x = owner.x.dp + (owner.width.dp - size.width) / 2f
    val y = owner.y.dp + (owner.height.dp - size.height) / 2f
    return WindowPosition.Absolute(x, y)
}

/** Finds the bounds of the screen containing [cursor], falling back to [owner]'s screen, and
 * finally the platform's default screen device, when [cursor] is null or off any known screen. */
internal fun currentScreenBounds(cursor: Point?, owner: Window?): Rectangle {
    val graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    if (cursor != null) {
        for (device in graphicsEnvironment.screenDevices) {
            val bounds = device.defaultConfiguration.bounds
            if (bounds.contains(cursor)) return bounds
        }
    }
    owner?.graphicsConfiguration?.bounds?.let { return it }
    return graphicsEnvironment.defaultScreenDevice.defaultConfiguration.bounds
}

/** Computes a [WindowPosition.Absolute] a little below-and-right of [cursor] (see
 * [CURSOR_OFFSET]), clamped so a window of [size] stays fully within [screenBounds]. [cursor] and
 * [screenBounds] are both AWT "points" (same space as Compose's Dp at density 1.0 — see
 * [centeredPosition]), so this stays entirely in Dp; no density conversion needed. */
internal fun cursorAnchoredPosition(cursor: Point, screenBounds: Rectangle, size: DpSize): WindowPosition {
    val minX = screenBounds.x.dp
    val minY = screenBounds.y.dp
    val maxX = (screenBounds.x.dp + screenBounds.width.dp - size.width).coerceAtLeast(minX)
    val maxY = (screenBounds.y.dp + screenBounds.height.dp - size.height).coerceAtLeast(minY)
    val x = (cursor.x.dp + CURSOR_OFFSET).coerceIn(minX, maxX)
    val y = (cursor.y.dp + CURSOR_OFFSET).coerceIn(minY, maxY)
    return WindowPosition.Absolute(x, y)
}

/** [cursorAnchoredPosition] when a cursor position was captured, otherwise the previous
 * owner-centered behavior. */
internal fun resolvePosition(cursor: Point?, owner: Window?, screenBounds: Rectangle, size: DpSize): WindowPosition =
    if (cursor != null) cursorAnchoredPosition(cursor, screenBounds, size) else centeredPosition(owner, size)

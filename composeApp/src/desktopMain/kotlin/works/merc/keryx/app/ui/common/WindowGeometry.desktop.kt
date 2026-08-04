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
 * Provides the initial dialog size used before the first auto-fit pass.
 *
 * @param initialWidth The fixed width requested by the dialog content.
 * @return A size with [initialWidth] and a placeholder height of 240.dp.
 */
internal fun placeholderSize(initialWidth: Dp) = DpSize(initialWidth, 240.dp)

/**
         * Determines whether the window dimensions are within the allowed fit tolerance of the target size.
         *
         * @return `true` if both dimensions are within the fit tolerance, `false` otherwise.
         */
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
    val screenBounds = currentScreenBounds(cursor = null, owner = owner)
    val minX = screenBounds.x.dp
    val minY = screenBounds.y.dp
    val maxX = (screenBounds.x.dp + screenBounds.width.dp - size.width).coerceAtLeast(minX)
    val maxY = (screenBounds.y.dp + screenBounds.height.dp - size.height).coerceAtLeast(minY)
    val x = (owner.x.dp + (owner.width.dp - size.width) / 2f).coerceIn(minX, maxX)
    val y = (owner.y.dp + (owner.height.dp - size.height) / 2f).coerceIn(minY, maxY)
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

/**
 * Positions a window offset from the cursor while keeping it within the screen bounds.
 *
 * @param cursor The cursor position.
 * @param screenBounds The available screen area.
 * @param size The window size.
 * @return The absolute window position.
 */
internal fun cursorAnchoredPosition(cursor: Point, screenBounds: Rectangle, size: DpSize): WindowPosition {
    val minX = screenBounds.x.dp
    val minY = screenBounds.y.dp
    val maxX = (screenBounds.x.dp + screenBounds.width.dp - size.width).coerceAtLeast(minX)
    val maxY = (screenBounds.y.dp + screenBounds.height.dp - size.height).coerceAtLeast(minY)
    val x = (cursor.x.dp + CURSOR_OFFSET).coerceIn(minX, maxX)
    val y = (cursor.y.dp + CURSOR_OFFSET).coerceIn(minY, maxY)
    return WindowPosition.Absolute(x, y)
}

/**
     * Resolves a dialog position using the captured cursor position when available, or centers it over the owner window.
     *
     * @param cursor The captured cursor position, if available.
     * @param owner The owner window used for centering when no cursor position is available.
     * @param screenBounds The bounds of the screen used for cursor anchoring.
     * @param size The dialog size.
     * @return The resolved dialog position.
     */
internal fun resolvePosition(cursor: Point?, owner: Window?, screenBounds: Rectangle, size: DpSize): WindowPosition =
    if (cursor != null) cursorAnchoredPosition(cursor, screenBounds, size) else centeredPosition(owner, size)

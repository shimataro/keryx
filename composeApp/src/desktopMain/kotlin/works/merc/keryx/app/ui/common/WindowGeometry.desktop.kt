package works.merc.keryx.app.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/** Slack (in Dp/AWT points) when comparing the native window's size against the requested one, so
 * rounding between Compose's Dp and AWT's integer point sizes doesn't look like a mismatch. */
private const val FIT_TOLERANCE = 2f

/**
 * How many native size applications [nextDialogFit] spends on a single target before giving up.
 * One is all a healthy dialog needs; the rest of the budget absorbs a size AWT or the window
 * manager applies behind Compose's back while the dialog is being realized. The cap exists so a
 * window manager that refuses the requested geometry — it re-applies its own size, which arrives
 * back as another drift event — cannot spin the guard forever. It is reset whenever the target
 * changes, and deliberately NOT when the window momentarily matches: an apply-then-snap-back
 * window manager does produce matching events, and refilling the budget on those would make the
 * fight immortal.
 */
internal const val MAX_FIT_CORRECTIONS = 5

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
 * Determines whether two window sizes are within [FIT_TOLERANCE] of each other in both dimensions.
 *
 * @param actual The size the window currently has.
 * @param target The size it was asked for.
 * @return `true` if both dimensions are within the fit tolerance, `false` otherwise.
 */
internal fun sizeMatches(actual: DpSize, target: DpSize): Boolean =
    abs(actual.width.value - target.width.value) <= FIT_TOLERANCE &&
        abs(actual.height.value - target.height.value) <= FIT_TOLERANCE

/**
 * Reads the native window's current outer size. AWT's integer bounds are in "points", the same
 * density-independent space Compose's Dp uses at density 1.0 (Compose's own window sizing rounds
 * `Dp.value` straight to AWT points), so no density conversion is needed — or correct — here.
 *
 * @param window The native window.
 * @return Its current outer size.
 */
internal fun windowSize(window: Window): DpSize = DpSize(window.width.dp, window.height.dp)

/**
 * Pushes [size] straight onto the native window, mirroring the Dp -> AWT-point rounding Compose's
 * own window sizing performs so the result is directly comparable via [sizeMatches].
 *
 * @param window The native window.
 * @param size The size to apply.
 */
internal fun applyWindowSize(window: Window, size: DpSize) {
    window.setSize(size.width.value.roundToInt().coerceAtLeast(0), size.height.value.roundToInt().coerceAtLeast(0))
}

/**
 * Computes the OS-window size that fits content measured at [contentPx].
 *
 * [density] must be the *dialog's* own density: [contentPx] comes out of the dialog's layout pass,
 * so converting it with the owner window's density is off by the ratio between the two whenever
 * owner and dialog sit on screens with different scale factors.
 *
 * The height cap is applied to the content *before* [decorationAllowance] is added, so the result
 * can exceed [maxHeightDp] by exactly the allowance — decoration is chrome the content never
 * occupies, and the cap is about how much of the screen the content may claim.
 *
 * @param contentPx Measured content size, in the dialog composition's pixels.
 * @param density The dialog composition's density.
 * @param maxHeightDp Height cap applied to the content.
 * @param decorationAllowance Extra height for OS-drawn decoration the content does not include.
 * @return The window size to request, or `null` for a degenerate measurement (which must never
 *   become a zero-sized window).
 */
internal fun fitWindowSize(
    contentPx: IntSize,
    density: Density,
    maxHeightDp: Dp,
    decorationAllowance: Dp,
): DpSize? {
    if (contentPx.width <= 0 || contentPx.height <= 0) return null
    return with(density) {
        val clampedHeightPx = contentPx.height.toFloat().coerceAtMost(maxHeightDp.toPx())
        DpSize(contentPx.width.toDp(), clampedHeightPx.toDp() + decorationAllowance)
    }
}

/**
 * Drift-guard state carried between [nextDialogFit] calls for one dialog window.
 *
 * @property target The size last computed from measured content; `null` before the first measurement.
 * @property corrections How many size applications have been spent on [target].
 * @property positionApplied Whether the dialog has been placed at least once.
 * @property gaveUpReported Whether giving up on [target] has already been reported, so the warning
 *   is logged once per target rather than on every subsequent drift event.
 */
internal data class DialogFitState(
    val target: DpSize? = null,
    val corrections: Int = 0,
    val positionApplied: Boolean = false,
    val gaveUpReported: Boolean = false,
)

/**
 * What to do about one drift-guard event, plus the state to carry into the next one.
 *
 * @property state The state to pass to the next [nextDialogFit] call.
 * @property applySize Whether the window should be resized to the target.
 * @property applyPosition Whether the window should be (re)placed.
 * @property reportGiveUp Whether this event is the one that exhausted [MAX_FIT_CORRECTIONS] for the
 *   current target, and should therefore be logged.
 */
internal data class DialogFitDecision(
    val state: DialogFitState,
    val applySize: Boolean,
    val applyPosition: Boolean,
    val reportGiveUp: Boolean,
)

/**
 * Decides how to react to one observation of "content wants [target], window is at [actual]".
 *
 * Position is re-applied only when the dialog has never been placed, or when the target itself
 * changed and the caller opted into repositioning: a mere drift correction must not yank a dialog
 * the user has dragged elsewhere, and a tabbed dialog ([repositionOnResize] = `false`) must keep
 * its top edge fixed while its height follows each tab.
 *
 * @param state The state returned by the previous call, or a default instance.
 * @param target The size the window should have for the currently measured content.
 * @param actual The window's current size.
 * @param repositionOnResize Whether the dialog re-places itself when its target size changes.
 * @return The decision for this event.
 */
internal fun nextDialogFit(
    state: DialogFitState,
    target: DpSize,
    actual: DpSize,
    repositionOnResize: Boolean,
): DialogFitDecision {
    val targetChanged = state.target != target
    val corrections = if (targetChanged) 0 else state.corrections
    val gaveUpReported = !targetChanged && state.gaveUpReported

    val matched = sizeMatches(actual, target)
    val applySize = !matched && corrections < MAX_FIT_CORRECTIONS
    val applyPosition = !state.positionApplied || (repositionOnResize && targetChanged)
    val reportGiveUp = !matched && !applySize && !gaveUpReported

    return DialogFitDecision(
        state = DialogFitState(
            target = target,
            corrections = if (applySize) corrections + 1 else corrections,
            positionApplied = state.positionApplied || applyPosition,
            gaveUpReported = gaveUpReported || reportGiveUp,
        ),
        applySize = applySize,
        applyPosition = applyPosition,
        reportGiveUp = reportGiveUp,
    )
}

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

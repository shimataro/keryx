package works.merc.keryx.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import works.merc.keryx.app.core.WINDOW_DEFAULT_HEIGHT
import works.merc.keryx.app.core.WINDOW_DEFAULT_WIDTH
import works.merc.keryx.app.core.WINDOW_MIN_HEIGHT
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import works.merc.keryx.app.data.local.LocalSettings
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

private const val WINDOW_PLACEMENT_FLOATING = "floating"
private const val WINDOW_PLACEMENT_MAXIMIZED = "maximized"
private const val WINDOW_PLACEMENT_FULLSCREEN = "fullscreen"

/** Height (in Dp/AWT points) of the band along a window's top edge treated as its "title bar" for
 * [isVisibleOnAnyScreen] — the part a user could still drag back onto a screen even if the rest of
 * the window has drifted off it. */
private const val TITLE_BAR_HEIGHT_DP = 40

/** An immutable snapshot of a [WindowState]'s observable properties, taken via `snapshotFlow` on
 * the UI thread and safe to hand off (e.g. through an `AtomicReference`) to a non-Compose caller
 * such as a JVM shutdown hook. */
internal data class ObservedWindowState(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val size: DpSize,
    val position: WindowPosition,
)

/** The values a freshly created [WindowState] should be given at startup. */
internal data class RestoredWindowState(
    val placement: WindowPlacement,
    val position: WindowPosition,
    val size: DpSize,
)

/** Snapshots the current, observable properties of [state]. */
internal fun observedWindowState(state: WindowState): ObservedWindowState =
    ObservedWindowState(
        placement = state.placement,
        isMinimized = state.isMinimized,
        size = state.size,
        position = state.position,
    )

private fun placementFromSaved(value: String): WindowPlacement = when (value) {
    WINDOW_PLACEMENT_MAXIMIZED -> WindowPlacement.Maximized
    WINDOW_PLACEMENT_FULLSCREEN -> WindowPlacement.Fullscreen
    else -> WindowPlacement.Floating
}

private fun placementToSaved(placement: WindowPlacement): String = when (placement) {
    WindowPlacement.Maximized -> WINDOW_PLACEMENT_MAXIMIZED
    WindowPlacement.Fullscreen -> WINDOW_PLACEMENT_FULLSCREEN
    else -> WINDOW_PLACEMENT_FLOATING
}

/**
 * Determines whether a window with the given floating bounds would have its title bar reachable
 * on at least one of [screens] — i.e. whether restoring it there is safe, as opposed to leaving it
 * stranded off-screen (e.g. a saved position on a monitor that has since been disconnected).
 */
internal fun isVisibleOnAnyScreen(x: Double, y: Double, width: Double, height: Double, screens: List<Rectangle>): Boolean {
    val titleBar = Rectangle(x.toInt(), y.toInt(), width.toInt().coerceAtLeast(1), TITLE_BAR_HEIGHT_DP)
    return screens.any { it.intersects(titleBar) }
}

/**
 * Computes the initial placement/position/size for the main window's [WindowState], from the last
 * persisted [LocalSettings] and the currently connected [screens].
 *
 * Deliberately returns the floating size/position even when [saved]'s placement is maximized or
 * fullscreen: passed into the initial `WindowState`, this becomes — via how Compose Desktop applies
 * window properties before the window is first shown (`setSizeSafely`/`setPositionSafely` apply
 * regardless of placement while the window isn't yet visible) — the bounds the OS itself restores
 * to once the user un-maximizes/exits fullscreen. Skipping this and passing `Dp.Unspecified`
 * instead (as an earlier version of this code did) left the un-maximized bounds equal to the full
 * screen, so un-maximizing never actually returned the window to its pre-maximize size.
 */
internal fun restoredWindowState(saved: LocalSettings, screens: List<Rectangle>): RestoredWindowState {
    val width = (saved.windowWidth ?: WINDOW_DEFAULT_WIDTH.toDouble()).coerceAtLeast(WINDOW_MIN_WIDTH.toDouble())
    val height = (saved.windowHeight ?: WINDOW_DEFAULT_HEIGHT.toDouble()).coerceAtLeast(WINDOW_MIN_HEIGHT.toDouble())
    val x = saved.windowX
    val y = saved.windowY
    val position = if (x != null && y != null && isVisibleOnAnyScreen(x, y, width, height, screens)) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition.Aligned(Alignment.Center)
    }
    return RestoredWindowState(
        placement = placementFromSaved(saved.windowPlacement),
        position = position,
        size = DpSize(width.dp, height.dp),
    )
}

/**
 * Applies [observed] to [current], producing the [LocalSettings] to persist.
 *
 * - While minimized, nothing is changed at all: minimizing hides the window without changing
 *   where/how it should reappear, so whatever was true immediately before the minimize (floating
 *   bounds, or maximized/fullscreen) is exactly what should still be there on the next launch.
 * - Placement is always updated (except while minimized, per above).
 * - Floating size/position are only updated while actually floating, so the values recorded are
 *   always the last known "un-maximized" bounds — see [restoredWindowState]'s KDoc for why that
 *   matters. [ObservedWindowState.position] is only ever a `WindowPosition.Absolute` in practice
 *   (an AWT-backed window always reports absolute coordinates), but the `is` check keeps this
 *   total rather than assuming it.
 */
internal fun persistedWindowState(current: LocalSettings, observed: ObservedWindowState): LocalSettings {
    if (observed.isMinimized) return current

    val isFloating = observed.placement == WindowPlacement.Floating
    val position = observed.position as? WindowPosition.Absolute

    return current.copy(
        windowPlacement = placementToSaved(observed.placement),
        windowWidth = if (isFloating) observed.size.width.value.toDouble() else current.windowWidth,
        windowHeight = if (isFloating) observed.size.height.value.toDouble() else current.windowHeight,
        windowX = if (isFloating && position != null) position.x.value.toDouble() else current.windowX,
        windowY = if (isFloating && position != null) position.y.value.toDouble() else current.windowY,
    )
}

/** The bounds of every currently connected screen, in AWT points. The sole side-effecting piece of
 * this file — kept separate so the functions above stay pure and testable. */
internal fun screenBounds(): List<Rectangle> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }

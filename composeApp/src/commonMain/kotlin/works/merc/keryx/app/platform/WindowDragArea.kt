package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Wrapper for the window-move drag area.
 *
 * On macOS, integrating the title bar into the content loses the OS's built-in drag-to-move, so
 * desktop's `main.kt` provides a wrapper around `FrameWindowScope.WindowDraggableArea` only on
 * macOS. Elsewhere (Windows/Linux, or non-desktop) the OS title bar handles it, so this stays a
 * default pass-through (no-op).
 *
 * The value is set once at startup and never changes, hence [staticCompositionLocalOf] (same
 * reason as [LocalNativeWindow]).
 */
val LocalWindowDragArea:
    ProvidableCompositionLocal<@Composable (Modifier, @Composable () -> Unit) -> Unit> =
    staticCompositionLocalOf { { _, content -> content() } }

/** Wraps [content] with the [LocalWindowDragArea] wrapper, making its free space a drag area for moving the window. */
@Composable
fun WindowDragArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LocalWindowDragArea.current(modifier, content)
}

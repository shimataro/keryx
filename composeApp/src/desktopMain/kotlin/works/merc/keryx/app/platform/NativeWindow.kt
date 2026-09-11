package works.merc.keryx.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow

/** Opaque handle to the native window (`ComposeWindow`). Desktop-only — nothing here reads it on
 *  Android, whose context menus are a real M3 `DropdownMenu` with no need for an `Activity` handle. */
typealias NativeWindowHandle = ComposeWindow

/** Provides the current [NativeWindowHandle], or `null` before the window is available. There is
 *  only ever one window for the lifetime of this desktop app, so a static local avoids the
 *  read-tracking overhead of `compositionLocalOf`. */
val LocalNativeWindow: ProvidableCompositionLocal<NativeWindowHandle?> =
    staticCompositionLocalOf { null }

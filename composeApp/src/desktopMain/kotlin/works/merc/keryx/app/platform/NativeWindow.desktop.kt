package works.merc.keryx.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow

actual typealias NativeWindowHandle = ComposeWindow

// There is only ever one window for the lifetime of this desktop app, so a
// static local avoids the read-tracking overhead of compositionLocalOf.
actual val LocalNativeWindow: ProvidableCompositionLocal<NativeWindowHandle?> =
    staticCompositionLocalOf { null }

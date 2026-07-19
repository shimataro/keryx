package works.merc.keryx.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal

/** Opaque handle to the platform's native window (`ComposeWindow` on desktop). */
expect class NativeWindowHandle

/** Provides the current [NativeWindowHandle], or `null` before the window is available. */
expect val LocalNativeWindow: ProvidableCompositionLocal<NativeWindowHandle?>

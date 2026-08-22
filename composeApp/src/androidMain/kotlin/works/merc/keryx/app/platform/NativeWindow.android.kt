package works.merc.keryx.app.platform

import android.app.Activity
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

actual typealias NativeWindowHandle = Activity

// Unlike desktop (a single long-lived window, hence its staticCompositionLocalOf), an Activity
// can be recreated (rotation, process death), so a plain compositionLocalOf is used instead.
actual val LocalNativeWindow: ProvidableCompositionLocal<NativeWindowHandle?> =
    compositionLocalOf { null }

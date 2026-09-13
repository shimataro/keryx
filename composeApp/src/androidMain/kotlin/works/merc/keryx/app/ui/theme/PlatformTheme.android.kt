package works.merc.keryx.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** M3's own default corner-radius scale — "native" on Android means Material 3's own shapes. */
actual val platformShapes: Shapes = Shapes()

/**
 * No overrides: leaving [androidx.compose.foundation.LocalIndication] and
 * [androidx.compose.material3.LocalRippleConfiguration] at their M3 defaults is exactly what makes
 * every `clickable`/`selectable`/`toggleable` and every M3 component show its own real ripple,
 * matching Android's native press feedback.
 */
actual @Composable fun ProvidePlatformInteraction(dark: Boolean, content: @Composable () -> Unit) = content()

/**
 * Returns a dynamic color scheme on Android 12+ (API 31), derived from the system wallpaper.
 * On older versions, falls back to the app's brand color scheme.
 */
@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
}

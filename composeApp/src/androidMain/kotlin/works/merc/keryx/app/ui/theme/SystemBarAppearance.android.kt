package works.merc.keryx.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The app's resolved dark flag (from `KeryxTheme`), provided by the Android
 * [ProvidePlatformInteraction] so composables hosted in a window of their own (a `Dialog`) can
 * apply the same system-bar appearance as the Activity window via [SyncSystemBarAppearance].
 */
internal val LocalAppDarkTheme = staticCompositionLocalOf { false }

// The same scrims androidx.activity's default `enableEdgeToEdge()` uses for the navigation bar
// (its own constants are private). They are only drawn on API 26–28, where the system cannot
// enforce navigation-bar contrast itself; API 29+ keeps the bar transparent.
private val LightNavigationScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Makes the status/navigation bar icons of the window hosting this composition follow the app's
 * own [dark] theme rather than the system's — without this, an in-app dark theme on a
 * system-light device leaves dark icons on a dark background (and vice versa).
 *
 * The single implementation for every window the app shows:
 * - **A `Dialog`'s window** (detected through [DialogWindowProvider], the documented way to reach
 *   it from inside its content) gets the icon appearance set directly.
 * - **The Activity's window** re-runs `enableEdgeToEdge` with styles keyed on [dark] (instead of
 *   the system dark mode `MainActivity`'s initial call defaults to), so on API 26–28 the
 *   navigation-bar scrim flips together with the icons rather than leaving light icons on a light
 *   scrim.
 *
 * Runs as a [SideEffect], so a theme change applies on the next successful composition.
 */
@Composable
internal fun SyncSystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            return@SideEffect
        }
        val activity = view.context.findActivity() as? ComponentActivity ?: return@SideEffect
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { dark },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package works.merc.keryx.app.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable

/** M3's own default corner-radius scale — "native" on Android means Material 3's own shapes. */
actual val platformShapes: Shapes = Shapes()

/**
 * No overrides: leaving [androidx.compose.foundation.LocalIndication] and
 * [androidx.compose.material3.LocalRippleConfiguration] at their M3 defaults is exactly what makes
 * every `clickable`/`selectable`/`toggleable` and every M3 component show its own real ripple,
 * matching Android's native press feedback.
 */
actual @Composable fun ProvidePlatformInteraction(dark: Boolean, content: @Composable () -> Unit) = content()

package works.merc.keryx.app.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable

/**
 * The platform's own corner-radius scale, applied as [androidx.compose.material3.MaterialTheme]'s
 * `shapes`. Desktop uses a tighter scale than M3's default (see the desktop `actual`'s own KDoc);
 * Android uses M3's own default scale, since Material 3 shapes are what "native" means there.
 */
expect val platformShapes: Shapes

/**
 * Wraps [content] in whatever [androidx.compose.runtime.CompositionLocal]s this platform's press
 * feedback needs — desktop overrides them for a flat, non-ripple feel (see the desktop `actual`'s
 * own KDoc); Android provides nothing extra, leaving M3's own ripple in effect, since a visible
 * ripple is exactly what "native" means there.
 *
 * @param dark Whether dark theme colors are currently resolved, for platforms whose press overlay
 * needs a different alpha per theme (see the desktop `actual`).
 */
@Composable
expect fun ProvidePlatformInteraction(dark: Boolean, content: @Composable () -> Unit)

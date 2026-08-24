package works.merc.keryx.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * A raised content container for grouping related rows within a screen or a floating popup — the
 * app's own "flat surface pattern" on desktop (hairline `outlineVariant` border, no tonal
 * elevation, matching the `ui-guidelines` skill), M3's own tonal-container elevation on Android
 * (a distinctly-tinted surface tier, no border), rather than one shared look. Used by
 * `SettingsCard`, `TagColorPicker`'s popup, `SetupScreen.OptionCard`, and
 * `NotificationCenterSheet` — as opposed to [KeryxAlertDialog]'s modal surface, which already has
 * its own `expect`/`actual` split and is unaffected by this one.
 */
@Composable
expect fun KeryxRaisedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
)

package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An icon button with a tooltip, following each platform's own idiom rather than one shared look —
 * see the `ui-guidelines` skill's "Platform-native chrome components" section. Desktop re-implements
 * M3's `IconButton` with the app's own flat, hover-driven press feedback; Android uses M3's own
 * `IconButton` + `TooltipBox` verbatim, since a real ripple and a long-press-triggered tooltip are
 * exactly what "native" means there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
expect fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
)

/**
 * The tooltip content shown by [TooltipIconButton] and other [androidx.compose.material3.TooltipBox]
 * call sites in this app (`LinkRow`, the feed-gone indicator) — an extension on
 * [TooltipScope] so it can be passed straight into a `tooltip = { … }` slot, and so the Android
 * `actual` can forward to M3's own `PlainTooltip` (which is itself a `TooltipScope` extension).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal expect fun TooltipScope.FlatTooltipContent(text: String)

/**
 * Groups related toolbar icons — a rounded capsule (macOS-toolbar-style clustering) on desktop, a
 * plain unadorned row on Android, since M3's own toolbars don't wrap their icons in a container.
 * See the `ui-guidelines` skill.
 */
@Composable
expect fun ToolbarIconGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit)

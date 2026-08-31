package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much container emphasis a [TooltipIconButton] carries. Android maps these onto M3's own
 * icon-button family; desktop onto the same flat tokens `FlatButton`/`FlatTonalButton` use.
 *
 * A kind's container tone must not equal the tone of the row it sits on — see [Secondary].
 */
enum class IconButtonKind {
    /** No container at all — the pane toolbars' own look, and the default. */
    Standard,

    /** The surface's own main action (a provider row's "connect"): `primary` fill. */
    Primary,

    /**
     * A secondary action that must still read as a button. Outlined rather than tonal-filled
     * because a tonal fill is `secondaryContainer`, which is exactly the tone the connected
     * provider row itself is painted with — the container would vanish into it.
     */
    Secondary,

    /** A destructive action: `errorContainer` fill (see the `ui-guidelines` skill for why not `error`). */
    Destructive,
}

/**
 * An icon button with a tooltip, following each platform's own idiom rather than one shared look —
 * see the `ui-guidelines` skill's "Platform-native chrome components" section. Desktop re-implements
 * M3's icon buttons with the app's own flat, hover-driven press feedback; Android uses M3's own
 * icon-button family + `TooltipBox` verbatim, since a real ripple and a long-press-triggered tooltip
 * are exactly what "native" means there.
 *
 * @param kind How much container emphasis the button carries — see [IconButtonKind].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
expect fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    kind: IconButtonKind = IconButtonKind.Standard,
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

package works.merc.keryx.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Flat, non-Material-ripple icon button + tooltip. Re-implements the parts of M3's `IconButton`
 * (min tap target, circular clip, click handling) with plain [Modifier]s so that pressing it uses
 * a hover-driven flat highlight instead of M3's hardcoded ripple. See
 * `.claude/skills/ui-guidelines/SKILL.md`.
 *
 * [kind] adds a container on top of that: the same flat tokens `FlatButton`/`FlatTonalButton` use.
 * None of the per-kind colors branch on [enabled] — the single `alpha(0.38f)` below dims the whole
 * `Box` (container included) when disabled, so a kind never needs its own disabled palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    size: Dp,
    kind: IconButtonKind,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(tooltip) },
        state = rememberTooltipState(),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val hovered by interactionSource.collectIsHoveredAsState()
        val colorScheme = MaterialTheme.colorScheme
        val container = when (kind) {
            IconButtonKind.Standard, IconButtonKind.Secondary -> Color.Transparent
            IconButtonKind.Primary -> colorScheme.primary
            IconButtonKind.Destructive -> colorScheme.errorContainer
        }
        val contentColor = when (kind) {
            IconButtonKind.Standard, IconButtonKind.Secondary -> LocalContentColor.current
            IconButtonKind.Primary -> colorScheme.onPrimary
            IconButtonKind.Destructive -> colorScheme.onErrorContainer
        }
        val border = if (kind == IconButtonKind.Secondary) colorScheme.outlineVariant else null
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Default)
                .hoverable(interactionSource, enabled = enabled)
                .background(container)
                // Layered over the kind's own fill rather than replacing it, so `Standard`
                // (transparent) keeps exactly the look it has always had.
                .background(if (enabled && hovered) colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(interactionSource = interactionSource, onClick = onClick, role = Role.Button, enabled = enabled),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

/**
 * The app's flat tooltip surface (shared by [TooltipIconButton] and settings' link rows): a
 * hairline-bordered [Surface] with no tonal elevation, matching the "flat surface pattern" in
 * `.claude/skills/ui-guidelines/SKILL.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun TooltipScope.FlatTooltipContent(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Groups related toolbar icons into a rounded capsule (macOS-toolbar-style clustering). See
 * `.claude/skills/ui-guidelines/SKILL.md` — this is a flat-fill approximation pending a native replacement
 * (e.g. SwiftUI's glass toolbar grouping) when this app gets a native SwiftUI UI.
 */
@Composable
actual fun ToolbarIconGroup(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

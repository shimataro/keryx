package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * A plain M3 `IconButton` inside a `TooltipBox` — `TooltipBox` already triggers on long-press on a
 * touch-primary platform (matching Android's own tooltip convention) without any extra gesture
 * wiring, and `IconButton` supplies a real ripple, so nothing here needs to reimplement press
 * feedback the way the desktop `actual` does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    size: Dp,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(tooltip) },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** M3's own tooltip look — a plain [PlainTooltip], matching every other native Android surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun TooltipScope.FlatTooltipContent(text: String) {
    PlainTooltip { Text(text) }
}

/**
 * No capsule here — M3's own toolbars don't wrap their icons in a container, unlike the macOS
 * toolbar clustering this mimics on desktop. See `.claude/skills/ui-guidelines/SKILL.md`.
 */
@Composable
actual fun ToolbarIconGroup(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, content = content)
}

package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
 * M3's own icon-button family inside a `TooltipBox` — `TooltipBox` already triggers on long-press on
 * a touch-primary platform (matching Android's own tooltip convention) without any extra gesture
 * wiring, and every one of these buttons supplies a real ripple, so nothing here needs to
 * reimplement press feedback the way the desktop `actual` does.
 *
 * [kind] selects which member of that family draws the container: `IconButton` (none),
 * `FilledIconButton` (`primary`), `OutlinedIconButton` (a hairline outline) and `FilledTonalIconButton`
 * recolored to `errorContainer`. Only that last one needs explicit colors — the others already read
 * the theme's own tokens. Disabled colors are left at M3's defaults (`onSurface` at 12%/38%).
 *
 * Each of these applies `minimumInteractiveComponentSize()` internally, so the [size] default of
 * 40dp still meets M3's 48dp minimum touch target while looking 40dp.
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
        val sized = modifier.size(size)
        when (kind) {
            IconButtonKind.Standard ->
                IconButton(onClick = onClick, modifier = sized, enabled = enabled, content = content)
            IconButtonKind.Primary ->
                FilledIconButton(onClick = onClick, modifier = sized, enabled = enabled, content = content)
            IconButtonKind.Secondary ->
                OutlinedIconButton(onClick = onClick, modifier = sized, enabled = enabled, content = content)
            IconButtonKind.Destructive ->
                FilledTonalIconButton(
                    onClick = onClick,
                    modifier = sized,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    content = content,
                )
        }
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

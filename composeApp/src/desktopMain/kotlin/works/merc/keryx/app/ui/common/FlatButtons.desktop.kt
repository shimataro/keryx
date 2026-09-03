package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The content color plus the label text style, shared by all three `actual`s below.
 *
 * The text style is not cosmetic. `TextStyle.Default` — what a bare `Text` inside the button
 * would otherwise use — leaves `lineHeight` unspecified, so the label's height, and with it the
 * whole button's, comes from whatever font the host resolves for the label's own glyphs. Two
 * buttons whose labels are worded differently then measure differently on one machine and
 * identically on another: the macOS CI runner measured "ダウンロード" at 16dp and
 * "再起動しています…" at 20dp, which is what made the Updates tab's headline row change height
 * between `UpdateState.Available` and `UpdateState.Installing` there and nowhere else (see
 * `FlatButtonsTest` and `UpdatesTabTest.theHeadlineRowIsTheSameHeightWithOrWithoutATrailingButton`).
 * `labelLarge` pins the line height at 20sp — so a flat button is 40dp tall regardless of its
 * label — and is the same style M3's own `Button` provides to its label, which is what the
 * Android `actual`s delegate to. A label that needs its own style still passes `style = ...`
 * itself; this only supplies the default.
 */
@Composable
private fun FlatButtonContent(contentColor: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
    }
}

@Composable
actual fun FlatButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        FlatButtonContent(contentColor, content)
    }
}

@Composable
actual fun FlatTonalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    destructive: Boolean,
    content: @Composable () -> Unit,
) {
    // Only the enabled palette varies with `destructive`: a disabled button carries no meaning to
    // color, so it keeps the same neutral onSurface 12%/38% dim either way.
    val background = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        destructive -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        FlatButtonContent(contentColor, content)
    }
}

@Composable
actual fun FlatTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        FlatButtonContent(contentColor, content)
    }
}

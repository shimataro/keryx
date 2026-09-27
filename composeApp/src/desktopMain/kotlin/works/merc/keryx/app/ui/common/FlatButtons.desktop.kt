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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * [FlatButtonLabelStyle] pins the line height at 18sp — so a flat button is 32dp tall regardless of
 * its label. A label that needs its own style still passes `style = ...` itself; this only
 * supplies the default.
 */
@Composable
private fun FlatButtonContent(contentColor: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        ProvideTextStyle(FlatButtonLabelStyle) { content() }
    }
}

/**
 * `labelLarge` (the style M3's own `Button` gives its label, which the Android `actual`s delegate
 * to) scaled down to 13sp on an 18sp line: with [FLAT_BUTTON_VERTICAL_PADDING] that makes a 32dp
 * button, closer to a macOS push button than M3's 40dp, which read as web-app-sized next to the
 * dialogs' native `JButton` row.
 */
private val FlatButtonLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp)

private val FLAT_BUTTON_VERTICAL_PADDING = 7.dp

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
            .padding(horizontal = 16.dp, vertical = FLAT_BUTTON_VERTICAL_PADDING),
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
        // Neutral, not a teal-tinted secondaryContainer: like a macOS push button, only the primary
        // action carries color, and the fill can't be mistaken for an unfocused selection.
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = FLAT_BUTTON_VERTICAL_PADDING),
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
            .padding(horizontal = 12.dp, vertical = FLAT_BUTTON_VERTICAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        FlatButtonContent(contentColor, content)
    }
}

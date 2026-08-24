package works.merc.keryx.app.ui.common

import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android `actual`: delegates to M3's own `Button`, which already picks up [KeryxTheme]'s
 * `colorScheme` (see that file's KDoc) with no color overrides needed here — same "plain M3"
 * approach as `KeryxTextField.android.kt`/`KeryxDialogs.android.kt`.
 */
@Composable
actual fun FlatButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
}

@Composable
actual fun FlatTonalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
}

@Composable
actual fun FlatTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
}

package works.merc.keryx.app.ui.common

import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android `actual`: delegates to M3's own `Switch`/`Checkbox`, which already pick up
 * [KeryxTheme]'s `colorScheme` with no color overrides needed here — same "plain M3" approach as
 * `KeryxTextField.android.kt`/`KeryxDialogs.android.kt`.
 */
@Composable
actual fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

@Composable
actual fun FlatCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

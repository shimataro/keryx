package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android `actual`: delegates to M3's own `Switch`/`Checkbox`, which already pick up
 * [KeryxTheme]'s `colorScheme` with no color overrides needed here — same "plain M3" approach as
 * `KeryxTextField.android.kt`/`KeryxDialogs.android.kt`. `FlatSwitch`'s `thumbContent` mirrors the
 * OS Settings app's own Switch (a check/close glyph inside the thumb) — `Switch` already supplies
 * the icon's `LocalContentColor` from `colors.iconColor`, so [KeryxIcon] needs no explicit tint.
 */
@Composable
actual fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            KeryxIcon(
                icon = if (checked) KeryxIcons.CheckFilled else KeryxIcons.CloseFilled,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
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

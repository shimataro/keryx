package works.merc.keryx.app.ui.common

import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Android `actual`: delegates to M3's own `SingleChoiceSegmentedButtonRow`/`SegmentedButton` and
 * `FilterChip`, which already pick up [KeryxTheme]'s `colorScheme` with no color overrides needed
 * here — same "plain M3" approach as `KeryxTextField.android.kt`/`KeryxDialogs.android.kt`. Both
 * `SegmentedButton` and `FilterChip` are stable (non-experimental) APIs as of this app's
 * `compose-material3` version.
 */
@Composable
actual fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) },
            )
        }
    }
}

@Composable
actual fun ToggleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    FilterChip(
        selected = checked,
        onClick = { onCheckedChange(!checked) },
        label = { Text(label) },
        enabled = enabled,
    )
}

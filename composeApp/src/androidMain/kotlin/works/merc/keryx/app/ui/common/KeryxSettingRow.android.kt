package works.merc.keryx.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Android `actual`: a real M3 [ListItem] — see the `expect`'s KDoc in `commonMain`. The whole row
 * (not just the label) is the tap target when [onClick] is given, matching Android's own list-row
 * convention; hover has no touch equivalent, so [supporting] renders as `ListItem`'s own
 * `supportingContent` line instead of a tooltip.
 */
@Composable
actual fun KeryxSettingRow(
    label: String,
    modifier: Modifier,
    supporting: String?,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    toggled: Boolean?,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        // trailing (e.g. FlatSwitch) has its own semantics node (M3's Switch reports Role.Switch +
        // its own checked state) that would otherwise merge into a second, separately-focusable
        // stop right next to this row's own — clearAndSetSemantics only touches the accessibility
        // tree, not the switch's own touch handling, so tapping it directly still fires its
        // onCheckedChange normally.
        trailingContent = trailing?.let {
            { Box(Modifier.clearAndSetSemantics {}) { it() } }
        },
        modifier = modifier.fillMaxWidth().let { base ->
            when {
                toggled != null && onClick != null ->
                    base.toggleable(value = toggled, role = Role.Switch, onValueChange = { onClick() })
                onClick != null -> base.clickable(onClick = onClick)
                else -> base
            }
        },
    )
}

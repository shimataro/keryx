package works.merc.keryx.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = modifier.fillMaxWidth().let { base ->
            if (onClick != null) base.clickable(onClick = onClick) else base
        },
    )
}

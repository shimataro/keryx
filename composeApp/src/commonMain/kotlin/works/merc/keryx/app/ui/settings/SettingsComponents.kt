package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.ui.common.FlatSwitch
import works.merc.keryx.app.ui.common.KeryxRaisedSurface
import works.merc.keryx.app.ui.common.KeryxSettingRow

// Shared building blocks for the settings tabs (SettingsDialog + *Tab files).

/**
 * Groups a section/tab's rows into a bordered, tonal container (the app's "flat surface pattern"
 * — see `ui-guidelines`: `surfaceContainerLow` fill + hairline `outlineVariant` border, no tonal
 * elevation — also used by `NotificationCenterSheet`/`SetupScreen.OptionCard`/`FlatTooltipContent`).
 * Ties a row's label and its trailing control (e.g. a switch pinned to the far edge by
 * `weight(1f)`) together as one visible unit instead of floating disconnected across the dialog's
 * fixed 640dp width.
 *
 * @param content The composable content displayed inside the card.
 */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    KeryxRaisedSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * Displays a titled settings section followed by a divider.
 *
 * @param title The section heading.
 * @param content The composable content displayed below the heading.
 */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    content()
    HorizontalDivider(
        Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * A tappable row that opens [url] in the external browser — a thin [KeryxSettingRow] wrapper (see
 * its own KDoc for the platform-native affordance each `actual` gives this: hover-underline +
 * tooltip on desktop, a real `ListItem` tap target on Android).
 *
 * @param label The text displayed for the link.
 * @param url The external URL to open, and to show as the desktop tooltip / Android supporting text.
 */
@Composable
internal fun LinkRow(label: String, url: String) {
    KeryxSettingRow(label = label, supporting = url, onClick = { BrowserOpener.open(url) })
}

/**
 * A tappable row sharing [LinkRow]'s visual language for an in-app action rather than opening a
 * URL — no destination tooltip/supporting text, since there's nothing to preview. Used for
 * Settings/About entry points that have no native application menu bar to live in (see
 * `platform/PlatformOs.kt`'s `hasNativeAppMenu`).
 *
 * @param label The text displayed for the action.
 * @param onClick Called when the row is tapped.
 */
@Composable
internal fun ActionLinkRow(label: String, onClick: () -> Unit) {
    KeryxSettingRow(label = label, onClick = onClick)
}

/**
 * Displays a labeled switch row. Tapping the switch always toggles it; on Android, tapping
 * anywhere in the row does too (a real `ListItem`'s own tap target) — desktop keeps its previous
 * behavior of only the switch itself being interactive (see [KeryxSettingRow]'s desktop `actual`).
 *
 * @param label The text displayed beside the switch.
 * @param checked Whether the switch is selected.
 * @param onChange Called with the new selection state when the switch changes.
 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    KeryxSettingRow(
        label = label,
        onClick = { onChange(!checked) },
        trailing = { FlatSwitch(checked = checked, onCheckedChange = onChange) },
    )
}

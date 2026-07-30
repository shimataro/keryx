package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.ui.common.FlatSwitch
import works.merc.keryx.app.ui.common.FlatTooltipContent

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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
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
 * A tappable text row that opens [url] in the external browser (rendered in the theme's primary
 * color). On hover it underlines, switches to a hand cursor, and shows the destination [url] in a
 * tooltip, so it reads clearly as a link.
 *
 * @param label The text displayed for the link.
 * @param url The external URL to open and display in the tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Displays a clickable link with hover styling and a URL tooltip.
 *
 * @param label The text displayed for the link.
 * @param url The URL opened when the link is clicked and shown in the tooltip.
 */
@Composable
internal fun LinkRow(label: String, url: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(url) },
        state = rememberTooltipState(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = if (hovered) TextDecoration.Underline else null,
            modifier = Modifier
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interactionSource) { BrowserOpener.open(url) }
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * Displays a labeled switch row.
 *
 * @param label The text displayed beside the switch.
 * @param checked Whether the switch is selected.
 * @param onChange Called with the new selection state when the switch changes.
 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FlatSwitch(checked = checked, onCheckedChange = onChange)
    }
}

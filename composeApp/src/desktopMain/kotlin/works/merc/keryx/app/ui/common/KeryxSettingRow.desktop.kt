package works.merc.keryx.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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

/**
 * Desktop `actual`: the app's flat convention — see the `expect`'s KDoc in `commonMain`. With
 * [trailing], this is exactly the former `SwitchRow`'s layout (plain-colored label + trailing
 * slot, [onClick] not wired to the row itself — only [trailing] was ever interactive there);
 * without it, this is exactly the former `LinkRow`/`ActionLinkRow` (primary-colored label,
 * underline + hand cursor on hover, [supporting] as a hover tooltip via [FlatTooltipContent]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxSettingRow(
    label: String,
    modifier: Modifier,
    supporting: String?,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    if (trailing != null) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            trailing()
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val text: @Composable () -> Unit = {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = if (hovered) TextDecoration.Underline else null,
            modifier = modifier
                .hoverable(interactionSource, enabled = onClick != null)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interactionSource, enabled = onClick != null) { onClick?.invoke() }
                .padding(vertical = 4.dp),
        )
    }
    if (supporting != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { FlatTooltipContent(supporting) },
            state = rememberTooltipState(),
        ) { text() }
    } else {
        text()
    }
}

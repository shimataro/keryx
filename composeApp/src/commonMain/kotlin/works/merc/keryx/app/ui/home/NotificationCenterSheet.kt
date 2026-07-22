package works.merc.keryx.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_dismiss
import works.merc.keryx.app.resources.notification_dismiss_all
import works.merc.keryx.app.resources.notification_empty
import works.merc.keryx.app.resources.notification_level_error
import works.merc.keryx.app.resources.notification_level_info
import works.merc.keryx.app.resources.notification_level_warning
import works.merc.keryx.app.resources.settings_cloud_reset
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Non-modal notification panel, shown as an anchored [androidx.compose.ui.window.Popup] from
 * `ArticleListPane`'s bell icon rather than an `AlertDialog` — there's no scrim, so it reads as a
 * transient popover instead of a blocking dialog. See `.claude/skills/ui-guidelines/SKILL.md` for the
 * Popup-vs-Dialog usage split.
 */
@Composable
fun NotificationCenterSheet(vm: NotificationCenterViewModel) {
    val items by vm.items.collectAsStateSafe(emptyList())
    val shape = MaterialTheme.shapes.medium

    Surface(
        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).shadow(4.dp, shape = shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val clearTooltip = stringResource(Res.string.notification_dismiss_all)
                TooltipIconButton(tooltip = clearTooltip, onClick = { vm.dismissAll() }, enabled = items.isNotEmpty()) {
                    KeryxIcon(KeryxIcons.DeleteSweep, contentDescription = clearTooltip)
                }
            }

            if (items.isEmpty()) {
                Text(
                    stringResource(Res.string.notification_empty),
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(Modifier.padding(top = 8.dp)) {
                    items.forEach { notification ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val (icon, tint, levelLabel) = when (notification.level) {
                                AppNotificationLevel.ERROR ->
                                    Triple(KeryxIcons.ErrorOutlined, MaterialTheme.colorScheme.error, stringResource(Res.string.notification_level_error))
                                AppNotificationLevel.WARNING ->
                                    Triple(KeryxIcons.Warning, Color(0xFFF9A825), stringResource(Res.string.notification_level_warning))
                                AppNotificationLevel.INFO ->
                                    Triple(KeryxIcons.Info, MaterialTheme.colorScheme.primary, stringResource(Res.string.notification_level_info))
                            }
                            KeryxIcon(icon, contentDescription = levelLabel, tint = tint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(notification.message, style = MaterialTheme.typography.bodyMedium)
                                // An actionable notification (e.g. an unusable cloud DB) offers its
                                // recovery action inline, below the message (the popup is too narrow
                                // to place it alongside).
                                if (notification.action == AppNotificationAction.RESET_CLOUD_DATA) {
                                    Spacer(Modifier.height(6.dp))
                                    FlatTonalButton(onClick = { vm.requestAction(notification) }) {
                                        Text(stringResource(Res.string.settings_cloud_reset))
                                    }
                                }
                            }
                            val dismissTooltip = stringResource(Res.string.notification_dismiss)
                            TooltipIconButton(tooltip = dismissTooltip, onClick = { vm.dismiss(notification.id) }) {
                                KeryxIcon(KeryxIcons.CloseOutlined, contentDescription = dismissTooltip, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

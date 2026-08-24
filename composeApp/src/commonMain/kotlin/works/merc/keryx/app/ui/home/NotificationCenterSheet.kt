package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.platform.BrowserOpener
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
import works.merc.keryx.app.ui.common.KeryxRaisedSurface
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Non-modal notification panel, shown as an anchored [androidx.compose.ui.window.Popup] from
 * `ArticleListPane`'s bell icon rather than an `AlertDialog` — there's no scrim, so it reads as a
 * transient popover instead of a blocking dialog. See `.claude/skills/ui-guidelines/SKILL.md` for the
 * Popup-vs-Dialog usage split.
 *
 * Every notification carries a next action ([AppNotificationAction]). All but the destructive
 * "reset cloud data" one are invoked by clicking the row itself; [onNavigated] then lets the caller
 * dismiss the popover, both so the destination is visible and because this popup dismisses on focus
 * loss (anything it opened would go with it).
 *
 * @param vm The view model providing notifications and handling notification actions.
 * @param onNavigated Called after any row action, so the caller can close the popover.
 */
@Composable
fun NotificationCenterSheet(vm: NotificationCenterViewModel, onNavigated: () -> Unit = {}) {
    val items by vm.items.collectAsStateSafe(emptyList())
    val shape = MaterialTheme.shapes.medium

    KeryxRaisedSurface(
        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).shadow(4.dp, shape = shape),
        shape = shape,
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
                        NotificationRow(
                            notification = notification,
                            onDismiss = { vm.dismiss(notification.id) },
                            onRequestHostAction = { vm.requestAction(notification) },
                            onNavigated = onNavigated,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays a notification with its level indicator, message, optional action, and dismiss control.
 *
 * @param onDismiss Dismisses the notification.
 * @param onRequestHostAction Requests handling of a notification action by the host screen.
 * @param onNavigated Called after a row action completes.
 */
@Composable
private fun NotificationRow(
    notification: AppNotification,
    onDismiss: () -> Unit,
    onRequestHostAction: () -> Unit,
    onNavigated: () -> Unit,
) {
    val action = notification.action
    // The reset action is destructive, so it must not fire from a stray row click.
    val rowAction: (() -> Unit)? = when (action) {
        null, AppNotificationAction.ResetCloudData -> null
        // Opening the browser needs no host state, so it's done right here (same pattern as the
        // article list's "open in browser").
        is AppNotificationAction.OpenUrl -> ({ BrowserOpener.open(action.url); onNavigated() })
        is AppNotificationAction.ShowFeedDetail,
        is AppNotificationAction.ShowSettingsTab,
        is AppNotificationAction.ShowInfoDialog,
        -> ({ onRequestHostAction(); onNavigated() })
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (rowAction == null) {
                    Modifier
                } else {
                    // Plain clickable, so it picks up the app-wide flat indication rather than a ripple.
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, onClick = rowAction)
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            // A clickable row signals itself the same way the settings screen's LinkRow does — the
            // app's established "this text leads somewhere" convention — rather than adding a
            // chevron or any other extra slot (which would shift the layout).
            Text(
                notification.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (rowAction != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                textDecoration = if (rowAction != null && hovered) TextDecoration.Underline else null,
            )
            // The destructive recovery action (an unusable cloud DB) gets an explicit button instead,
            // inline below the message (the popup is too narrow to place it alongside).
            if (action == AppNotificationAction.ResetCloudData) {
                Spacer(Modifier.height(6.dp))
                FlatTonalButton(onClick = onRequestHostAction) {
                    Text(stringResource(Res.string.settings_cloud_reset))
                }
            }
        }
        val dismissTooltip = stringResource(Res.string.notification_dismiss)
        TooltipIconButton(tooltip = dismissTooltip, onClick = onDismiss) {
            KeryxIcon(KeryxIcons.CloseOutlined, contentDescription = dismissTooltip, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
        }
    }
}

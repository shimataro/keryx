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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_dismiss
import works.merc.keryx.app.resources.notification_dismiss_all
import works.merc.keryx.app.resources.notification_empty
import works.merc.keryx.app.resources.notification_level_error
import works.merc.keryx.app.resources.notification_level_info
import works.merc.keryx.app.resources.notification_level_warning
import works.merc.keryx.app.resources.settings_cloud_reset
import works.merc.keryx.app.resources.time_days_ago
import works.merc.keryx.app.resources.time_hours_ago
import works.merc.keryx.app.resources.time_minutes_ago
import works.merc.keryx.app.resources.time_now
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxRaisedSurface
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Notification panel, hosted by [works.merc.keryx.app.ui.common.KeryxAnchoredPanel] from
 * `ArticleListPane`'s bell icon — a non-modal anchored popover on desktop, a `ModalBottomSheet` on
 * Android (see that composable's own KDoc, and `.claude/skills/ui-guidelines/SKILL.md`'s
 * Popup-vs-Dialog section for why this is a popover, not an `AlertDialog`, in the first place).
 *
 * The `KeryxRaisedSurface`/shadow/width wrapping is desktop-only: a bare `Popup` supplies no
 * container of its own, but Android's `ModalBottomSheet` already does, so doubling it here would
 * nest two visible surfaces on that platform.
 *
 * Every notification carries a next action ([AppNotificationAction]). All but the destructive
 * "reset cloud data" one are invoked by clicking the row itself; [onNavigated] then lets the caller
 * dismiss the popover, both so the destination is visible and because a desktop popup dismisses on
 * focus loss (anything it opened would go with it).
 *
 * @param vm The view model providing notifications and handling notification actions.
 * @param onNavigated Called after any row action, so the caller can close the popover.
 */
@Composable
fun NotificationCenterSheet(vm: NotificationCenterViewModel, onNavigated: () -> Unit = {}) {
    val items by vm.items.collectAsState()
    val shape = MaterialTheme.shapes.medium
    val isTouchPrimary = works.merc.keryx.app.platform.isTouchPrimary
    // One shared reading for every row, re-read while the panel is composed so relative labels
    // don't freeze; produceState cancels the loop once the panel leaves composition.
    val clock: Clock = koinInject()
    val nowMillis by produceState(clock.nowMillis(), clock) {
        while (true) {
            delay(RELATIVE_TIME_REFRESH_MS)
            value = clock.nowMillis()
        }
    }

    val body = @Composable {
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
                            nowMillis = nowMillis,
                            onDismiss = { vm.dismiss(notification.id) },
                            onRequestHostAction = { vm.requestAction(notification) },
                            onNavigated = onNavigated,
                        )
                    }
                }
            }
        }
    }

    if (isTouchPrimary) {
        body()
    } else {
        KeryxRaisedSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).shadow(4.dp, shape = shape),
            shape = shape,
        ) { body() }
    }
}

/**
 * Displays a notification with its level indicator, message, optional action, and dismiss control.
 *
 * @param nowMillis The current time the relative timestamp is measured against.
 * @param onDismiss Dismisses the notification.
 * @param onRequestHostAction Requests handling of a notification action by the host screen.
 * @param onNavigated Called after a row action completes.
 */
@Composable
private fun NotificationRow(
    notification: AppNotification,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onRequestHostAction: () -> Unit,
    onNavigated: () -> Unit,
) {
    val action = notification.action
    val rowAction = notificationRowAction(notification, onRequestHostAction, onNavigated)
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
                        .clickable(interactionSource = interactionSource, role = Role.Button, onClick = rowAction)
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
            Spacer(Modifier.height(2.dp))
            Text(
                formatRelativeTime(notification.timestampMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            // The destructive recovery action (an unusable cloud DB) gets an explicit button instead,
            // inline below the message (the popup is too narrow to place it alongside).
            if (action == AppNotificationAction.ResetCloudData) {
                Spacer(Modifier.height(6.dp))
                FlatTonalButton(onClick = onRequestHostAction, destructive = true) {
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

/** How often an open notification panel re-reads the clock; minutes are the finest unit shown. */
private const val RELATIVE_TIME_REFRESH_MS = 60_000L

/** Which relative-time label a notification row shows, given how long ago it was raised. */
internal sealed interface RelativeTime {
    data object Now : RelativeTime
    data class Minutes(val count: Int) : RelativeTime
    data class Hours(val count: Int) : RelativeTime
    data class Days(val count: Int) : RelativeTime

    /** Older than a week: shown as an absolute date and time instead. */
    data object Absolute : RelativeTime
}

/**
 * Buckets [diffMillis] (now minus the notification's timestamp) into a [RelativeTime]. A negative
 * difference (a timestamp slightly ahead of the clock) counts as [RelativeTime.Now].
 */
internal fun relativeTimeOf(diffMillis: Long): RelativeTime = when {
    diffMillis < 60_000L -> RelativeTime.Now
    diffMillis < 3_600_000L -> RelativeTime.Minutes((diffMillis / 60_000L).toInt())
    diffMillis < 86_400_000L -> RelativeTime.Hours((diffMillis / 3_600_000L).toInt())
    diffMillis < 604_800_000L -> RelativeTime.Days((diffMillis / 86_400_000L).toInt())
    else -> RelativeTime.Absolute
}

/**
 * Formats [timestampMillis] relative to [nowMillis] for display in notification rows. The caller
 * supplies [nowMillis] and refreshes it while the panel is open, so the labels stay current.
 */
@Composable
private fun formatRelativeTime(timestampMillis: Long, nowMillis: Long): String {
    return when (val relative = relativeTimeOf(nowMillis - timestampMillis)) {
        RelativeTime.Now -> stringResource(Res.string.time_now)
        is RelativeTime.Minutes -> pluralStringResource(Res.plurals.time_minutes_ago, relative.count, relative.count)
        is RelativeTime.Hours -> pluralStringResource(Res.plurals.time_hours_ago, relative.count, relative.count)
        is RelativeTime.Days -> pluralStringResource(Res.plurals.time_days_ago, relative.count, relative.count)
        RelativeTime.Absolute -> formatTimestamp(timestampMillis)
    }
}

/**
 * What acting on [notification] does, or `null` when the notification offers nothing to act on
 * from a plain tap — no action at all, or the destructive
 * [AppNotificationAction.ResetCloudData], which must never fire from a stray row click and gets
 * its own confirmed button instead.
 *
 * Shared by the notification row and Android's foreground alert Snackbar
 * (`HomeScreen`'s `ForegroundAlertSnackbar`), so both reach the same destination for the same
 * notification. Kept a plain function rather than a composable so its branches are unit-testable.
 *
 * @param onRequestHostAction Hands the notification to the host screen, for the actions that need
 *   another screen's state changed (see [AppNotificationAction]'s own KDoc).
 * @param onNavigated Called once the action has been started, so a caller showing the notification
 *   in a transient surface (popover, Snackbar) can dismiss it.
 */
internal fun notificationRowAction(
    notification: AppNotification,
    onRequestHostAction: () -> Unit,
    onNavigated: () -> Unit,
): (() -> Unit)? = when (val action = notification.action) {
    null, AppNotificationAction.ResetCloudData -> null
    // Opening the browser needs no host state, so it's done right here (same pattern as the
    // article list's "open in browser").
    is AppNotificationAction.OpenUrl -> ({ BrowserOpener.open(action.url); onNavigated() })
    is AppNotificationAction.ShowFeedDetail,
    is AppNotificationAction.ShowSettingsTab,
    is AppNotificationAction.ShowInfoDialog,
    -> ({ onRequestHostAction(); onNavigated() })
}

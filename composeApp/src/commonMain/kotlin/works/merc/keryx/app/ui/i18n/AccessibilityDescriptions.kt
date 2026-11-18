package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import works.merc.keryx.app.core.AppNotificationLevel
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.accessibility_checked
import works.merc.keryx.app.resources.accessibility_unchecked
import works.merc.keryx.app.resources.home_notifications_alert_accessible_name
import works.merc.keryx.app.resources.notification_level_error
import works.merc.keryx.app.resources.notification_level_warning

/**
 * Localized state description for a checked UI item (used by accessibility services).
 */
@Composable
fun checkedStateDescription(): String = stringResource(Res.string.accessibility_checked)

/**
 * Localized state description for an unchecked UI item (used by accessibility services).
 */
@Composable
fun uncheckedStateDescription(): String = stringResource(Res.string.accessibility_unchecked)

/**
 * Localized state description for the notification center's entry point: the most severe [level]
 * when it is an alert (`WARNING`/`ERROR`), or `null` for notices alone and an empty notification
 * center, which have nothing to report beyond the count. It is what keeps the bell's tint from being
 * the only sign of severity.
 */
@Composable
fun notificationAlertStateDescription(level: AppNotificationLevel?): String? = when (level) {
    AppNotificationLevel.ERROR -> stringResource(Res.string.notification_level_error)
    AppNotificationLevel.WARNING -> stringResource(Res.string.notification_level_warning)
    AppNotificationLevel.INFO, null -> null
}

/**
 * The notification center row's accessible name when it holds an alert — its name, [count] and the
 * alert's level ("Notifications, 2, Warning") — or `null` when there is no alert to report (an empty
 * center, or notices alone), so the row keeps its plain name. For a bridge that does not read
 * [notificationAlertStateDescription] (`accessibilityBridgeReadsStateDescription`): its accessible
 * name then replaces the row's text, so the name and count are spelled out here too.
 */
@Composable
fun notificationRowAlertAccessibleName(count: Int, level: AppNotificationLevel?): String? {
    val levelText = notificationAlertStateDescription(level) ?: return null
    if (count <= 0) return null
    return stringResource(Res.string.home_notifications_alert_accessible_name, count, levelText)
}

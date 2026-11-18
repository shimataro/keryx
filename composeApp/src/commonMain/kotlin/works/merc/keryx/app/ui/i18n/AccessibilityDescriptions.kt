package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import works.merc.keryx.app.core.AppNotificationLevel
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.accessibility_checked
import works.merc.keryx.app.resources.accessibility_unchecked
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

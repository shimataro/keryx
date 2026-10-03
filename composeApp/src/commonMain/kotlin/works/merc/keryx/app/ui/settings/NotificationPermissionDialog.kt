package works.merc.keryx.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_permission_allow
import works.merc.keryx.app.resources.notification_permission_body
import works.merc.keryx.app.resources.notification_permission_not_now
import works.merc.keryx.app.resources.notification_permission_title
import works.merc.keryx.app.ui.common.KeryxAlertDialog

/**
 * The in-app explanation shown before the OS notification-permission request (see
 * [NotificationPermissionFlow]). Any dismissal other than "Allow" counts as "Not now".
 */
@Composable
internal fun NotificationPermissionDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    KeryxAlertDialog(
        onDismissRequest = onNotNow,
        confirmText = stringResource(Res.string.notification_permission_allow),
        onConfirm = onAllow,
        dismissText = stringResource(Res.string.notification_permission_not_now),
        title = stringResource(Res.string.notification_permission_title),
        text = { Text(stringResource(Res.string.notification_permission_body)) },
    )
}

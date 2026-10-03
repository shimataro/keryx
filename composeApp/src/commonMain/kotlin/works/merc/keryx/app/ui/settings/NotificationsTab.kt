package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_notification_blocked_hint
import works.merc.keryx.app.resources.settings_notification_enabled
import works.merc.keryx.app.resources.settings_notification_open_system_settings
import works.merc.keryx.app.ui.common.KeryxSettingRow

/**
 * Notifications tab: new-article notification toggle.
 *
 * Turning the switch on asks for the OS notification permission first where there is one
 * (Android 13+), through [NotificationPermissionFlow]: the setting turns on only once it is
 * granted. After a denial a row pointing to the OS notification settings appears below the switch,
 * since a permanent denial returns without any system dialog and the switch alone would just stay
 * off unexplained.
 *
 * @param vm The view model that provides the setting state.
 * @param permissionPrompt The one prompt App owns, so a denial made earlier (here or from Home) is still
 *   known after this tab or the Settings dialog was closed and reopened.
 */
@Composable
internal fun NotificationsTabContent(vm: SettingsViewModel, permissionPrompt: NotificationPermissionPrompt) {
    val settings by vm.localSettings.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        SettingsCard(settingRowsOnly = true) {
            SwitchRow(
                label = stringResource(Res.string.settings_notification_enabled),
                checked = settings.notificationEnabled,
                onChange = permissionPrompt::onSwitchChanged,
            )
            if (permissionPrompt.systemSettingsHintVisible) {
                KeryxSettingRow(
                    label = stringResource(Res.string.settings_notification_open_system_settings),
                    supporting = stringResource(Res.string.settings_notification_blocked_hint),
                    onClick = permissionPrompt::openSystemSettings,
                )
            }
        }
    }
}

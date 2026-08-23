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
import works.merc.keryx.app.platform.rememberNotificationPermissionRequester
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_notification_enabled

/**
 * Notifications tab: new-article notification toggle.
 *
 * @param vm The view model that provides the setting state and handles changes.
 */
@Composable
internal fun NotificationsTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    // Covers the case App.kt's own startup request doesn't: the setting starting off and being
    // turned on later in this session (a no-op everywhere the expect's KDoc already covers).
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        SettingsCard {
            SwitchRow(
                label = stringResource(Res.string.settings_notification_enabled),
                checked = settings.notificationEnabled,
                onChange = {
                    vm.setNotificationEnabled(it)
                    if (it) requestNotificationPermission()
                },
            )
        }
    }
}

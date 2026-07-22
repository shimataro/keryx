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
import works.merc.keryx.app.resources.settings_notification_enabled

/** 通知: new-article notification toggle. */
@Composable
internal fun NotificationsTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        SettingsCard {
            SwitchRow(
                label = stringResource(Res.string.settings_notification_enabled),
                checked = settings.notificationEnabled,
                onChange = { vm.setNotificationEnabled(it) },
            )
        }
    }
}

package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.SegmentedControl
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_update_check_3days
import works.merc.keryx.app.resources.settings_update_check_available
import works.merc.keryx.app.resources.settings_update_check_daily
import works.merc.keryx.app.resources.settings_update_check_failed
import works.merc.keryx.app.resources.settings_update_check_interval
import works.merc.keryx.app.resources.settings_update_check_now
import works.merc.keryx.app.resources.settings_update_check_startup_only
import works.merc.keryx.app.resources.settings_update_check_up_to_date
import works.merc.keryx.app.resources.settings_update_check_weekly
import works.merc.keryx.app.resources.settings_update_open_release_page

/** アップデート: update-check interval / check now / result. */
/**
 * Displays update-check settings and the current update status.
 *
 * @param vm The view model that provides update settings and actions.
 */
@Composable
internal fun UpdatesTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    // Opening the tab already means "I want to know if there's an update", so run the check the user
    // would otherwise have to trigger by hand — no result yet means nothing at all would be shown.
    // Equivalent to one press of "check now", so it never perturbs the automatic check schedule.
    LaunchedEffect(Unit) {
        if (vm.updateCheckResult == null) vm.checkForUpdate()
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(Res.string.settings_update_check_interval),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SegmentedControl(
            options = listOf(
                0 to stringResource(Res.string.settings_update_check_startup_only),
                24 to stringResource(Res.string.settings_update_check_daily),
                72 to stringResource(Res.string.settings_update_check_3days),
                168 to stringResource(Res.string.settings_update_check_weekly),
            ),
            selected = settings.updateCheckIntervalHours,
            onSelect = { vm.setUpdateCheckIntervalHours(it) },
        )

        Spacer(Modifier.height(12.dp))
        FlatTonalButton(onClick = { vm.checkForUpdate() }, enabled = !vm.checkingForUpdate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.checkingForUpdate) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    KeryxIcon(
                        KeryxIcons.Update,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_update_check_now))
            }
        }
        when (val result = vm.updateCheckResult) {
            is UpdateStatus.UpToDate -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_update_check_up_to_date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateStatus.Available -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_update_check_available, result.version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LinkRow(
                    label = stringResource(Res.string.settings_update_open_release_page),
                    url = result.url,
                )
            }
            is UpdateStatus.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_update_check_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            null -> Unit
        }
    }
}

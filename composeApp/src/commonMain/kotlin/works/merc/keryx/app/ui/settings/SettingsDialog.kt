package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.ui.common.KeryxDialogTab
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxTabDialog
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_cloud_sync
import works.merc.keryx.app.resources.settings_tab_data
import works.merc.keryx.app.resources.settings_tab_general
import works.merc.keryx.app.resources.settings_tab_notifications
import works.merc.keryx.app.resources.settings_updates

/**
 * The settings screen, presented as a modeless, macOS-System-Settings-style tabbed dialog window
 * (see [KeryxTabDialog]) rather than a full-screen navigation route. Each tab reorganizes what used
 * to be a single vertical list of sections; all the underlying settings logic (via
 * [SettingsViewModel]) is unchanged and applies immediately. Each tab's content lives in its own
 * file (`GeneralTab` / `NotificationsTab` / `CloudSyncTab` / `DataTab` / `UpdatesTab`), with shared
 * building blocks in `SettingsComponents`.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val vm = koinInject<SettingsViewModel>()

    var selectedTabId by remember { mutableStateOf("general") }

    // The cloud-sync tab exists only when at least one cloud provider was configured at build time
    // (mirrors the old section-level hiding). availableCloudTypes is stable across the dialog's life.
    val tabs = buildList {
        add(KeryxDialogTab("general", stringResource(Res.string.settings_tab_general), KeryxIcons.Tune))
        add(KeryxDialogTab("notifications", stringResource(Res.string.settings_tab_notifications), KeryxIcons.Notifications))
        if (vm.availableCloudTypes.isNotEmpty()) {
            add(KeryxDialogTab("cloud_sync", stringResource(Res.string.settings_cloud_sync), KeryxIcons.Cloud))
        }
        add(KeryxDialogTab("data", stringResource(Res.string.settings_tab_data), KeryxIcons.Storage))
        add(KeryxDialogTab("updates", stringResource(Res.string.settings_updates), KeryxIcons.Update))
    }

    KeryxTabDialog(
        onDismissRequest = onDismiss,
        tabs = tabs,
        selectedTabId = selectedTabId,
        onSelectTab = { selectedTabId = it },
    ) { tabId ->
        when (tabId) {
            "general" -> GeneralTabContent(vm)
            "notifications" -> NotificationsTabContent(vm)
            "cloud_sync" -> CloudSyncTabContent(vm)
            "data" -> DataTabContent(vm)
            "updates" -> UpdatesTabContent(vm)
            else -> Unit
        }
    }
}

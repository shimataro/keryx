package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.platform.selfUpdateCheckSupported
import works.merc.keryx.app.ui.common.KeryxDialogTab
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxTabDialog
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_cloud_sync
import works.merc.keryx.app.resources.settings_tab_data
import works.merc.keryx.app.resources.settings_tab_general
import works.merc.keryx.app.resources.settings_tab_notifications
import works.merc.keryx.app.resources.settings_title
import works.merc.keryx.app.resources.settings_updates

/**
 * The settings screen, presented as a modeless, macOS-System-Settings-style tabbed dialog window
 * (see [KeryxTabDialog]) rather than a full-screen navigation route. Each tab reorganizes what used
 * to be a single vertical list of sections; all the underlying settings logic (via
 * [SettingsViewModel]) is unchanged and applies immediately. Each tab's content lives in its own
 * file (`GeneralTab` / `NotificationsTab` / `CloudSyncTab` / `DataTab` / `UpdatesTab`), with shared
 * building blocks in `SettingsComponents`.
 *
 * @param onDismiss Called when the dialog should be dismissed.
 * @param initialTabId The tab shown when the dialog opens. Defaults to the first tab; a notification's
 *   `ShowSettingsTab` action opens the dialog directly on the tab where the problem is fixable.
 * @param tabRequestToken Bumped by the caller on every fresh explicit navigation request (a
 *   notification action or the "Open Settings" menu command), so the dialog jumps to [initialTabId]
 *   even if it's already open on that same tab id and the user has since switched tabs manually.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, initialTabId: String = "general", tabRequestToken: Int = 0) {
    val vm = koinInject<SettingsViewModel>()

    // The cloud-sync tab exists only when at least one cloud provider was configured at build time
    // (mirrors the old section-level hiding). availableCloudTypes is stable across the dialog's life.
    val tabs = buildList {
        add(KeryxDialogTab("general", stringResource(Res.string.settings_tab_general), KeryxIcons.Tune))
        add(KeryxDialogTab("notifications", stringResource(Res.string.settings_tab_notifications), KeryxIcons.Notifications))
        if (vm.availableCloudTypes.isNotEmpty()) {
            add(KeryxDialogTab("cloud_sync", stringResource(Res.string.settings_cloud_sync), KeryxIcons.Cloud))
        }
        add(KeryxDialogTab("data", stringResource(Res.string.settings_tab_data), KeryxIcons.Storage))
        // Hidden where there's an app-store update mechanism to defer to instead (see
        // selfUpdateCheckSupported's KDoc) — checkForUpdateAndNotify is gated the same way, so this
        // tab would otherwise always show "up to date" without ever being able to find anything.
        if (selfUpdateCheckSupported) {
            add(KeryxDialogTab("updates", stringResource(Res.string.settings_updates), KeryxIcons.Update))
        }
    }

    var selectedTabId by rememberSelectedTabId(initialTabId, tabRequestToken, tabs)

    KeryxTabDialog(
        onDismissRequest = onDismiss,
        tabs = tabs,
        selectedTabId = selectedTabId,
        onSelectTab = { selectedTabId = it },
        title = stringResource(Res.string.settings_title),
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

/** Re-initializes to [initialTabId] whenever [tabRequestToken] changes — a fresh explicit
 *  navigation request should always land on the requested tab, even if it's the same tab id
 *  the dialog is already showing (see App.kt's ShowSettingsTab / OpenSettings handling).
 *  Falls back to the first entry of [tabs] when [initialTabId] doesn't match any of them (e.g. a
 *  `ShowSettingsTab("cloud_sync")` notification surviving into a build with no cloud provider
 *  configured), so the dialog never opens on a tab id that isn't actually rendered. */
@Composable
internal fun rememberSelectedTabId(
    initialTabId: String,
    tabRequestToken: Int,
    tabs: List<KeryxDialogTab>,
): MutableState<String> =
    remember(tabRequestToken) {
        mutableStateOf(if (tabs.any { it.id == initialTabId }) initialTabId else tabs.first().id)
    }

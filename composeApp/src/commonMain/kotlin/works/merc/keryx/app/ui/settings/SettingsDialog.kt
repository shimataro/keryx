package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.ui.common.FlatButton
import works.merc.keryx.app.ui.common.FlatSwitch
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.FlatTooltipContent
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxDialogTab
import works.merc.keryx.app.ui.common.KeryxTabDialog
import works.merc.keryx.app.ui.common.SegmentedControl
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_abort
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.dropbox
import works.merc.keryx.app.resources.google_drive
import works.merc.keryx.app.resources.setup_auth_failed
import works.merc.keryx.app.resources.settings_cache
import works.merc.keryx.app.resources.settings_cloud_abort_connect_confirm_body
import works.merc.keryx.app.resources.settings_cloud_abort_connect_confirm_title
import works.merc.keryx.app.resources.settings_cloud_disconnect_confirm_body
import works.merc.keryx.app.resources.settings_cloud_disconnect_confirm_title
import works.merc.keryx.app.resources.settings_cloud_reset
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_action
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_body
import works.merc.keryx.app.resources.settings_cloud_reset_confirm_title
import works.merc.keryx.app.resources.settings_cloud_switch_confirm_action
import works.merc.keryx.app.resources.settings_cloud_switch_confirm_body
import works.merc.keryx.app.resources.settings_cloud_switch_confirm_title
import works.merc.keryx.app.resources.settings_cloud_sync
import works.merc.keryx.app.resources.settings_cloud_sync_hint
import works.merc.keryx.app.resources.settings_data_management
import works.merc.keryx.app.resources.settings_days30
import works.merc.keryx.app.resources.settings_days7
import works.merc.keryx.app.resources.settings_days90
import works.merc.keryx.app.resources.settings_dropbox_connect
import works.merc.keryx.app.resources.settings_dropbox_disconnect
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_export_success
import works.merc.keryx.app.resources.settings_font_large
import works.merc.keryx.app.resources.settings_font_medium
import works.merc.keryx.app.resources.settings_font_size
import works.merc.keryx.app.resources.settings_font_small
import works.merc.keryx.app.resources.settings_font_xlarge
import works.merc.keryx.app.resources.settings_google_drive_connect
import works.merc.keryx.app.resources.settings_google_drive_disconnect
import works.merc.keryx.app.resources.settings_import_failed
import works.merc.keryx.app.resources.settings_import_opml
import works.merc.keryx.app.resources.settings_import_success
import works.merc.keryx.app.resources.settings_last_synced
import works.merc.keryx.app.resources.settings_notification_enabled
import works.merc.keryx.app.resources.settings_read_timeout
import works.merc.keryx.app.resources.settings_refresh_hour1
import works.merc.keryx.app.resources.settings_refresh_hour3
import works.merc.keryx.app.resources.settings_refresh_interval
import works.merc.keryx.app.resources.settings_refresh_manual
import works.merc.keryx.app.resources.settings_refresh_min15
import works.merc.keryx.app.resources.settings_refresh_min30
import works.merc.keryx.app.resources.settings_seconds10
import works.merc.keryx.app.resources.settings_seconds30
import works.merc.keryx.app.resources.settings_seconds60
import works.merc.keryx.app.resources.settings_start_minimized
import works.merc.keryx.app.resources.settings_tab_data
import works.merc.keryx.app.resources.settings_tab_general
import works.merc.keryx.app.resources.settings_tab_notifications
import works.merc.keryx.app.resources.settings_theme
import works.merc.keryx.app.resources.settings_theme_dark
import works.merc.keryx.app.resources.settings_theme_light
import works.merc.keryx.app.resources.settings_theme_system
import works.merc.keryx.app.resources.settings_unlimited
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
import works.merc.keryx.app.resources.settings_updates

/** How long the inline OPML import/export status stays before auto-clearing. */
private const val OPML_STATUS_MS = 4000L

/**
 * The settings screen, presented as a modeless, macOS-System-Settings-style tabbed dialog window
 * (see [KeryxTabDialog]) rather than a full-screen navigation route. Each tab reorganizes what used
 * to be a single vertical list of sections; all the underlying settings logic (via
 * [SettingsViewModel]) is unchanged and applies immediately.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val vm = koinInject<SettingsViewModel>()

    var selectedTabId by remember { mutableStateOf("general") }

    // The cloud-sync tab exists only when at least one cloud provider was configured at build time
    // (mirrors the old section-level hiding). availableCloudTypes is stable across the dialog's life.
    val tabs = buildList {
        add(KeryxDialogTab("general", stringResource(Res.string.settings_tab_general), Icons.Outlined.Tune))
        add(KeryxDialogTab("notifications", stringResource(Res.string.settings_tab_notifications), Icons.Outlined.Notifications))
        if (vm.availableCloudTypes.isNotEmpty()) {
            add(KeryxDialogTab("cloud_sync", stringResource(Res.string.settings_cloud_sync), Icons.Outlined.Cloud))
        }
        add(KeryxDialogTab("data", stringResource(Res.string.settings_tab_data), Icons.Outlined.Storage))
        add(KeryxDialogTab("updates", stringResource(Res.string.settings_updates), Icons.Outlined.Update))
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

/** 一般: theme / font size / refresh interval / start-minimized. */
@Composable
private fun GeneralTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Section(stringResource(Res.string.settings_theme)) {
            SegmentedControl(
                options = listOf(
                    "system" to stringResource(Res.string.settings_theme_system),
                    "light" to stringResource(Res.string.settings_theme_light),
                    "dark" to stringResource(Res.string.settings_theme_dark),
                ),
                selected = settings.themeMode,
                onSelect = { vm.setThemeMode(it) },
            )
        }

        Section(stringResource(Res.string.settings_font_size)) {
            SegmentedControl(
                options = listOf(
                    0.85 to stringResource(Res.string.settings_font_small),
                    1.0 to stringResource(Res.string.settings_font_medium),
                    1.2 to stringResource(Res.string.settings_font_large),
                    1.4 to stringResource(Res.string.settings_font_xlarge),
                ),
                selected = settings.fontSizeScale,
                onSelect = { vm.setFontScale(it) },
            )
        }

        Section(stringResource(Res.string.settings_refresh_interval)) {
            SegmentedControl(
                options = listOf(
                    15 to stringResource(Res.string.settings_refresh_min15),
                    30 to stringResource(Res.string.settings_refresh_min30),
                    60 to stringResource(Res.string.settings_refresh_hour1),
                    180 to stringResource(Res.string.settings_refresh_hour3),
                    0 to stringResource(Res.string.settings_refresh_manual),
                ),
                selected = settings.refreshIntervalMinutes,
                onSelect = { vm.setRefreshIntervalMinutes(it) },
            )
        }

        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SwitchRow(
                label = stringResource(Res.string.settings_start_minimized),
                checked = settings.startMinimized,
                onChange = { vm.setStartMinimized(it) },
            )
        }
    }
}

/** 通知: new-article notification toggle. */
@Composable
private fun NotificationsTabContent(vm: SettingsViewModel) {
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

/** クラウド同期: provider connect/disconnect/switch, with the three confirmation dialogs. */
@Composable
private fun CloudSyncTabContent(vm: SettingsViewModel) {
    // Confirmation-dialog triggers for the two disruptive cloud-storage actions (disconnect an
    // established connection, or abort an in-flight OAuth wait). Starting a fresh connect stays
    // immediate — it's low-risk (just opens a browser).
    var confirmingDisconnect by remember { mutableStateOf<CloudStorageType?>(null) }
    var confirmingAbortConnect by remember { mutableStateOf<CloudStorageType?>(null) }
    // Confirms switching from the currently-connected provider to a different one (disconnect old,
    // connect new). Holds the target (new) provider.
    var confirmingSwitchTo by remember { mutableStateOf<CloudStorageType?>(null) }
    // Confirms the destructive "reset cloud data" (delete the cloud DB, re-upload local fresh).
    var confirmingResetCloudData by remember { mutableStateOf<CloudStorageType?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        val connected = vm.connectedType
        // Only one provider can be connected at a time. Reinforce that in words.
        Text(
            stringResource(Res.string.settings_cloud_sync_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            // Always show every available provider, in a fixed order (never collapse to just the
            // connected one).
            vm.availableCloudTypes.forEachIndexed { index, type ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                CloudProviderRow(
                    type = type,
                    connected = connected == type,
                    connecting = vm.connectingType == type,
                    canCancel = vm.canCancelConnect,
                    idleEnabled = vm.connectingType == null && !vm.resetting,
                    failed = vm.connectFailedType == type,
                    lastSyncedAtText = if (connected == type) vm.lastSyncedAtText else null,
                    resetting = vm.resetting,
                    // No provider connected yet: a fresh connect is low-risk, so do it directly. A
                    // different provider connected: confirm the switch first.
                    onSelect = {
                        if (connected == null) vm.connect(type) else confirmingSwitchTo = type
                    },
                    onCancel = { confirmingAbortConnect = type },
                    onDisconnect = { confirmingDisconnect = type },
                    onResetCloudData = { confirmingResetCloudData = type },
                )
            }
        }
    }

    confirmingDisconnect?.let { type ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingDisconnect = null },
            title = stringResource(Res.string.settings_cloud_disconnect_confirm_title, type.brandLabel()),
            text = { Text(stringResource(Res.string.settings_cloud_disconnect_confirm_body)) },
            confirmText = stringResource(type.disconnectLabel()),
            onConfirm = { vm.disconnect(); confirmingDisconnect = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    confirmingAbortConnect?.let { type ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingAbortConnect = null },
            title = stringResource(Res.string.settings_cloud_abort_connect_confirm_title, type.brandLabel()),
            text = { Text(stringResource(Res.string.settings_cloud_abort_connect_confirm_body)) },
            confirmText = stringResource(Res.string.common_abort),
            onConfirm = { vm.cancelConnect(); confirmingAbortConnect = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    confirmingResetCloudData?.let { _ ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingResetCloudData = null },
            title = stringResource(Res.string.settings_cloud_reset_confirm_title),
            text = { Text(stringResource(Res.string.settings_cloud_reset_confirm_body)) },
            confirmText = stringResource(Res.string.settings_cloud_reset_confirm_action),
            onConfirm = { vm.resetCloudData(); confirmingResetCloudData = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    confirmingSwitchTo?.let { type ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingSwitchTo = null },
            title = stringResource(Res.string.settings_cloud_switch_confirm_title, type.brandLabel()),
            // Read the current provider live — nothing else can mutate it while this modal is open.
            text = {
                Text(
                    stringResource(
                        Res.string.settings_cloud_switch_confirm_body,
                        vm.connectedType?.brandLabel().orEmpty(),
                    ),
                )
            },
            confirmText = stringResource(Res.string.settings_cloud_switch_confirm_action),
            onConfirm = { vm.switchTo(type); confirmingSwitchTo = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
}

/** データ: cache retention / read timeout / OPML import-export. */
@Composable
private fun DataTabContent(vm: SettingsViewModel) {
    // Inline status shown right under the OPML import/export buttons (macOS-style transient text).
    var opmlStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.opmlResult) {
        opmlStatus = when (val r = vm.opmlResult) {
            is OpmlResult.Exported -> getString(Res.string.settings_export_success)
            is OpmlResult.Imported -> {
                val added = getString(Res.string.settings_import_success, r.added)
                if (r.failed > 0) "$added / ${getString(Res.string.settings_import_failed, r.failed)}" else added
            }
            OpmlResult.Cancelled, null -> opmlStatus
        }
        vm.clearOpmlResult()
    }
    LaunchedEffect(opmlStatus) {
        if (opmlStatus != null) {
            delay(OPML_STATUS_MS)
            opmlStatus = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Section(stringResource(Res.string.settings_cache)) {
            SegmentedControl(
                options = listOf(
                    7 to stringResource(Res.string.settings_days7),
                    30 to stringResource(Res.string.settings_days30),
                    90 to stringResource(Res.string.settings_days90),
                    (null as Int?) to stringResource(Res.string.settings_unlimited),
                ),
                selected = vm.cacheRetentionDays,
                onSelect = { vm.updateCacheRetention(it) },
            )
        }

        Section(stringResource(Res.string.settings_read_timeout)) {
            SegmentedControl(
                options = listOf(
                    10 to stringResource(Res.string.settings_seconds10),
                    30 to stringResource(Res.string.settings_seconds30),
                    60 to stringResource(Res.string.settings_seconds60),
                ),
                selected = vm.readTimeoutSeconds,
                onSelect = { vm.updateReadTimeout(it) },
            )
        }

        Section(stringResource(Res.string.settings_data_management)) {
            // Disable both while either OPML op runs (import can take a while — one fetch per feed)
            // so the buttons don't look inert/re-triggerable; the running one shows a spinner in
            // place of its icon.
            val opmlBusy = vm.importingOpml || vm.exportingOpml
            Row {
                FlatTonalButton(onClick = { vm.importOpml() }, enabled = !opmlBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.importingOpml) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_import_opml))
                    }
                }
                Spacer(Modifier.width(8.dp))
                FlatTonalButton(onClick = { vm.exportOpml() }, enabled = !opmlBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.exportingOpml) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_export_opml))
                    }
                }
            }
            opmlStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** アップデート: update-check interval / check now / result. */
@Composable
private fun UpdatesTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
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
                    Icon(
                        Icons.Outlined.Update,
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

/** Brand icon for a cloud provider's connection row. */
private fun CloudStorageType.brandIcon(): DrawableResource = when (this) {
    CloudStorageType.DROPBOX -> Res.drawable.dropbox
    CloudStorageType.GOOGLE_DRIVE -> Res.drawable.google_drive
}

/**
 * Brand name for a cloud provider's connection row. Deliberately a hardcoded literal, not a
 * string resource — these are untranslated product names (matching the pre-existing "Dropbox"
 * literal this section already used).
 */
private fun CloudStorageType.brandLabel(): String = when (this) {
    CloudStorageType.DROPBOX -> "Dropbox"
    CloudStorageType.GOOGLE_DRIVE -> "Google Drive"
}

private fun CloudStorageType.connectLabel(): StringResource = when (this) {
    CloudStorageType.DROPBOX -> Res.string.settings_dropbox_connect
    CloudStorageType.GOOGLE_DRIVE -> Res.string.settings_google_drive_connect
}

private fun CloudStorageType.disconnectLabel(): StringResource = when (this) {
    CloudStorageType.DROPBOX -> Res.string.settings_dropbox_disconnect
    CloudStorageType.GOOGLE_DRIVE -> Res.string.settings_google_drive_disconnect
}

/**
 * One provider's row in the cloud-sync tab: brand icon + name, and a trailing
 * connect/disconnect/abort control (the sole click target — clicking the connect button opens the
 * switch-confirmation dialog instead of connecting directly when a different provider is already
 * connected). A failed connect shows its error inline under this specific row.
 */
@Composable
private fun CloudProviderRow(
    type: CloudStorageType,
    connected: Boolean,
    connecting: Boolean,
    canCancel: Boolean,
    idleEnabled: Boolean,
    failed: Boolean,
    lastSyncedAtText: String? = null,
    resetting: Boolean = false,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onResetCloudData: () -> Unit = {},
) {
    // The connected row gets a step-up accent (same secondaryContainer/onSecondaryContainer
    // tokens KeryxDialogTabBar uses for its selected tab) so it still stands out once nested
    // inside the outer SettingsCard's surfaceContainerLow background; unconnected rows stay
    // transparent (no extra tint over the card).
    val contentColor = if (connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (connected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(type.brandIcon()),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(type.brandLabel(), style = MaterialTheme.typography.bodyMedium, color = contentColor)
            }
            if (connected) {
                // Disabled during a switch (old provider's revoke in flight, connectingType != null)
                // or a reset in progress, so neither destructive action can be re-triggered mid-op.
                val enabled = idleEnabled && !resetting
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatTonalButton(onClick = onResetCloudData, enabled = enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (resetting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Outlined.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.settings_cloud_reset))
                        }
                    }
                    FlatTonalButton(onClick = onDisconnect, enabled = enabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(type.disconnectLabel()))
                        }
                    }
                }
            } else if (connecting && canCancel) {
                // Still waiting on the OAuth browser redirect — offer an explicit abort.
                FlatTonalButton(onClick = onCancel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.common_abort))
                    }
                }
            } else {
                // Idle, or past the cancellable window (finishing up: saving tokens/settings/syncing).
                FlatButton(onClick = onSelect, enabled = idleEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(type.connectLabel()))
                    }
                }
            }
        }
        // Last-synced shown as a subtitle under the provider name (only non-null for the connected
        // row) — its own full-width line so it never wraps against the trailing action buttons.
        lastSyncedAtText?.let { syncedAt ->
            Text(
                stringResource(Res.string.settings_last_synced, syncedAt),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }
        if (failed) {
            Text(
                stringResource(Res.string.setup_auth_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Groups a section/tab's rows into a bordered, tonal container (the app's "flat surface pattern"
 * — see `ui-guidelines`: `surfaceContainerLow` fill + hairline `outlineVariant` border, no tonal
 * elevation — also used by `NotificationCenterSheet`/`SetupScreen.OptionCard`/`FlatTooltipContent`).
 * Ties a row's label and its trailing control (e.g. a switch pinned to the far edge by
 * `weight(1f)`) together as one visible unit instead of floating disconnected across the dialog's
 * fixed 640dp width.
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * A titled sub-group inside a tab (heading + trailing divider). Used only in tabs that hold more
 * than one sub-group (一般 / データ); single-group tabs omit the heading, which would just repeat
 * the tab name.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    content()
    HorizontalDivider(
        Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * A tappable text row that opens [url] in the external browser (rendered in the theme's primary
 * color). On hover it underlines, switches to a hand cursor, and shows the destination [url] in a
 * tooltip, so it reads clearly as a link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkRow(label: String, url: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(url) },
        state = rememberTooltipState(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = if (hovered) TextDecoration.Underline else null,
            modifier = Modifier
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interactionSource) { BrowserOpener.open(url) }
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FlatSwitch(checked = checked, onCheckedChange = onChange)
    }
}

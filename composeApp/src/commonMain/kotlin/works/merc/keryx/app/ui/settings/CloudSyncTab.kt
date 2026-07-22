package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.ui.common.FlatButton
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_abort
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.dropbox
import works.merc.keryx.app.resources.google_drive
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
import works.merc.keryx.app.resources.settings_cloud_sync_hint
import works.merc.keryx.app.resources.settings_dropbox_connect
import works.merc.keryx.app.resources.settings_dropbox_disconnect
import works.merc.keryx.app.resources.settings_google_drive_connect
import works.merc.keryx.app.resources.settings_google_drive_disconnect
import works.merc.keryx.app.resources.settings_last_synced
import works.merc.keryx.app.resources.setup_auth_failed

/** クラウド同期: provider connect/disconnect/switch, with the three confirmation dialogs. */
@Composable
internal fun CloudSyncTabContent(vm: SettingsViewModel) {
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
                                KeryxIcon(
                                    KeryxIcons.RestartAlt,
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
                            KeryxIcon(
                                KeryxIcons.LinkOff,
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

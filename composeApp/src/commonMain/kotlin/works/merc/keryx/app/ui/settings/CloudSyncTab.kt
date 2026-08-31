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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.platform.isTouchPrimary
import works.merc.keryx.app.ui.common.FlatButton
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.IconButtonKind
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.common.TooltipIconButton
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_abort
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.dropbox
import works.merc.keryx.app.resources.google_drive
import works.merc.keryx.app.resources.onedrive
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
import works.merc.keryx.app.resources.settings_onedrive_connect
import works.merc.keryx.app.resources.settings_onedrive_disconnect
import works.merc.keryx.app.resources.settings_last_synced
import works.merc.keryx.app.resources.setup_auth_failed

/**
 * Cloud sync tab: provider connect/disconnect/switch, with the three confirmation dialogs.
 *
 * @param vm The view model supplying cloud provider state and handling user actions.
 */
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
                    // Only meaningful for the connected provider: it's why its background syncs
                    // are currently failing (an expired token, a transient outage, bad cloud data).
                    lastSyncErrorText = if (connected == type) vm.lastSyncErrorText else null,
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
    CloudStorageType.ONEDRIVE -> Res.drawable.onedrive
}

/**
 * Brand name for a cloud provider's connection row. Deliberately a hardcoded literal, not a
 * string resource — these are untranslated product names (matching the pre-existing "Dropbox"
 * literal this section already used).
 */
private fun CloudStorageType.brandLabel(): String = when (this) {
    CloudStorageType.DROPBOX -> "Dropbox"
    CloudStorageType.GOOGLE_DRIVE -> "Google Drive"
    CloudStorageType.ONEDRIVE -> "OneDrive"
}

/**
 * Provides the localized label for connecting to the cloud storage provider.
 *
 * @return The provider-specific connect button label resource.
 */
private fun CloudStorageType.connectLabel(): StringResource = when (this) {
    CloudStorageType.DROPBOX -> Res.string.settings_dropbox_connect
    CloudStorageType.GOOGLE_DRIVE -> Res.string.settings_google_drive_connect
    CloudStorageType.ONEDRIVE -> Res.string.settings_onedrive_connect
}

/**
 * Gets the localized label for disconnecting from the cloud provider.
 *
 * @return The provider-specific disconnect label resource.
 */
private fun CloudStorageType.disconnectLabel(): StringResource = when (this) {
    CloudStorageType.DROPBOX -> Res.string.settings_dropbox_disconnect
    CloudStorageType.GOOGLE_DRIVE -> Res.string.settings_google_drive_disconnect
    CloudStorageType.ONEDRIVE -> Res.string.settings_onedrive_disconnect
}

/**
 * One trailing action on a provider row: a labelled button on desktop, an icon-only
 * [TooltipIconButton] on a touch-primary platform. Two labelled buttons plus the provider name
 * cannot fit a phone-width settings dialog in any locale (they need ~340-366dp of ~288dp), which
 * used to squeeze the name onto four lines.
 *
 * [kind] is the one emphasis axis, rendered by each platform's own means: the icon-only button
 * takes it as its container, while labelled it selects the button component (`Primary` -> the
 * filled `FlatButton`, `Destructive` -> `FlatTonalButton(destructive = true)`, anything else -> a
 * plain `FlatTonalButton`). The glyph therefore never needs its own tint — it inherits the
 * container's content color (`onPrimary` / `onErrorContainer`) on both platforms.
 *
 * @param busy Swaps the glyph for a spinner in the same fixed slot, so the swap can't reflow.
 * @param iconOnly Overridable for tests only (mirrors `listRowMinHeight`'s own parameter).
 */
@Composable
private fun ProviderActionButton(
    label: String,
    icon: DrawableResource,
    onClick: () -> Unit,
    kind: IconButtonKind,
    enabled: Boolean = true,
    busy: Boolean = false,
    iconOnly: Boolean = isTouchPrimary,
) {
    // 18dp keeps desktop's labelled buttons exactly as they look today; 20dp matches the row's own
    // brand mark inside the bare icon buttons.
    val glyphSize = if (iconOnly) 20.dp else 18.dp
    val glyph: @Composable () -> Unit = {
        if (busy) {
            // Follow the container's content color: a primary-filled container would otherwise
            // hide SmallSpinner's own `primary` default entirely.
            SmallSpinner(size = glyphSize, color = LocalContentColor.current)
        } else {
            // Icon-only: the glyph is the sole carrier of the label (as at every other
            // TooltipIconButton call site — neither actual sets an onClickLabel). Labelled: the
            // Text beside it announces the action, so the glyph is decorative.
            KeryxIcon(icon, contentDescription = label.takeIf { iconOnly }, modifier = Modifier.size(glyphSize))
        }
    }
    if (iconOnly) {
        TooltipIconButton(tooltip = label, onClick = onClick, enabled = enabled, kind = kind, content = glyph)
        return
    }
    val labelled: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            glyph()
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
    when (kind) {
        IconButtonKind.Primary -> FlatButton(onClick = onClick, enabled = enabled, content = labelled)
        IconButtonKind.Destructive ->
            FlatTonalButton(onClick = onClick, enabled = enabled, destructive = true, content = labelled)
        else -> FlatTonalButton(onClick = onClick, enabled = enabled, content = labelled)
    }
}

/**
 * Displays a cloud provider's connection state, available actions, and synchronization details.
 *
 * @param lastSyncedAtText Formatted time of the provider's most recent synchronization, or null.
 * @param lastSyncErrorText Localized explanation of the provider's current synchronization failure, or null.
 * @param iconOnly Whether the trailing actions drop their labels — see [ProviderActionButton]. Only
 *   overridden by tests; production always takes the platform's own answer.
 * @param onSelect Invoked to select or connect the provider.
 * @param onCancel Invoked to abort an in-progress connection.
 * @param onDisconnect Invoked to disconnect the provider.
 * @param onResetCloudData Invoked to reset the provider's cloud data.
 */
@Composable
internal fun CloudProviderRow(
    type: CloudStorageType,
    connected: Boolean,
    connecting: Boolean,
    canCancel: Boolean,
    idleEnabled: Boolean,
    failed: Boolean,
    lastSyncedAtText: String? = null,
    lastSyncErrorText: String? = null,
    resetting: Boolean = false,
    iconOnly: Boolean = isTouchPrimary,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onResetCloudData: () -> Unit = {},
) {
    // The connected row gets a step-up accent (same secondaryContainer/onSecondaryContainer
    // tokens desktop's settings-dialog tab bar, KeryxDialogTabBar, uses for its selected tab) so
    // it still stands out once nested inside the outer SettingsCard's surfaceContainerLow
    // background; unconnected rows stay
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
                Text(
                    type.brandLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    // A `Row` measures its non-weighted children first, so the trailing actions
                    // always take their intrinsic width and only the remainder reaches the name.
                    // Truncating here is what keeps that remainder from becoming four wrapped lines.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected) {
                // Disabled during a switch (old provider's revoke in flight, connectingType != null)
                // or a reset in progress, so neither destructive action can be re-triggered mid-op.
                val enabled = idleEnabled && !resetting
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderActionButton(
                        label = stringResource(Res.string.settings_cloud_reset),
                        icon = KeryxIcons.Delete,
                        onClick = onResetCloudData,
                        kind = IconButtonKind.Destructive,
                        enabled = enabled,
                        busy = resetting,
                        iconOnly = iconOnly,
                    )
                    ProviderActionButton(
                        label = stringResource(type.disconnectLabel()),
                        icon = KeryxIcons.LinkOff,
                        onClick = onDisconnect,
                        kind = IconButtonKind.Secondary,
                        enabled = enabled,
                        iconOnly = iconOnly,
                    )
                }
            } else if (connecting && canCancel) {
                // Still waiting on the OAuth browser redirect — offer an explicit abort. Labelled,
                // the spinner rides inside the button (as it always has); icon-only it needs its own
                // slot, so the button can keep showing the abort glyph instead of progress.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (iconOnly) SmallSpinner(size = 20.dp) // matches the icon-only glyph size beside it
                    ProviderActionButton(
                        label = stringResource(Res.string.common_abort),
                        icon = KeryxIcons.CloseOutlined,
                        onClick = onCancel,
                        kind = IconButtonKind.Secondary,
                        busy = !iconOnly,
                        iconOnly = iconOnly,
                    )
                }
            } else {
                // Idle, or past the cancellable window (finishing up: saving tokens/settings/syncing).
                ProviderActionButton(
                    label = stringResource(type.connectLabel()),
                    icon = KeryxIcons.Link,
                    onClick = onSelect,
                    kind = IconButtonKind.Primary,
                    enabled = idleEnabled,
                    busy = connecting,
                    iconOnly = iconOnly,
                )
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
        // An in-progress sync failure (already localized per exception type) takes precedence: it
        // describes the live state of a working connection, whereas `failed` only reports that the
        // last connect attempt didn't complete.
        val errorText = lastSyncErrorText ?: stringResource(Res.string.setup_auth_failed).takeIf { failed }
        errorText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
            )
        }
    }
}

package works.merc.keryx.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.app_name
import works.merc.keryx.app.resources.common_abort
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.dropbox
import works.merc.keryx.app.resources.google_drive
import works.merc.keryx.app.resources.onedrive
import works.merc.keryx.app.resources.setup_abort_connect_confirm_body
import works.merc.keryx.app.resources.setup_abort_connect_confirm_title
import works.merc.keryx.app.resources.setup_auth_failed
import works.merc.keryx.app.resources.setup_choose_mode
import works.merc.keryx.app.resources.setup_connecting
import works.merc.keryx.app.resources.setup_dropbox
import works.merc.keryx.app.resources.setup_dropbox_desc
import works.merc.keryx.app.resources.setup_google_drive
import works.merc.keryx.app.resources.setup_google_drive_desc
import works.merc.keryx.app.resources.setup_onedrive
import works.merc.keryx.app.resources.setup_onedrive_desc
import works.merc.keryx.app.resources.setup_local_desc
import works.merc.keryx.app.resources.setup_local_only
import works.merc.keryx.app.resources.setup_title

/** Setup card copy + brand icon for a cloud provider. */
private class CloudSetupOption(
    val title: StringResource,
    val description: StringResource,
    val icon: DrawableResource,
)

private fun CloudStorageType.setupOption(): CloudSetupOption = when (this) {
    CloudStorageType.DROPBOX ->
        CloudSetupOption(Res.string.setup_dropbox, Res.string.setup_dropbox_desc, Res.drawable.dropbox)
    CloudStorageType.GOOGLE_DRIVE ->
        CloudSetupOption(Res.string.setup_google_drive, Res.string.setup_google_drive_desc, Res.drawable.google_drive)
    CloudStorageType.ONEDRIVE ->
        CloudSetupOption(Res.string.setup_onedrive, Res.string.setup_onedrive_desc, Res.drawable.onedrive)
}

@Composable
fun SetupScreen(onComplete: () -> Unit) {
    val vm = koinInject<SetupViewModel>()

    // Confirmation-dialog trigger for aborting an in-flight OAuth wait.
    var confirmingAbortConnect by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.setup_choose_mode),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            OptionCard(
                title = stringResource(Res.string.setup_local_only),
                description = stringResource(Res.string.setup_local_desc),
                enabled = vm.phase != SetupPhase.CONNECTING,
                onClick = { vm.chooseLocalOnly(onComplete) },
            )
            vm.availableCloudTypes.forEach { type ->
                val option = type.setupOption()
                Spacer(Modifier.height(12.dp))
                OptionCard(
                    title = stringResource(option.title),
                    description = stringResource(option.description),
                    enabled = vm.phase != SetupPhase.CONNECTING,
                    icon = option.icon,
                    onClick = { vm.connect(type, onComplete) },
                )
            }

            Spacer(Modifier.height(16.dp))
            when (vm.phase) {
                SetupPhase.CONNECTING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.setup_connecting))
                    if (vm.canCancelConnect) {
                        Spacer(Modifier.height(12.dp))
                        FlatTonalButton(onClick = { confirmingAbortConnect = true }) {
                            Text(stringResource(Res.string.common_abort))
                        }
                    }
                }
                SetupPhase.ERROR -> Text(
                    stringResource(Res.string.setup_auth_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                SetupPhase.IDLE -> Unit
            }
        }
        VerticalScrollbarIfNeeded(scrollState)
    }

    if (confirmingAbortConnect) {
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingAbortConnect = false },
            title = stringResource(Res.string.setup_abort_connect_confirm_title),
            text = { Text(stringResource(Res.string.setup_abort_connect_confirm_body)) },
            confirmText = stringResource(Res.string.common_abort),
            onConfirm = { vm.cancelConnect(); confirmingAbortConnect = false },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
}

@Composable
private fun OptionCard(
    title: String,
    description: String,
    enabled: Boolean,
    icon: DrawableResource? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = 420.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlatTonalButton(onClick = onClick, enabled = enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Image(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        KeryxIcon(
                            KeryxIcons.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(title)
                }
            }
        }
    }
}

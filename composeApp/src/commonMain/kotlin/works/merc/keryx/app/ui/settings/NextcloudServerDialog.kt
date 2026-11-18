package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.data.cloud.nextcloud.NextcloudServerProbe
import works.merc.keryx.app.domain.CloudConnectRequest
import works.merc.keryx.app.presentation.settings.NextcloudServerController
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.nextcloud_server_checking
import works.merc.keryx.app.resources.nextcloud_server_continue
import works.merc.keryx.app.resources.nextcloud_server_hint
import works.merc.keryx.app.resources.nextcloud_server_scope
import works.merc.keryx.app.resources.nextcloud_server_title
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxTextField
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.i18n.errorMessage

/**
 * Asks for the Nextcloud server to connect to and checks it ([NextcloudServerController]) before
 * handing the connect request on — opened by every Nextcloud connect route: first-run setup, and the
 * Cloud Sync tab's connect, switch and reconnect. Says up front what the sign-in grants (an app
 * password reaching the whole account) and how to revoke or narrow it.
 *
 * @param initialUrl The address the field starts with — the server last connected to, on a reconnect.
 * @param onDismiss Called when the dialog is cancelled; nothing is connected or torn down.
 * @param onConfirmed Called with the checked request once the server passed; the caller closes the
 *   dialog and starts the connect.
 */
@Composable
internal fun NextcloudServerDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirmed: (CloudConnectRequest) -> Unit,
    probe: NextcloudServerProbe = koinInject(),
) {
    // The typed address survives an Android configuration change, like AddFeedDialog's URL.
    var savedUrl by rememberSaveable { mutableStateOf(initialUrl) }
    val controller = remember(probe) { NextcloudServerController(probe::probe, initialUrl = savedUrl) }
    val state by controller.state.collectAsState()
    LaunchedEffect(state.url) { savedUrl = state.url }
    val scope = rememberCoroutineScope()
    val checkGate = remember(scope) { NextcloudServerCheckGate(scope) }
    // Closing the gate on a pass too keeps a second Continue in the same frame from connecting twice.
    val submit: () -> Unit = {
        checkGate.start(controller::submit) { request ->
            checkGate.close()
            onConfirmed(request)
        }
    }

    KeryxAlertDialog(
        // Cancel, Escape, Back and an outside click all land here.
        onDismissRequest = {
            checkGate.close()
            onDismiss()
        },
        title = stringResource(Res.string.nextcloud_server_title),
        text = {
            NextcloudServerDialogContent(
                url = state.url,
                onUrlChange = controller::setUrl,
                checking = state.checking,
                error = state.error,
                onSubmit = submit,
            )
        },
        confirmText = stringResource(Res.string.nextcloud_server_continue),
        onConfirm = submit,
        confirmEnabled = state.confirmEnabled,
        dismissText = stringResource(Res.string.common_cancel),
    )
}

/** The scope notice, the address field, and one status line (checking, or why the check failed). */
@Composable
internal fun NextcloudServerDialogContent(
    url: String,
    onUrlChange: (String) -> Unit,
    checking: Boolean,
    error: ErrorKind?,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    Column(modifier) {
        Text(
            stringResource(Res.string.nextcloud_server_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        KeryxTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            placeholder = stringResource(Res.string.nextcloud_server_hint),
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // One status slot, reserved whether or not anything is in it, so a check starting or
        // failing never moves the field (the ui-guidelines skill's layout-stability rule).
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 20.dp), contentAlignment = Alignment.CenterStart) {
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSpinner(size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.nextcloud_server_checking), style = MaterialTheme.typography.bodySmall)
                }
                error != null -> Text(
                    errorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

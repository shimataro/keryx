package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.common_save
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.KeryxTextField

@Composable
internal fun TextPromptDialog(
    title: String,
    hint: String,
    initial: String,
    allowBlank: Boolean = false,
    blockingError: (String) -> String? = { null },
    infoHint: (String) -> String? = { null },
    extraContent: @Composable () -> Unit = {},
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val trimmed = text.trim()
    val error = if (!allowBlank && trimmed.isBlank()) null else blockingError(trimmed)
    val canConfirm = (allowBlank || trimmed.isNotBlank()) && error == null
    val message = error ?: infoHint(trimmed)

    fun submit() {
        if (canConfirm) onConfirm(trimmed)
    }

    KeryxAlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = title,
        text = {
            Column {
                KeryxTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = hint,
                    isError = error != null,
                    supportingText = message,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                extraContent()
            }
        },
        confirmText = stringResource(Res.string.common_save),
        onConfirm = { submit() },
        confirmEnabled = canConfirm,
        dismissText = stringResource(Res.string.common_cancel),
    )
}

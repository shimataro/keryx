package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp

/**
 * Android `actual`: M3's own [OutlinedTextField] — the desirable look on this platform (see the
 * `expect`'s KDoc in commonMain), unlike desktop's flat restyle.
 *
 * [minHeight]/[horizontalPadding] exist only for desktop's inline-row-editing convention (see
 * `ui/home/InlineRename.kt`), which this actual does not attempt to replicate pixel-for-pixel —
 * `minHeight` is applied as a lower bound on top of M3's own sizing, and `horizontalPadding` is
 * unused, since `OutlinedTextField` manages its own internal content padding and exposes no simple
 * override for it. The [TextFieldValue]/[TextRange] bookkeeping otherwise mirrors the desktop
 * actual exactly, to preserve [initiallySelectAll] and "an externally-changed value replaces the
 * local copy with the caret at the end" behavior identically.
 */
@Composable
actual fun KeryxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String?,
    singleLine: Boolean,
    isError: Boolean,
    supportingText: String?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    minHeight: Dp,
    horizontalPadding: Dp,
    initiallySelectAll: Boolean,
) {
    var fieldValue by remember {
        val selection = if (initiallySelectAll) TextRange(0, value.length) else TextRange(value.length)
        mutableStateOf(TextFieldValue(value, selection))
    }
    val displayed = if (fieldValue.text == value) fieldValue else TextFieldValue(value, TextRange(value.length))

    OutlinedTextField(
        value = displayed,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != value) onValueChange(updated.text)
        },
        modifier = modifier.heightIn(min = minHeight),
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

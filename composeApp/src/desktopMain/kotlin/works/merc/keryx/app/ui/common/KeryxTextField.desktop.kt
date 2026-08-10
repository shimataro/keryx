package works.merc.keryx.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Desktop `actual`: a flat, thin-bordered field built on [BasicTextField]. See the `expect` in
 * `commonMain` for the rationale (Native-feel restyle; no SwingPanel). The [modifier] goes on the
 * inner [BasicTextField] so a caller's `focusRequester` / `onFocusChanged { it.isFocused }` keeps
 * working; the border/background/padding/leading/trailing/placeholder are drawn in `decorationBox`.
 *
 * The caller still owns a plain [String]; the [TextFieldValue] below is internal state, kept only so
 * the caret/selection can be positioned (`initiallySelectAll`). It mirrors what
 * `BasicTextField(value: String, …)` does internally, except that its initial selection is
 * configurable and defaults to the end of the text rather than to index 0.
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
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var fieldValue by remember {
        val selection = if (initiallySelectAll) TextRange(0, value.length) else TextRange(value.length)
        mutableStateOf(TextFieldValue(value, selection))
    }
    // A value changed from the outside (the caller rejecting/transforming input, or state arriving
    // from elsewhere) wins over the local copy, and puts the caret at the end of the new text.
    val displayed = if (fieldValue.text == value) fieldValue else TextFieldValue(value, TextRange(value.length))

    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(modifier = Modifier) {
        BasicTextField(
            value = displayed,
            onValueChange = { updated ->
                fieldValue = updated
                if (updated.text != value) onValueChange(updated.text)
            },
            modifier = modifier,
            singleLine = singleLine,
            interactionSource = interactionSource,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .border(BorderStroke(1.dp, borderColor), MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .heightIn(min = minHeight)
                        .padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { leadingIcon() }
                        Box(Modifier.padding(start = 8.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingIcon != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { trailingIcon() }
                    }
                }
            },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

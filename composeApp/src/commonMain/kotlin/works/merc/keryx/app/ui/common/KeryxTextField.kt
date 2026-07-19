package works.merc.keryx.app.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Drop-in replacement for a single-line `androidx.compose.material3.OutlinedTextField`. The desktop
 * `actual` renders a flat, thin-bordered native-feel field (hairline `outlineVariant` border, small
 * corner radius, accent-colored border on focus) instead of M3's tall outlined box with its floating
 * label — matching the app's Native-feel restyle conventions (see `.claude/ui-guidelines.md`). When an
 * Android target is added, its `actual` can go back to M3's `OutlinedTextField`/`TextField`, which is
 * the desirable look on Android.
 *
 * The [modifier] is applied to the inner text field (not an outer frame), so a caller's
 * `focusRequester` / `onFocusChanged { it.isFocused }` behaves exactly as it did on `OutlinedTextField`.
 * The placeholder contrast, border colors, and supporting-text styling are internal — call sites no
 * longer pass a `colors = OutlinedTextFieldDefaults.colors(...)` override.
 */
@Composable
expect fun KeryxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
)

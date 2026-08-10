package works.merc.keryx.app.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Default metrics of a stand-alone [KeryxTextField] (a dialog/search field). */
object KeryxTextFieldDefaults {
    /** Minimum height of the field's frame, giving a stand-alone field its comfortable click target. */
    val MinHeight: Dp = 40.dp

    /** Inset between the field's frame and its text/icons. */
    val HorizontalPadding: Dp = 12.dp
}

/**
 * Drop-in replacement for a single-line `androidx.compose.material3.OutlinedTextField`. The desktop
 * `actual` renders a flat, thin-bordered native-feel field (hairline `outlineVariant` border, small
 * corner radius, accent-colored border on focus) instead of M3's tall outlined box with its floating
 * label — matching the app's Native-feel restyle conventions (see `.claude/skills/ui-guidelines/SKILL.md`). When an
 * Android target is added, its `actual` can go back to M3's `OutlinedTextField`/`TextField`, which is
 * the desirable look on Android.
 *
 * The [modifier] is applied to the inner text field (not an outer frame), so a caller's
 * `focusRequester` / `onFocusChanged { it.isFocused }` behaves exactly as it did on `OutlinedTextField`.
 * The placeholder contrast, border colors, and supporting-text styling are internal — call sites no
 * longer pass a `colors = OutlinedTextFieldDefaults.colors(...)` override.
 *
 * [minHeight] and [horizontalPadding] exist for the one call site that is *not* stand-alone: the
 * feed list's inline row editor (`ui/home/InlineRename.kt`), which must occupy exactly the height of
 * the label `Text` it replaces so entering edit mode moves nothing around it. Leave them at their
 * [KeryxTextFieldDefaults] values everywhere else.
 *
 * [initiallySelectAll] preselects the whole initial [value], so the first keystroke replaces it
 * (the rename convention of every desktop file manager). The default keeps the caret at the end.
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
    minHeight: Dp = KeryxTextFieldDefaults.MinHeight,
    horizontalPadding: Dp = KeryxTextFieldDefaults.HorizontalPadding,
    initiallySelectAll: Boolean = false,
)

package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxTextField

/** Test tag on the inline row editor's text field (feed / folder / tag rows). */
internal const val INLINE_RENAME_FIELD_TEST_TAG = "inline-rename-field"

/** Test tag on the inline row editor's explicit "×" cancel affordance. */
internal const val INLINE_RENAME_CANCEL_TEST_TAG = "inline-rename-cancel"

/** Text inset inside the inline editor's frame. Tighter than a stand-alone field's
 * [works.merc.keryx.app.ui.common.KeryxTextFieldDefaults.HorizontalPadding], so the text being
 * edited stays near where the label it replaced was drawn. */
private val InlineRenameHorizontalPadding = 6.dp

/** Size of the "×" cancel glyph. Deliberately smaller than one line of the row's own text, so
 * showing it can never make the editor — and therefore the row — taller than the label it replaced. */
private val InlineRenameCancelIconSize = 16.dp

/**
 * Whether an edited name may be committed, and the message (if any) that says why not.
 *
 * A blank value is deliberately **not** an error: it produces no message and no red frame, it simply
 * cannot be committed — unless `allowBlank` says a blank value is itself meaningful, in which case
 * it is validated like any other. This is [TextPromptDialog]'s exact model, reused rather than
 * reinvented, so the inline editor and the remaining dialogs agree on what "invalid" means.
 */
internal data class InlineRenameValidation(val error: String?, val canCommit: Boolean)

/** Validates [text] for [InlineRenameField]. See [InlineRenameValidation]. */
internal fun inlineRenameValidation(
    text: String,
    allowBlank: Boolean,
    blockingError: (String) -> String?,
): InlineRenameValidation {
    val trimmed = text.trim()
    val error = if (!allowBlank && trimmed.isBlank()) null else blockingError(trimmed)
    return InlineRenameValidation(error = error, canCommit = (allowBlank || trimmed.isNotBlank()) && error == null)
}

/**
 * The feed list's in-row name editor: the row's label `Text` swapped for a text field occupying the
 * same slot, instead of a modal rename dialog.
 *
 * Committing and cancelling follow the desktop file-manager convention:
 * - **Enter** (or the IME's `Done` action, so a future touch target needs no extra branch) commits,
 *   but only when the value passes validation — otherwise the keystroke is swallowed and the editor
 *   stays open (and stays red if [blockingError] produced a message).
 * - **Escape**, and the "×" affordance next to the text, always cancel and restore the original value.
 *   The "×" exists because Escape has no touch equivalent, and because Escape is hard to discover.
 * - **Losing focus** commits when the value is valid, and otherwise silently cancels: focus has
 *   already moved on by then, so dragging it back to a rejected row would trap the user.
 *
 * Validation deliberately reuses [TextPromptDialog]'s exact model rather than inventing a second one:
 * a blank value is *not* an error (no red frame), it simply cannot be committed unless [allowBlank]
 * says a blank value is meaningful — and [blockingError] is only consulted for values that are
 * committable in the first place.
 *
 * @param value The name to start editing from.
 * @param onCommit Receives the trimmed value. Only called once, and only for a valid value.
 * @param onCancel Called instead of [onCommit] when the edit is abandoned. Only called once.
 * @param placeholder Shown while the field is empty (e.g. the title a blanked feed name falls back to).
 * @param allowBlank Whether a blank value is a meaningful, committable value.
 * @param blockingError Produces a validation message for the trimmed value, or `null` when valid.
 */
@Composable
internal fun InlineRenameField(
    value: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    allowBlank: Boolean = false,
    blockingError: (String) -> String? = { null },
) {
    var text by remember { mutableStateOf(value) }
    // The editor is removed from the composition by its own commit/cancel, which loses focus in
    // turn — without these two guards that focus loss would run the blur path a second time.
    var everFocused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val cancelLabel = stringResource(Res.string.common_cancel)

    val trimmed = text.trim()
    val (error, canCommit) = inlineRenameValidation(text, allowBlank, blockingError)

    fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        if (commit) onCommit(trimmed) else onCancel()
    }

    KeryxTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = placeholder,
        isError = error != null,
        minHeight = 0.dp,
        horizontalPadding = InlineRenameHorizontalPadding,
        initiallySelectAll = true,
        trailingIcon = {
            KeryxIcon(
                KeryxIcons.CloseFilled,
                contentDescription = cancelLabel,
                modifier = Modifier
                    .testTag(INLINE_RENAME_CANCEL_TEST_TAG)
                    .size(InlineRenameCancelIconSize)
                    // `clickable` is focusable by default, and taking focus from the field beside it
                    // would run the blur path — *committing* — before this click's own handler ever
                    // ran, making the cancel button commit.
                    .focusProperties { canFocus = false }
                    .clickable(onClickLabel = cancelLabel) { finish(commit = false) },
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canCommit) finish(commit = true) }),
        modifier = modifier
            .testTag(INLINE_RENAME_FIELD_TEST_TAG)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) everFocused = true else if (everFocused) finish(commit = canCommit)
            }
            // Preview, so Enter/Escape are handled here rather than by the text field itself — and
            // so an invalid Enter is swallowed instead of falling through to anything else.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { finish(commit = false); true }
                    Key.Enter, Key.NumPadEnter -> { if (canCommit) finish(commit = true); true }
                    else -> false
                }
            },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

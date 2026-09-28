package works.merc.keryx.app.presentation.home

/**
 * Whether an edited name may be committed, and the message (if any) that says why not.
 *
 * A blank value is deliberately **not** an error: it produces no message and no red frame, it simply
 * cannot be committed — unless `allowBlank` says a blank value is itself meaningful, in which case
 * it is validated like any other. Shared by every single-line name-entry surface (the Compose
 * `InlineRenameField` and `TextPromptDialog`, and the Apple app's inline row editor), so they all
 * agree on what "invalid" means.
 */
data class InlineRenameValidation(val error: String?, val canCommit: Boolean)

/** Validates [text] for an inline row editor. See [InlineRenameValidation]. */
fun inlineRenameValidation(
    text: String,
    allowBlank: Boolean,
    blockingError: (String) -> String?,
): InlineRenameValidation {
    val trimmed = text.trim()
    val error = if (!allowBlank && trimmed.isBlank()) null else blockingError(trimmed)
    return InlineRenameValidation(error = error, canCommit = (allowBlank || trimmed.isNotBlank()) && error == null)
}

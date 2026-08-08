package works.merc.keryx.app.platform

/** An open-file dialog request. [filterLabel] is only shown by backends with a filter dropdown. */
data class OpenFileRequest(val title: String, val extensions: List<String>, val filterLabel: String)

/**
 * A save-file dialog request. The `overwrite*` fields are only used by backends whose dialog does
 * not confirm an overwrite natively (see `SwingFilePickerBackend`); a native dialog that already
 * prompts (macOS/Windows `FileDialog`) ignores them.
 */
data class SaveFileRequest(
    val title: String,
    val defaultName: String,
    val overwriteTitle: String,
    val overwriteMessage: String,
    val overwriteReplaceLabel: String,
    val overwriteCancelLabel: String,
)

/** Native open/save file dialogs (desktop). */
expect object FilePicker {
    /** Shows an open dialog; returns the chosen path or null if cancelled. */
    suspend fun pickOpenFile(request: OpenFileRequest): String?

    /** Shows a save dialog; returns the chosen path or null if cancelled. */
    suspend fun pickSaveFile(request: SaveFileRequest): String?
}

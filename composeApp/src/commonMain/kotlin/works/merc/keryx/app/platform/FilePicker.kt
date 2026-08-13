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
    /**
     * Shows a dialog for selecting a file to open.
     *
     * @param request The open-dialog title, allowed extensions, and filter label.
     * @return The selected file path, or `null` if the dialog is cancelled.
     */
    suspend fun pickOpenFile(request: OpenFileRequest): String?

    /**
     * Shows a save dialog for the specified request.
     *
     * @param request The save-dialog configuration, including the title and default filename.
     * @return The selected file path, or `null` if the dialog is cancelled.
     */
    suspend fun pickSaveFile(request: SaveFileRequest): String?
}

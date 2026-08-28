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

/**
 * A file the user picked through [FilePicker]: a read/write handle, opaque to the caller, that
 * never exposes how the file is addressed.
 *
 * On desktop this resolves to an absolute filesystem path. It deliberately is not a path in the
 * contract, because Android's Storage Access Framework (`ActivityResultContracts.OpenDocument` /
 * `CreateDocument`) hands back a `content://` `Uri` that no `java.io.File` can open — so an Android
 * target would resolve the same handle through a `ContentResolver` instead.
 */
interface PickedFile {
    /**
     * Reads the whole file as text.
     *
     * @return The file's contents, or `null` if it could not be read.
     */
    suspend fun readText(): String?

    /**
     * Writes text to the file, replacing any existing contents.
     *
     * @param text The text to write.
     * @throws Exception if the write fails (e.g. an `IOException` on either platform's `actual`).
     *   Unlike [readText], a write failure has no "could not do it" sentinel to return instead —
     *   both `actual`s let the underlying I/O exception propagate, and callers (e.g.
     *   `SettingsViewModel.exportOpml`) rely on that to distinguish a genuine failure from success.
     */
    suspend fun writeText(text: String)
}

/** Native open/save file dialogs (desktop). */
expect object FilePicker {
    /**
     * Shows a dialog for selecting a file to open.
     *
     * @param request The open-dialog title, allowed extensions, and filter label.
     * @return A handle to the selected file, or `null` if the dialog is cancelled.
     */
    suspend fun pickOpenFile(request: OpenFileRequest): PickedFile?

    /**
     * Shows a save dialog for the specified request.
     *
     * @param request The save-dialog configuration, including the title and default filename.
     * @return A handle to the selected file, or `null` if the dialog is cancelled.
     */
    suspend fun pickSaveFile(request: SaveFileRequest): PickedFile?
}

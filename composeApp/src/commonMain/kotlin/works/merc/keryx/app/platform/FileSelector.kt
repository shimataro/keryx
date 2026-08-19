package works.merc.keryx.app.platform

/** Test seam over the platform [FilePicker]. Production code uses [PlatformFileSelector]. */
interface FileSelector {
    /**
     * Prompts the user to select a file for opening.
     *
     * @param request The options for the file selection dialog.
     * @return A handle to the selected file, or `null` if no file was selected.
     */
    suspend fun pickOpenFile(request: OpenFileRequest): PickedFile?

    /**
     * Prompts the user to choose a location for saving a file.
     *
     * @param request The save file selection options.
     * @return A handle to the selected file, or `null` if no file is selected.
     */
    suspend fun pickSaveFile(request: SaveFileRequest): PickedFile?
}

/** The production [FileSelector]: the OS's own native dialogs. */
object PlatformFileSelector : FileSelector {
    /**
     * Opens a file selection dialog.
     *
     * @param request The options for selecting a file to open.
     * @return A handle to the selected file, or `null` if no file is selected.
     */
    override suspend fun pickOpenFile(request: OpenFileRequest) = FilePicker.pickOpenFile(request)

    /**
     * Opens a platform file picker for selecting a save destination.
     *
     * @param request The options for the save file picker.
     * @return A handle to the selected file, or `null` if no file is selected.
     */
    override suspend fun pickSaveFile(request: SaveFileRequest) = FilePicker.pickSaveFile(request)
}

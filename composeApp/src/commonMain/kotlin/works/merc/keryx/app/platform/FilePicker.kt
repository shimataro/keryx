package works.merc.keryx.app.platform

/** Native open/save file dialogs (desktop). */
expect object FilePicker {
    /** Shows an open dialog; returns the chosen path or null if cancelled. */
    suspend fun pickOpenFile(title: String, extensions: List<String>): String?

    /** Shows a save dialog; returns the chosen path or null if cancelled. */
    suspend fun pickSaveFile(title: String, defaultName: String): String?
}

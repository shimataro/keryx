package works.merc.keryx.app.platform

/**
 * Stub — Storage Access Framework pickers are Phase 4 work (`ActivityResultContracts.OpenDocument`
 * / `CreateDocument`, wired through `platform/FileSelector.kt`'s interface seam since an
 * `expect object` cannot itself hold an `ActivityResultLauncher`). Until then, OPML import/export
 * in Settings behaves as if the user always cancels the dialog — visibly inert rather than
 * crashing the app, since these are reachable from the UI regardless of cloud-sync state.
 */
actual object FilePicker {
    actual suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? = null

    actual suspend fun pickSaveFile(request: SaveFileRequest): PickedFile? = null
}

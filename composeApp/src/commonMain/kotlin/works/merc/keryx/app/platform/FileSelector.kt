package works.merc.keryx.app.platform

/** Test seam over the platform [FilePicker]. Production code uses [PlatformFileSelector]. */
interface FileSelector {
    suspend fun pickOpenFile(request: OpenFileRequest): String?
    suspend fun pickSaveFile(request: SaveFileRequest): String?
}

/** The production [FileSelector]: the OS's own native dialogs. */
object PlatformFileSelector : FileSelector {
    override suspend fun pickOpenFile(request: OpenFileRequest) = FilePicker.pickOpenFile(request)
    override suspend fun pickSaveFile(request: SaveFileRequest) = FilePicker.pickSaveFile(request)
}

package works.merc.keryx.app.platform

import android.net.Uri
import works.merc.keryx.app.core.Log

private const val TAG = "AndroidFilePicker"

/**
 * A [PickedFile] backed by a SAF `content://` [Uri] rather than a filesystem path — see
 * [PickedFile]'s own KDoc for why the interface is deliberately path-agnostic. Every read/write
 * goes through [android.content.ContentResolver], the only API that can open a `content://` Uri
 * this app doesn't itself own.
 */
private class ContentUriPickedFile(private val uri: Uri) : PickedFile {
    override suspend fun readText(): String? = runCatching {
        AndroidAppContext.application.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().decodeToString() }
    }.onFailure { e -> Log.warn(TAG, "Failed to read the picked file", e) }.getOrNull()

    override suspend fun writeText(text: String) {
        runCatching {
            // "wt": write + truncate. SAF's CreateDocument already creates an empty placeholder at
            // this Uri, but a plain "w" mode's truncate behavior is provider-dependent — "wt" makes
            // the intent explicit so a re-export to the same file can't leave trailing old bytes.
            AndroidAppContext.application.contentResolver.openOutputStream(uri, "wt")
                ?.use { it.write(text.encodeToByteArray()) }
        }.onFailure { e -> Log.warn(TAG, "Failed to write the picked file", e) }
    }
}

/**
 * Android [FilePicker]: Storage Access Framework pickers
 * (`ActivityResultContracts.OpenDocument`/`CreateDocument`), routed through
 * [AndroidFilePickerHost] since this `expect object` cannot itself hold an
 * `ActivityResultLauncher` (see that object's own KDoc).
 *
 * [OpenFileRequest.extensions]/[OpenFileRequest.filterLabel] and every field of
 * [SaveFileRequest.overwriteTitle]/etc. are unused here: SAF filters by MIME type rather than
 * extension (handled by always passing the wildcard MIME type — see
 * [AndroidFilePickerHost.launchOpen]), and its own document-creation UI already handles naming
 * collisions natively, the same reason macOS/Windows's native `FileDialog` backend ignores those
 * same fields.
 */
actual object FilePicker {
    actual suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? =
        AndroidFilePickerHost.launchOpen()?.let { ContentUriPickedFile(it) }

    actual suspend fun pickSaveFile(request: SaveFileRequest): PickedFile? =
        AndroidFilePickerHost.launchCreate(request.defaultName)?.let { ContentUriPickedFile(it) }
}

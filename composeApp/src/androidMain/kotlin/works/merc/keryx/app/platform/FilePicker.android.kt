package works.merc.keryx.app.platform

import android.net.Uri
import works.merc.keryx.app.core.Log
import java.io.IOException

private const val TAG = "AndroidFilePicker"

/**
 * Reads the full text content of a `content://` [uri] via [android.content.ContentResolver], the
 * only API that can open a `content://` Uri this app doesn't itself own. Shared by
 * [ContentUriPickedFile] (a file picked through the SAF pickers below) and `AndroidOpmlOpen.kt`
 * (an `.opml` opened via an incoming `ACTION_VIEW` intent) — both resolve to a `content://` Uri and
 * need identical read handling.
 */
internal suspend fun readTextFromUri(uri: Uri): String? = runCatching {
    AndroidAppContext.application.contentResolver.openInputStream(uri)
        ?.use { it.readBytes().decodeToString() }
}.onFailure { e -> Log.warn(TAG, "Failed to read from $uri", e) }.getOrNull()

/**
 * A [PickedFile] backed by a SAF `content://` [Uri] rather than a filesystem path — see
 * [PickedFile]'s own KDoc for why the interface is deliberately path-agnostic. Every read/write
 * goes through [android.content.ContentResolver], the only API that can open a `content://` Uri
 * this app doesn't itself own.
 */
internal class ContentUriPickedFile(private val uri: Uri) : PickedFile {
    override suspend fun readText(): String? = readTextFromUri(uri)

    override suspend fun writeText(text: String) {
        // Deliberately not wrapped in runCatching — see writeText's own KDoc: the desktop actual
        // (FileIO.writeText) lets an IOException propagate on failure, and a caller (e.g.
        // SettingsViewModel.exportOpml) relies on that to report the export as failed. A silently
        // swallowed failure here previously left the UI reporting "exported" for content that was
        // never actually written — see review finding #4 (v0.11.0..HEAD).
        //
        // "wt": write + truncate. SAF's CreateDocument already creates an empty placeholder at this
        // Uri, but a plain "w" mode's truncate behavior is provider-dependent — "wt" makes the
        // intent explicit so a re-export to the same file can't leave trailing old bytes.
        val stream = AndroidAppContext.application.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("contentResolver.openOutputStream returned null")
        stream.use { it.write(text.encodeToByteArray()) }
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

package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.core.map

/**
 * [CloudStorage] backed by the Google Drive API v3, storing the sync DB in the
 * hidden `appDataFolder` special space (scope `drive.appdata`) — the closest
 * analog to Dropbox's dedicated app folder, invisible in the user's regular Drive.
 *
 * [accessTokenProvider] returns a currently-valid access token (refreshing it if
 * needed); a null result means the user is not connected.
 *
 * Optimistic concurrency uses the file resource's `version` field (a monotonically
 * increasing counter returned for arbitrary binary files) as the opaque `rev`.
 * Drive has no native compare-and-set for content updates, so an [upload] with a
 * non-null `expectedRev` re-reads the current `version` and fails with
 * [SyncConflictException] if it changed since the caller's [download]. This leaves
 * a small check-then-write window, acceptable for the low-risk synced data
 * (read/star state, subscriptions) and reconciled by the sync retry loop.
 */
class GoogleDriveStorage(
    private val client: HttpClient,
    private val accessTokenProvider: suspend () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudStorage {

    private val apiBase = "https://www.googleapis.com/drive/v3"
    private val uploadBase = "https://www.googleapis.com/upload/drive/v3"

    /**
     * Verifies access to the Google Drive app-data folder.
     *
     * @return A successful result when access is available; otherwise, a mapped storage error.
     */
    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.get("$apiBase/files") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("pageSize", "1")
                parameters.append("fields", "files(id)")
            }
        }
        if (response.status.value in 200..299) Result.Ok(Unit) else mapError(response.status.value, response.bodyAsText())
    }

    /**
     * Streams the file identified by the basename of the specified path to disk.
     *
     * @param path The path whose basename identifies the file.
     * @param destPath The local file to write the contents to.
     * @return The downloaded file's Google Drive revision.
     */
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = withToken { token ->
        val file = when (val f = findFile(token, fileName(path))) {
            is Result.Err -> return@withToken f
            is Result.Ok -> f.value ?: return@withToken Result.Err(CloudStorageException("File not found: ${fileName(path)}"))
        }
        val response = client.get("$apiBase/files/${file.id}") {
            header("Authorization", "Bearer $token")
            url { parameters.append("alt", "media") }
        }
        if (response.status.value !in 200..299) {
            return@withToken mapError(response.status.value, response.bodyAsText())
        }
        response.writeBodyToFile(destPath)
        // The version came from the name lookup above, which is the same read this download is
        // already based on — no extra request to learn what was just fetched.
        Result.Ok(CloudFileMeta(file.version))
    }

    override suspend fun upload(
        path: String,
        sourcePath: String,
        expectedRev: String?,
    ): Result<CloudFileMeta> = withToken { token ->
        val name = fileName(path)
        val existing = when (val f = findFile(token, name)) {
            is Result.Err -> return@withToken f
            is Result.Ok -> f.value
        }
        if (existing == null) {
            createFile(token, name, sourcePath).map { CloudFileMeta(it.version) }
        } else {
            // Best-effort optimistic concurrency: bail if the remote changed since download().
            if (expectedRev != null && existing.version != expectedRev) {
                return@withToken Result.Err(SyncConflictException())
            }
            updateFile(token, existing.id, sourcePath)
        }
    }

    /**
     * Creates a new file without overwriting an existing file.
     *
     * @param path The sync path identifying the file.
     * @param data The file contents.
     * @return `Ok` when the file is created successfully, or an error if the file already exists or creation fails.
     */
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = withToken { token ->
        val name = fileName(path)
        // Best-effort create-only: Drive has no atomic create-if-absent, so we check first and
        // fail with a conflict when the file already exists rather than overwriting it. The small
        // check-then-write window is reconciled by the sync retry loop, as with upload().
        val existing = when (val f = findFile(token, name)) {
            is Result.Err -> return@withToken f
            is Result.Ok -> f.value
        }
        if (existing != null) {
            Result.Err(SyncConflictException())
        } else {
            when (val created = createFile(token, name, sourcePath)) {
                is Result.Err -> created
                is Result.Ok -> resolveCreateRace(token, name, created.value)
            }
        }
    }

    /**
     * Reconciles duplicate files created concurrently by retaining the file with the lowest ID.
     *
     * A listing that shows only the file we just created is not proof no one else is racing —
     * Drive's list index is not guaranteed to already reflect a just-completed create from a
     * concurrently racing device — so a solitary result is re-checked once more before being
     * trusted, shrinking (not fully closing) the false-winner window.
     *
     * @param token The Google Drive access token.
     * @param name The file name shared by the concurrent creations.
     * @param created The file created by the current operation, as returned by its own write.
     * @return `Result.Ok` if the current file is retained and duplicates are deleted; a conflict error if another file wins.
     */
    private suspend fun resolveCreateRace(token: String, name: String, created: DriveFile): Result<CloudFileMeta> {
        var rechecked = false
        while (true) {
            val listResult = listFilesByName(token, name)
            if (listResult is Result.Err) return listResult
            val files = (listResult as Result.Ok).value
            val winner = files.minByOrNull { it.id }
            if (winner == null) {
                return Result.Err(CloudStorageException("Created file disappeared immediately"))
            }
            if (winner.id != created.id) {
                // Lost the race — delete the file we just created so it does not linger as an orphan
                // (the winner may have listed before ours became visible and so never sees it to clean
                // up). Best-effort: a failed cleanup must not mask the conflict signal, and a
                // double-delete with the winner is safe (404 == Ok). The sync flow will then download
                // the winner's file and retry.
                deleteById(token, created.id)
                return Result.Err(SyncConflictException())
            }
            if (files.size == 1 && !rechecked) {
                rechecked = true
                delay(RACE_RECHECK_DELAY_MS)
                continue
            }
            // We are the winner — safely delete every duplicate.
            for (file in files) {
                if (file.id != winner.id) {
                    when (val del = deleteById(token, file.id)) {
                        is Result.Err -> return del
                        is Result.Ok -> Unit
                    }
                }
            }
            // We won, so the retained file is the one we created — report the version its *own*
            // write returned, not the one this listing just read. The listing happens up to
            // RACE_RECHECK_DELAY_MS after that write, and a racing device's upload() landing in
            // between bumps our file's version; returning that would tell SyncRepository we had
            // merged a revision we never saw, and the next sync would skip downloading it.
            // Reporting a *stale* version is safe by comparison: it only costs one extra
            // download+merge next cycle.
            return Result.Ok(CloudFileMeta(created.version))
        }
    }

    /**
     * Deletes all matching files for the specified path.
     *
     * @param path The sync path identifying the files to delete.
     * @return `Result.Ok` when all matching files are deleted or none exist; otherwise, the first deletion or lookup error.
     */
    override suspend fun delete(path: String): Result<Unit> = withToken { token ->
        val name = fileName(path)
        val listResult = listFilesByName(token, name)
        if (listResult is Result.Err) return@withToken listResult
        val files = (listResult as Result.Ok).value
        if (files.isEmpty()) return@withToken Result.Ok(Unit)
        for (file in files) {
            when (val del = deleteById(token, file.id)) {
                is Result.Err -> return@withToken del
                is Result.Ok -> Unit
            }
        }
        Result.Ok(Unit)
    }

    /** Deletes a file by its Drive ID. 404 is treated as success (idempotent). */
    private suspend fun deleteById(token: String, fileId: String): Result<Unit> {
        val response = client.delete("$apiBase/files/$fileId") {
            header("Authorization", "Bearer $token")
        }
        return when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value == 404 -> Result.Ok(Unit)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Renames every app-data file matching [from]'s basename to [to]'s basename.
     *
     * Drive is name-addressed and tolerates duplicates, so — as with [delete] — all matches are
     * handled: the deterministic winner (lowest id, see [findFile]) is renamed and the rest are
     * deleted, leaving no leftover under the old name that a subsequent [create] would collide
     * with.
     *
     * @param from The sync path whose basename identifies the file(s) to rename.
     * @param to The sync path whose basename becomes the new name.
     * @return `Result.Ok` when the rename (and any duplicate cleanup) succeeds or no file matched;
     * otherwise, the first lookup, rename, or cleanup error.
     */
    override suspend fun rename(from: String, to: String): Result<Unit> = withToken { token ->
        val files = when (val list = listFilesByName(token, fileName(from))) {
            is Result.Err -> return@withToken list
            is Result.Ok -> list.value
        }
        val winner = files.minByOrNull { it.id } ?: return@withToken Result.Ok(Unit) // absent = done
        when (val renamed = renameById(token, winner.id, fileName(to))) {
            is Result.Err -> return@withToken renamed
            is Result.Ok -> Unit
        }
        for (file in files) {
            if (file.id == winner.id) continue
            when (val del = deleteById(token, file.id)) {
                is Result.Err -> return@withToken del
                is Result.Ok -> Unit
            }
        }
        Result.Ok(Unit)
    }

    /** Renames a file by its Drive ID via a metadata PATCH. 404 is treated as success (idempotent). */
    private suspend fun renameById(token: String, fileId: String, name: String): Result<Unit> {
        // The *metadata* endpoint ($apiBase), not $uploadBase: updateFile()'s uploadType=media PATCH
        // replaces content, this one only changes the name.
        val response = client.patch("$apiBase/files/$fileId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) }.toString())
        }
        return when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value == 404 -> Result.Ok(Unit)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun metadata(path: String): Result<CloudFileMeta?> = withToken { token ->
        when (val f = findFile(token, fileName(path))) {
            is Result.Err -> f
            // `version` is already fetched by the same name lookup this call has always made (it
            // is what upload() compares as the rev), so returning it costs no extra request.
            is Result.Ok -> Result.Ok(f.value?.let { CloudFileMeta(it.version) })
        }
    }

    /** Lists all app-data files matching [name] (there should be at most one). */
    private suspend fun listFilesByName(token: String, name: String): Result<List<DriveFile>> {
        val response = client.get("$apiBase/files") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("q", "name = '${name.replace("'", "\\'")}' and trashed = false")
                parameters.append("fields", "files(id,version)")
            }
        }
        if (response.status.value !in 200..299) {
            return mapError(response.status.value, response.bodyAsText())
        }
        val files = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
            ?.get("files")?.jsonArray
            ?: return Result.Err(CloudStorageException("Missing files array in response"))
        val result = files.map { fileObj ->
            val obj = fileObj.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content
                ?: return Result.Err(CloudStorageException("File metadata missing id"))
            val version = obj["version"]?.jsonPrimitive?.content
                ?: return Result.Err(CloudStorageException("File metadata missing version"))
            DriveFile(id, version)
        }
        return Result.Ok(result)
    }

    /** Looks up the single app-data file by name; returns null when absent.
     *  When transient duplicates exist (e.g. from a racing create), the
     *  deterministic winner (lowest id) is returned so all callers converge. */
    private suspend fun findFile(token: String, name: String): Result<DriveFile?> {
        return when (val listResult = listFilesByName(token, name)) {
            is Result.Err -> listResult
            is Result.Ok -> Result.Ok(listResult.value.minByOrNull { it.id })
        }
    }

    /** Creates the file in appDataFolder via a multipart/related upload (metadata + media). Returns the created file's id and version. */
    private suspend fun createFile(token: String, name: String, sourcePath: String): Result<DriveFile> {
        val boundary = "keryx-${Random.nextLong().toULong()}"
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add(APP_DATA_FOLDER) }
        }.toString()
        val opening = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.encodeToByteArray()
        val closing = "\r\n--$boundary--\r\n".encodeToByteArray()

        val response = client.post("$uploadBase/files") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("uploadType", "multipart")
                parameters.append("fields", "id,version")
            }
            // The envelope is streamed around the file rather than concatenated with it: building
            // `opening + data + closing` would hold the whole database on the heap twice over.
            setBody(
                FileUploadContent(
                    sourcePath = sourcePath,
                    contentType = ContentType("multipart", "related").withParameter("boundary", boundary),
                    prefix = opening,
                    suffix = closing,
                )
            )
        }
        if (response.status.value !in 200..299) {
            return mapError(response.status.value, response.bodyAsText())
        }
        return parseDriveFile(response.bodyAsText())
    }

    /** Overwrites the file's content with a simple media upload. Returns the resulting version. */
    private suspend fun updateFile(token: String, fileId: String, sourcePath: String): Result<CloudFileMeta> {
        val response = client.patch("$uploadBase/files/$fileId") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("uploadType", "media")
                parameters.append("fields", "id,version")
            }
            setBody(FileUploadContent(sourcePath))
        }
        if (response.status.value !in 200..299) {
            return mapError(response.status.value, response.bodyAsText())
        }
        // `fields=id,version` is already requested, so the write's own response carries the new
        // version — no follow-up lookup, which could otherwise pick up a racing device's write.
        return parseDriveFile(response.bodyAsText()).map { CloudFileMeta(it.version) }
    }

    /**
     * Parses a Drive file resource fetched with `fields=id,version`.
     *
     * @param body The JSON response body.
     * @return The file's id and version, or an error when either field is absent.
     */
    private fun parseDriveFile(body: String): Result<DriveFile> {
        val obj = json.parseToJsonElement(body) as? JsonObject
            ?: return Result.Err(CloudStorageException("Malformed file metadata response"))
        val id = obj["id"]?.jsonPrimitive?.content
            ?: return Result.Err(CloudStorageException("File metadata missing id"))
        val version = obj["version"]?.jsonPrimitive?.content
            ?: return Result.Err(CloudStorageException("File metadata missing version"))
        return Result.Ok(DriveFile(id, version))
    }

    /**
     * Converts an HTTP response into a successful or mapped error result.
     *
     * @param response The HTTP response to evaluate.
     * @return `Result.Ok` for a successful status, or a mapped error result otherwise.
     */
    private suspend fun okOrError(response: HttpResponse): Result<Unit> = when {
        response.status.value in 200..299 -> Result.Ok(Unit)
        else -> mapError(response.status.value, response.bodyAsText())
    }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        withCloudToken(accessTokenProvider, "Google Drive", block)

    /**
     * Maps a Google Drive HTTP response to a cloud storage error.
     *
     * @param status The HTTP response status code.
     * @param body The response body.
     * @return The corresponding cloud storage error result.
     */
    private fun mapError(status: Int, body: String): Result.Err = cloudStorageError("Google Drive", status, body)

    /** Drive files are named, not path-addressed: use the basename of the sync path. */
    private fun fileName(path: String): String = path.substringAfterLast('/')

    private class DriveFile(val id: String, val version: String)

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"

        /** Grace period before trusting a post-create listing that shows no duplicates — Drive's
         *  list index is not guaranteed to already reflect a just-completed create from a
         *  concurrently racing device. */
        const val RACE_RECHECK_DELAY_MS = 1_000L
    }
}

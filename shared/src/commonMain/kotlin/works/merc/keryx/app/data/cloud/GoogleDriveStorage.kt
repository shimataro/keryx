package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.core.map
import works.merc.keryx.app.core.valueOrElse

private const val PROVIDER_NAME = "Google Drive"

/**
 * [CloudStorage] backed by the Google Drive API v3, storing the sync DB in the
 * hidden `appDataFolder` special space (scope `drive.appdata`) — the closest
 * analog to Dropbox's dedicated app folder, invisible in the user's regular Drive.
 *
 * [accessTokenProvider] returns a currently-valid access token (refreshing it if
 * needed); a null result means the user is not connected.
 *
 * Optimistic concurrency uses the opaque `rev` `<file id>:<version>`. `version` is a
 * monotonically increasing counter, but only per file: two files can carry the same one,
 * so the id goes into the `rev` as well. Racing first uploads can leave two files of the
 * same name (see [resolveCreateRace]), and a device whose lookup later moves from its own
 * file to the winner's must see a different `rev` — otherwise a matching `version` would
 * make it skip downloading the winner's file and let its next upload overwrite that file
 * unmerged. A `rev` recorded by an older build (the `version` alone) matches nothing, which
 * costs one extra download and merge, or one conflict retry, and nothing else.
 *
 * Drive has no native compare-and-set for content updates, so an [upload] with a
 * non-null `expectedRev` re-reads the current `rev` and fails with
 * [SyncConflictException] if it changed since the caller's [download]. An overwrite
 * landing between that check and the write is not detected; the overwritten device
 * merges its changes into the cloud file again on its next sync, and those that survive the
 * ordinary merge rules return to the cloud. The overwrite itself loses them from the cloud
 * only if that device never syncs again. Closing the window
 * needs a server-side precondition Drive does not offer — see "Google Drive: an upload can
 * overwrite another device's upload unnoticed" in docs/known-issues.md.
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
            bearerAuth(token)
            url {
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("pageSize", "1")
                parameters.append("fields", "files(id)")
            }
        }
        response.okOr(PROVIDER_NAME)
    }

    /**
     * Streams the file identified by the basename of the specified path to disk.
     *
     * @param path The path whose basename identifies the file.
     * @param destPath The local file to write the contents to.
     * @return The downloaded file's Google Drive revision.
     */
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = withToken { token ->
        val file = findFile(token, cloudFileName(path)).valueOrElse { return@withToken it }
            ?: return@withToken Result.Err(CloudStorageException("File not found: ${cloudFileName(path)}"))
        val response = client.get("$apiBase/files/${file.id}") {
            bearerAuth(token)
            url { parameters.append("alt", "media") }
        }
        if (!response.status.isSuccess()) {
            return@withToken mapError(response.status.value, response.bodyAsText())
        }
        response.writeBodyToFile(destPath, MAX_SYNC_DB_SIZE_BYTES)
        // The rev came from the name lookup above, which is the same read this download is
        // already based on — no extra request to learn what was just fetched.
        Result.Ok(CloudFileMeta(file.rev))
    }

    override suspend fun upload(
        path: String,
        sourcePath: String,
        expectedRev: String?,
    ): Result<CloudFileMeta> = withToken { token ->
        val name = cloudFileName(path)
        val existing = findFile(token, name).valueOrElse { return@withToken it }
        if (existing == null) {
            createFile(token, name, sourcePath).map { CloudFileMeta(it.rev) }
        } else {
            // Client-side optimistic concurrency: bail if the remote changed since download(). Drive
            // cannot make this check part of the write — see the class KDoc for the window it leaves.
            if (expectedRev != null && existing.rev != expectedRev) {
                return@withToken Result.Err(SyncConflictException())
            }
            updateFile(token, existing.id, sourcePath)
        }
    }

    /**
     * Creates a new file without overwriting an existing file.
     *
     * @param path The sync path identifying the file.
     * @param sourcePath The path to the file to upload.
     * @return `Ok` when the file is created successfully, or an error if the file already exists or creation fails.
     */
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = withToken { token ->
        val name = cloudFileName(path)
        // Drive has no atomic create-if-absent, so check first and fail with a conflict when the
        // file already exists rather than overwriting it. Two creates racing past this check each
        // produce a file of their own; resolveCreateRace reconciles them afterwards, but only as far
        // as Drive's listing shows the other file (see its KDoc and docs/known-issues.md).
        val existing = findFile(token, name).valueOrElse { return@withToken it }
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
     * trusted. That narrows the false-winner window but cannot close it: with no atomic
     * create-if-absent and no consistent listing, Drive offers nothing to close it with. A
     * device that wrongly believes it won keeps writing to its own file until the listing
     * catches up and [findFile]'s lowest-id rule moves it to the winner's. That move changes the
     * `rev` it sees (the id is part of it), so its next sync downloads and merges the winner's
     * file before uploading; its own file is left behind unused — see "Google Drive: an upload
     * can overwrite another device's upload unnoticed" in docs/known-issues.md.
     *
     * @param token The Google Drive access token.
     * @param name The file name shared by the concurrent creations.
     * @param created The file created by the current operation, as returned by its own write.
     * @return `Result.Ok` if the current file is retained and duplicates are deleted; a conflict error if another file wins.
     */
    private suspend fun resolveCreateRace(token: String, name: String, created: DriveFile): Result<CloudFileMeta> {
        var rechecked = false
        while (true) {
            val files = listFilesByName(token, name).valueOrElse { return it }
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
            deleteAll(token, files, keepId = winner.id).valueOrElse { return it }
            // We won, so the retained file is the one we created — report the rev its *own*
            // write returned, not the one this listing just read. The listing happens up to
            // RACE_RECHECK_DELAY_MS after that write, and a racing device's upload() landing in
            // between bumps our file's version; returning that would tell SyncRepository we had
            // merged a revision we never saw, and the next sync would skip downloading it.
            // Reporting a *stale* version is safe by comparison: it only costs one extra
            // download+merge next cycle.
            return Result.Ok(CloudFileMeta(created.rev))
        }
    }

    /**
     * Deletes all matching files for the specified path.
     *
     * @param path The sync path identifying the files to delete.
     * @return `Result.Ok` when all matching files are deleted or none exist; otherwise, the first deletion or lookup error.
     */
    override suspend fun delete(path: String): Result<Unit> = withToken { token ->
        val files = listFilesByName(token, cloudFileName(path)).valueOrElse { return@withToken it }
        deleteAll(token, files)
    }

    /**
     * Deletes every file in [files] except the one whose id is [keepId], in list order.
     *
     * @return `Result.Ok` once all are deleted (or there was nothing to delete); otherwise, the first
     * deletion error, leaving the remaining files untouched.
     */
    private suspend fun deleteAll(token: String, files: List<DriveFile>, keepId: String? = null): Result<Unit> {
        for (file in files) {
            if (file.id == keepId) continue
            deleteById(token, file.id).valueOrElse { return it }
        }
        return Result.Ok(Unit)
    }

    /** Deletes a file by its Drive ID. 404 is treated as success (idempotent). */
    private suspend fun deleteById(token: String, fileId: String): Result<Unit> {
        val response = client.delete("$apiBase/files/$fileId") {
            bearerAuth(token)
        }
        return response.okOrAbsentOr(PROVIDER_NAME)
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
        val files = listFilesByName(token, cloudFileName(from)).valueOrElse { return@withToken it }
        val winner = files.minByOrNull { it.id } ?: return@withToken Result.Ok(Unit) // absent = done
        renameById(token, winner.id, cloudFileName(to)).valueOrElse { return@withToken it }
        deleteAll(token, files, keepId = winner.id)
    }

    /** Renames a file by its Drive ID via a metadata PATCH. 404 is treated as success (idempotent). */
    private suspend fun renameById(token: String, fileId: String, name: String): Result<Unit> {
        // The *metadata* endpoint ($apiBase), not $uploadBase: updateFile()'s uploadType=media PATCH
        // replaces content, this one only changes the name.
        val response = client.patch("$apiBase/files/$fileId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) }.toString())
        }
        return response.okOrAbsentOr(PROVIDER_NAME)
    }

    override suspend fun metadata(path: String): Result<CloudFileMeta?> = withToken { token ->
        // `id` and `version` are already fetched by the same name lookup this call has always
        // made (they are what upload() compares as the rev), so returning it costs no extra request.
        findFile(token, cloudFileName(path)).map { file -> file?.let { CloudFileMeta(it.rev) } }
    }

    /**
     * Lists the app-data files whose name starts with [prefix] through `files.list`, following
     * `nextPageToken` until the last page; a token already used is a storage error rather than a loop.
     *
     * The prefix is applied here rather than in the query: Drive's `contains` operator matches
     * word prefixes, not string prefixes, so it would both miss and over-match sync file names.
     * `appDataFolder` holds no folders this app creates, but a folder is skipped all the same.
     *
     * @param prefix The basename prefix to keep.
     * @return The matching files (possibly with a name listed twice — see [CloudStorage.list]), or a mapped storage error.
     */
    override suspend fun list(prefix: String): Result<List<CloudFileEntry>> = withToken { token ->
        val entries = mutableListOf<CloudFileEntry>()
        var pageToken: String? = null
        // A token already used would answer the same page again, and the loop would never end.
        val seenTokens = mutableSetOf<String>()
        do {
            val response = client.get("$apiBase/files") {
                bearerAuth(token)
                url {
                    parameters.append("spaces", APP_DATA_FOLDER)
                    parameters.append("q", "trashed = false")
                    parameters.append("fields", "nextPageToken,files(id,name,version,size,mimeType)")
                    parameters.append("pageSize", LIST_PAGE_SIZE.toString())
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }
            if (!response.status.isSuccess()) {
                return@withToken mapError(response.status.value, response.bodyAsText())
            }
            val page = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: return@withToken Result.Err(CloudStorageException("Malformed files.list response"))
            val files = page["files"] as? JsonArray
                ?: return@withToken Result.Err(CloudStorageException("Missing files array in response"))
            for (element in files) {
                val obj = element as? JsonObject ?: continue
                if (obj["mimeType"]?.jsonPrimitive?.content == FOLDER_MIME_TYPE) continue
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                if (!name.startsWith(prefix)) continue
                val file = parseDriveFile(obj).valueOrElse { return@withToken it }
                val size = obj["size"]?.jsonPrimitive?.longOrNull
                    ?: return@withToken Result.Err(CloudStorageException("File metadata missing size"))
                entries += CloudFileEntry(name, file.rev, size)
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            if (pageToken != null && !seenTokens.add(pageToken)) {
                return@withToken Result.Err(CloudStorageException("Repeated nextPageToken in files.list response"))
            }
        } while (pageToken != null)
        Result.Ok(entries)
    }

    /** Lists all app-data files matching [name] (there should be at most one). */
    private suspend fun listFilesByName(token: String, name: String): Result<List<DriveFile>> {
        val response = client.get("$apiBase/files") {
            bearerAuth(token)
            url {
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("q", "name = '${name.replace("'", "\\'")}' and trashed = false")
                parameters.append("fields", "files(id,version)")
            }
        }
        if (!response.status.isSuccess()) {
            return mapError(response.status.value, response.bodyAsText())
        }
        val files = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
            ?.get("files")?.jsonArray
            ?: return Result.Err(CloudStorageException("Missing files array in response"))
        val result = files.map { fileObj ->
            parseDriveFile(fileObj.jsonObject).valueOrElse { return it }
        }
        return Result.Ok(result)
    }

    /** Looks up the single app-data file by name; returns null when absent.
     *  When transient duplicates exist (e.g. from a racing create), the
     *  deterministic winner (lowest id) is returned so all callers converge. */
    private suspend fun findFile(token: String, name: String): Result<DriveFile?> =
        listFilesByName(token, name).map { files -> files.minByOrNull { it.id } }

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
            bearerAuth(token)
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
        if (!response.status.isSuccess()) {
            return mapError(response.status.value, response.bodyAsText())
        }
        return parseDriveFile(response.bodyAsText())
    }

    /** Overwrites the file's content with a simple media upload. Returns the resulting rev. */
    private suspend fun updateFile(token: String, fileId: String, sourcePath: String): Result<CloudFileMeta> {
        val response = client.patch("$uploadBase/files/$fileId") {
            bearerAuth(token)
            url {
                parameters.append("uploadType", "media")
                parameters.append("fields", "id,version")
            }
            setBody(FileUploadContent(sourcePath))
        }
        if (!response.status.isSuccess()) {
            return mapError(response.status.value, response.bodyAsText())
        }
        // `fields=id,version` is already requested, so the write's own response carries the new
        // version — no follow-up lookup, which could otherwise pick up a racing device's write.
        return parseDriveFile(response.bodyAsText()).map { CloudFileMeta(it.rev) }
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
        return parseDriveFile(obj)
    }

    /**
     * Reads a Drive file resource's `id` and `version` fields.
     *
     * @param obj The file resource object.
     * @return The file's id and version, or an error when either field is absent.
     */
    private fun parseDriveFile(obj: JsonObject): Result<DriveFile> {
        val id = obj["id"]?.jsonPrimitive?.content
            ?: return Result.Err(CloudStorageException("File metadata missing id"))
        val version = obj["version"]?.jsonPrimitive?.content
            ?: return Result.Err(CloudStorageException("File metadata missing version"))
        return Result.Ok(DriveFile(id, version))
    }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        withCloudToken(accessTokenProvider, PROVIDER_NAME, block)

    /**
     * Maps a Google Drive HTTP response to a cloud storage error.
     *
     * @param status The HTTP response status code.
     * @param body The response body.
     * @return The corresponding cloud storage error result.
     */
    private fun mapError(status: Int, body: String): Result.Err = cloudStorageError(PROVIDER_NAME, status, body)

    private class DriveFile(val id: String, val version: String) {
        /** The `rev` this storage reports and compares; see the class KDoc for why it holds the id. */
        val rev: String get() = "$id:$version"
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"

        /** `files.list`'s largest page size, so a listing takes as few round trips as possible. */
        const val LIST_PAGE_SIZE = 1000

        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        /** Grace period before trusting a post-create listing that shows no duplicates — Drive's
         *  list index is not guaranteed to already reflect a just-completed create from a
         *  concurrently racing device. */
        const val RACE_RECHECK_DELAY_MS = 1_000L
    }
}

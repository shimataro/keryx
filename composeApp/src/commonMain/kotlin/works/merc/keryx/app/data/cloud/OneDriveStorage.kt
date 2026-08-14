package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.ONEDRIVE_GRAPH_BASE
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException

/**
 * [CloudStorage] backed by the Microsoft Graph API, storing the sync DB in
 * OneDrive's hidden app folder (`/me/drive/special/approot`, scope
 * `Files.ReadWrite.AppFolder`) — the analog of Dropbox's dedicated app folder /
 * Google Drive's `appDataFolder`, invisible in the user's regular OneDrive.
 *
 * [accessTokenProvider] returns a currently-valid access token (refreshing it if
 * needed); a null result means the user is not connected.
 *
 * Optimistic concurrency uses the DriveItem's `eTag` as the opaque `rev`, sent
 * back via `If-Match` on [upload]; Graph returns 412 (precondition failed) when it
 * no longer matches (another device wrote first). [create] uses the native
 * `@microsoft.graph.conflictBehavior=fail` so an existing file yields 409 instead
 * of being overwritten.
 */
class OneDriveStorage(
    private val client: HttpClient,
    private val accessTokenProvider: suspend () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudStorage {

    /**
     * Verifies access to the OneDrive app folder.
     *
     * @return A successful result when access is authorized or the app folder has not been provisioned; otherwise, a mapped storage error.
     */
    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.get(appRootUrl) { header("Authorization", "Bearer $token") }
        when (response.status.value) {
            in 200..299 -> Result.Ok(Unit)
            // The token is valid but the app folder has not been provisioned yet — still connected.
            404 -> Result.Ok(Unit)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = withToken { token ->
        val meta = client.get(itemUrl(path)) { header("Authorization", "Bearer $token") }
        if (meta.status.value == 404) {
            return@withToken Result.Err(CloudStorageException("File not found: ${fileName(path)}"))
        }
        if (meta.status.value !in 200..299) {
            return@withToken mapError(meta.status.value, meta.bodyAsText())
        }
        val obj = json.parseToJsonElement(meta.bodyAsText()) as? JsonObject
            ?: return@withToken Result.Err(CloudStorageException("Malformed metadata response"))
        val eTag = obj["eTag"]?.jsonPrimitive?.content
            ?: return@withToken Result.Err(CloudStorageException("Missing eTag in metadata"))
        val downloadUrl = obj["@microsoft.graph.downloadUrl"]?.jsonPrimitive?.content
            ?: return@withToken Result.Err(CloudStorageException("Missing download URL in metadata"))

        // The download URL is pre-authenticated — do not attach the bearer token. The shared
        // client has followRedirects=false, so follow a single redirect (e.g. to a CDN) manually.
        var content = client.get(downloadUrl)
        if (content.status.value in 300..399) {
            val location = content.headers["Location"]
                ?: return@withToken Result.Err(CloudStorageException("Redirect without Location header"))
            content = client.get(location)
        }
        if (content.status.value !in 200..299) {
            return@withToken mapError(content.status.value, content.bodyAsText())
        }
        content.writeBodyToFile(destPath)
        // The eTag came from the item metadata fetched above, which is the read this download is
        // based on — no extra request to learn what was just fetched.
        Result.Ok(CloudFileMeta(eTag))
    }

    /**
     * Uploads file content to OneDrive, optionally requiring a matching revision.
     *
     * @param path The path of the file to upload.
     * @param data The file content.
     * @param expectedRev The required current revision, or `null` to upload without revision checking.
     * @return A result indicating whether the upload succeeded or encountered a revision conflict.
     */
    override suspend fun upload(
        path: String,
        sourcePath: String,
        expectedRev: String?,
    ): Result<CloudFileMeta> = withToken { token ->
        val response = client.put("${itemUrl(path)}:/content") {
            header("Authorization", "Bearer $token")
            if (expectedRev != null) header("If-Match", expectedRev)
            setBody(FileUploadContent(sourcePath))
        }
        // If-Match no longer matches — another device wrote first.
        response.metaOrConflictOr(conflictStatus = 412)
    }

    /**
     * Creates a file at the specified path without overwriting an existing file.
     *
     * @param path The file path within the OneDrive app folder.
     * @param data The file content.
     * @return A successful result when the file is created, or a conflict result when a file already exists.
     */
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = withToken { token ->
        // conflictBehavior=fail is Graph's native create-only: an existing file yields 409
        // instead of being overwritten, which we surface as a conflict so the caller falls back
        // to the merge path. encodedParameters keeps the "@"/"." literal (already URL-safe).
        val response = client.put("${itemUrl(path)}:/content") {
            header("Authorization", "Bearer $token")
            url { encodedParameters.append("@microsoft.graph.conflictBehavior", "fail") }
            setBody(FileUploadContent(sourcePath))
        }
        response.metaOrConflictOr(conflictStatus = 409)
    }

    /**
     * Maps a content-write response to the revision it produced, a sync conflict, or a storage error.
     *
     * A successful content PUT answers with the resulting DriveItem, so the new `eTag` comes from
     * the write itself rather than a follow-up item GET, which could otherwise observe another
     * device's later write.
     *
     * @param conflictStatus The HTTP status Graph uses to report the lost write (412 for `If-Match`, 409 for create-only).
     * @return The written revision, a conflict, or a mapped storage error.
     */
    private suspend fun HttpResponse.metaOrConflictOr(conflictStatus: Int): Result<CloudFileMeta> = when {
        status.value in 200..299 -> {
            val eTag = (json.parseToJsonElement(bodyAsText()) as? JsonObject)
                ?.get("eTag")?.jsonPrimitive?.content
                ?: return Result.Err(CloudStorageException("Missing eTag in upload response"))
            Result.Ok(CloudFileMeta(eTag))
        }
        status.value == conflictStatus -> Result.Err(SyncConflictException())
        else -> mapError(status.value, bodyAsText())
    }

    /**
     * Deletes the file at the specified path.
     *
     * Treats an already absent file as a successful deletion.
     *
     * @param path The path of the file to delete.
     * @return A successful result when the file is deleted or already absent; otherwise, the mapped storage error.
     */
    override suspend fun delete(path: String): Result<Unit> = withToken { token ->
        val response = client.delete(itemUrl(path)) { header("Authorization", "Bearer $token") }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            // Already absent — delete is idempotent.
            response.status.value == 404 -> Result.Ok(Unit)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Renames the app-folder item at [from] to [to]'s basename via a Graph metadata PATCH.
     *
     * Treats an already-absent item as success; an occupied destination surfaces as 409
     * (`nameAlreadyExists`) — Graph's `@microsoft.graph.conflictBehavior` is a content-upload
     * parameter and does not apply to a metadata PATCH, so the default no-overwrite behavior
     * stands.
     *
     * @param from The current path of the item within the app folder.
     * @param to The path whose basename becomes the item's new name.
     * @return A successful result when the item is renamed or already absent; otherwise, the
     * mapped storage error (including an occupied destination).
     */
    override suspend fun rename(from: String, to: String): Result<Unit> = withToken { token ->
        val response = client.patch(itemUrl(from)) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", fileName(to)) }.toString())
        }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value == 404 -> Result.Ok(Unit) // already absent — rename is idempotent
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Determines whether an item exists at the specified path.
     *
     * @param path The path of the item within the app folder.
     * @return `true` if the item exists, `false` if it is not found.
     */
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = withToken { token ->
        val response = client.get(itemUrl(path)) { header("Authorization", "Bearer $token") }
        when {
            response.status.value in 200..299 -> {
                // The DriveItem `eTag` is the rev this provider guards uploads with (`If-Match`).
                // It comes from the same item GET that used to be read only for its status code.
                val eTag = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
                    ?.get("eTag")?.jsonPrimitive?.content
                    ?: return@withToken Result.Err(CloudStorageException("Missing eTag in metadata"))
                Result.Ok(CloudFileMeta(eTag))
            }
            response.status.value == 404 -> Result.Ok(null)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Executes an operation with an available OneDrive access token.
     *
     * @param block The operation to execute with the access token.
     * @return The result produced by the operation.
     */
    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        withCloudToken(accessTokenProvider, "OneDrive", block)

    /**
     * Converts a OneDrive response error into a storage error result.
     *
     * @param status The HTTP response status code.
     * @param body The response body containing error details.
     * @return An error result describing the OneDrive failure.
     */
    private fun mapError(status: Int, body: String): Result.Err = cloudStorageError("OneDrive", status, body)

    /** Graph app-folder items are path-addressed by name: use the basename of the sync path. */
    private fun fileName(path: String): String = path.substringAfterLast('/')

    /** Metadata / delete URL for the app-folder item (append `:/content` for the content stream). */
    private fun itemUrl(path: String): String = "$appRootUrl:/${fileName(path)}"

    private val appRootUrl: String get() = "$ONEDRIVE_GRAPH_BASE/me/drive/special/approot"
}

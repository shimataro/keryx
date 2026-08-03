package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.ONEDRIVE_GRAPH_BASE
import works.merc.keryx.app.core.Result

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

    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.get(appRootUrl) { header("Authorization", "Bearer $token") }
        when (response.status.value) {
            in 200..299 -> Result.Ok(Unit)
            // The token is valid but the app folder has not been provisioned yet — still connected.
            404 -> Result.Ok(Unit)
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun download(path: String): Result<CloudFile> = withToken { token ->
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
        Result.Ok(CloudFile(content.readRawBytes(), eTag))
    }

    override suspend fun upload(
        path: String,
        data: ByteArray,
        expectedRev: String?,
    ): Result<Unit> = withToken { token ->
        val response = client.put("${itemUrl(path)}:/content") {
            header("Authorization", "Bearer $token")
            if (expectedRev != null) header("If-Match", expectedRev)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        // If-Match no longer matches — another device wrote first.
        response.okOrConflictOr("OneDrive", conflictStatus = 412)
    }

    override suspend fun create(path: String, data: ByteArray): Result<Unit> = withToken { token ->
        // conflictBehavior=fail is Graph's native create-only: an existing file yields 409
        // instead of being overwritten, which we surface as a conflict so the caller falls back
        // to the merge path. encodedParameters keeps the "@"/"." literal (already URL-safe).
        val response = client.put("${itemUrl(path)}:/content") {
            header("Authorization", "Bearer $token")
            url { encodedParameters.append("@microsoft.graph.conflictBehavior", "fail") }
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        response.okOrConflictOr("OneDrive", conflictStatus = 409)
    }

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
     * Determines whether an item exists at the specified path.
     *
     * @param path The path of the item within the app folder.
     * @return `true` if the item exists, `false` if it is not found.
     */
    override suspend fun exists(path: String): Result<Boolean> = withToken { token ->
        val response = client.get(itemUrl(path)) { header("Authorization", "Bearer $token") }
        when {
            response.status.value in 200..299 -> Result.Ok(true)
            response.status.value == 404 -> Result.Ok(false)
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

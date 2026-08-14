package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException

/**
 * [CloudStorage] backed by the Dropbox v2 REST API. [accessTokenProvider]
 * returns a currently-valid access token (refreshing it if needed); a null
 * result means the user is not connected.
 */
class DropboxStorage(
    private val client: HttpClient,
    private val accessTokenProvider: suspend () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudStorage {

    private val apiBase = "https://api.dropboxapi.com"
    private val contentBase = "https://content.dropboxapi.com"

    /**
     * Authenticates the configured Dropbox account.
     *
     * @return A successful result when authentication succeeds, or a mapped storage error otherwise.
     */
    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.post("$apiBase/2/users/get_current_account") {
            header("Authorization", "Bearer $token")
        }
        if (response.status.value in 200..299) Result.Ok(Unit) else mapError(response.status.value, response.bodyAsText())
    }

    /**
     * Downloads a file and its Dropbox revision metadata.
     *
     * @param path The path of the file to download.
     * @return A result containing the file data and revision, or a storage error.
     */
    override suspend fun download(path: String): Result<CloudFile> = withToken { token ->
        val response = client.post("$contentBase/2/files/download") {
            header("Authorization", "Bearer $token")
            header("Dropbox-API-Arg", buildJsonObject { put("path", path) }.toString())
        }
        if (response.status.value !in 200..299) {
            return@withToken mapError(response.status.value, response.bodyAsText())
        }
        val resultHeader = response.headers["dropbox-api-result"]
            ?: return@withToken Result.Err(CloudStorageException("Missing Dropbox-API-Result header"))
        val rev = (json.parseToJsonElement(resultHeader) as? JsonObject)
            ?.get("rev")?.jsonPrimitive?.content
            ?: return@withToken Result.Err(CloudStorageException("Missing rev in metadata"))
        Result.Ok(CloudFile(response.readRawBytes(), rev))
    }

    /**
     * Uploads file data to Dropbox, optionally requiring a specific revision.
     *
     * @param path The Dropbox path for the file.
     * @param data The file contents.
     * @param expectedRev The revision that must currently exist for the update to succeed, or `null` to overwrite.
     * @return A successful result, a conflict result when the expected revision is stale, or a mapped failure.
     */
    override suspend fun upload(
        path: String,
        data: ByteArray,
        expectedRev: String?,
    ): Result<CloudFileMeta> = withToken { token ->
        val mode = if (expectedRev != null) {
            buildJsonObject { put(".tag", "update"); put("update", expectedRev) }
        } else {
            buildJsonObject { put(".tag", "overwrite") }
        }
        val arg = buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("autorename", false)
        }.toString()

        val response = client.post("$contentBase/2/files/upload") {
            header("Authorization", "Bearer $token")
            header("Dropbox-API-Arg", arg)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        // A rev-guarded update that loses the race returns 409 (conflict).
        response.metaOrConflictOr(conflictStatus = 409)
    }

    /**
     * Creates a new file at the specified path without overwriting an existing file.
     *
     * @param path The Dropbox path where the file will be created.
     * @param data The file contents.
     * @return A successful result when the file is created, or an error result for failures including an existing file conflict.
     */
    override suspend fun create(path: String, data: ByteArray): Result<CloudFileMeta> = withToken { token ->
        // WriteMode "add" is create-only: if the file already exists Dropbox returns 409
        // (with autorename=false it does not silently create a copy), which we surface as a
        // conflict so the caller falls back to the merge path instead of overwriting.
        val arg = buildJsonObject {
            put("path", path)
            put("mode", buildJsonObject { put(".tag", "add") })
            put("autorename", false)
        }.toString()

        val response = client.post("$contentBase/2/files/upload") {
            header("Authorization", "Bearer $token")
            header("Dropbox-API-Arg", arg)
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        response.metaOrConflictOr(conflictStatus = 409)
    }

    /**
     * Maps a write response to the revision it produced, a sync conflict, or a storage error.
     *
     * A successful `files/upload` answers with the new FileMetadata, so the resulting `rev` is
     * read straight from the write itself — no follow-up metadata request, which could otherwise
     * observe another device's later write.
     *
     * @param conflictStatus The HTTP status Dropbox uses to report a lost rev guard.
     * @return The written revision, a conflict, or a mapped storage error.
     */
    private suspend fun HttpResponse.metaOrConflictOr(conflictStatus: Int): Result<CloudFileMeta> = when {
        status.value in 200..299 -> {
            val rev = (json.parseToJsonElement(bodyAsText()) as? JsonObject)
                ?.get("rev")?.jsonPrimitive?.content
                ?: return Result.Err(CloudStorageException("Missing rev in upload response"))
            Result.Ok(CloudFileMeta(rev))
        }
        status.value == conflictStatus -> Result.Err(SyncConflictException())
        else -> mapError(status.value, bodyAsText())
    }

    /**
     * Fetches a file's revision without its contents.
     *
     * @param path The Dropbox path to check.
     * @return The file's metadata, `null` if the path is not found, or an error result for other failures.
     */
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = withToken { token ->
        val response = client.post("$apiBase/2/files/get_metadata") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("path", path) }.toString())
        }
        when {
            response.status.value in 200..299 -> {
                // `rev` is what an update-mode upload is guarded on, and what the caller compares
                // against the last revision it merged. This is the same response that used to be
                // read only for its status code.
                val rev = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
                    ?.get("rev")?.jsonPrimitive?.content
                    ?: return@withToken Result.Err(CloudStorageException("Missing rev in metadata"))
                Result.Ok(CloudFileMeta(rev))
            }
            response.status.value == 409 -> {
                val body = response.bodyAsText()
                if (body.contains("path/not_found")) Result.Ok(null) else mapError(409, body)
            }
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Deletes a file or folder, treating an already-absent path as a successful deletion.
     *
     * @param path The Dropbox path to delete.
     * @return A result indicating whether the deletion succeeded.
     */
    override suspend fun delete(path: String): Result<Unit> = withToken { token ->
        val response = client.post("$apiBase/2/files/delete_v2") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("path", path) }.toString())
        }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            // delete_v2 reports an already-absent path as 409 with a "not_found" error summary.
            // Treat that as success so delete is idempotent.
            response.status.value == 409 -> {
                val body = response.bodyAsText()
                if (body.contains("not_found")) Result.Ok(Unit) else mapError(409, body)
            }
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Renames (moves) a file, without overwriting an existing destination.
     *
     * @param from The current Dropbox path.
     * @param to The destination Dropbox path.
     * @return A successful result when the file is moved or the source is already absent;
     * otherwise, a mapped storage error (including an occupied destination).
     */
    override suspend fun rename(from: String, to: String): Result<Unit> = withToken { token ->
        // move_v2 with autorename=false is a plain rename: it never silently creates "(1)"
        // copies, so an occupied destination surfaces as a conflict instead of being papered over.
        val response = client.post("$apiBase/2/files/move_v2") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("from_path", from)
                    put("to_path", to)
                    put("autorename", false)
                }.toString(),
            )
        }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value == 409 -> {
                val body = response.bodyAsText()
                // "from_lookup/not_found" is the source being absent — idempotent success. Matching
                // the tagged path (rather than a bare "not_found") keeps a "to/conflict" or
                // "to/malformed_path" error from being swallowed as success.
                if (body.contains("from_lookup/not_found")) Result.Ok(Unit) else mapError(409, body)
            }
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Executes an operation with a valid Dropbox access token.
     *
     * @param block The operation to execute with the access token.
     * @return The result produced by the operation.
     */
    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        withCloudToken(accessTokenProvider, "Dropbox", block)

    /**
     * Converts a Dropbox API failure response into a storage error result.
     *
     * @param status The HTTP status code returned by Dropbox.
     * @param body The response body containing error details.
     * @return An error result representing the Dropbox failure.
     */
    private fun mapError(status: Int, body: String): Result.Err = cloudStorageError("Dropbox", status, body)
}

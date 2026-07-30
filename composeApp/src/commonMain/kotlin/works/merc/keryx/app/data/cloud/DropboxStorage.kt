package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.merc.keryx.app.core.CloudAuthException
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

    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.post("$apiBase/2/users/get_current_account") {
            header("Authorization", "Bearer $token")
        }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value in setOf(401, 403) ->
                Result.Err(CloudAuthException("Authentication failed"))
            else -> Result.Err(CloudStorageException("HTTP ${response.status.value}"))
        }
    }

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

    override suspend fun upload(
        path: String,
        data: ByteArray,
        expectedRev: String?,
    ): Result<Unit> = withToken { token ->
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
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            // A rev-guarded update that loses the race returns 409 (conflict).
            response.status.value == 409 -> Result.Err(SyncConflictException())
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun create(path: String, data: ByteArray): Result<Unit> = withToken { token ->
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
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value == 409 -> Result.Err(SyncConflictException())
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun exists(path: String): Result<Boolean> = withToken { token ->
        val response = client.post("$apiBase/2/files/get_metadata") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("path", path) }.toString())
        }
        when {
            response.status.value in 200..299 -> Result.Ok(true)
            response.status.value == 409 -> {
                val body = response.bodyAsText()
                if (body.contains("path/not_found")) Result.Ok(false) else mapError(409, body)
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

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
import works.merc.keryx.app.core.CloudAuthException
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

    override suspend fun authenticate(): Result<Unit> = withToken { token ->
        val response = client.get("$apiBase/files") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("pageSize", "1")
                parameters.append("fields", "files(id)")
            }
        }
        when {
            response.status.value in 200..299 -> Result.Ok(Unit)
            response.status.value in setOf(401, 403) ->
                Result.Err(CloudAuthException("Authentication failed"))
            else -> mapError(response.status.value, response.bodyAsText())
        }
    }

    override suspend fun download(path: String): Result<CloudFile> = withToken { token ->
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
        Result.Ok(CloudFile(response.readRawBytes(), file.version))
    }

    override suspend fun upload(
        path: String,
        data: ByteArray,
        expectedRev: String?,
    ): Result<Unit> = withToken { token ->
        val name = fileName(path)
        val existing = when (val f = findFile(token, name)) {
            is Result.Err -> return@withToken f
            is Result.Ok -> f.value
        }
        if (existing == null) {
            createFile(token, name, data).map { }
        } else {
            // Best-effort optimistic concurrency: bail if the remote changed since download().
            if (expectedRev != null && existing.version != expectedRev) {
                return@withToken Result.Err(SyncConflictException())
            }
            updateFile(token, existing.id, data)
        }
    }

    override suspend fun create(path: String, data: ByteArray): Result<Unit> = withToken { token ->
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
            when (val created = createFile(token, name, data)) {
                is Result.Err -> created
                is Result.Ok -> {
                    val createdId = created.value
                    val listResult = listFilesByName(token, name)
                    if (listResult is Result.Err) return@withToken listResult
                    val files = (listResult as Result.Ok).value
                    if (files.none { it.id == createdId }) {
                        return@withToken Result.Err(CloudStorageException("Created file disappeared immediately"))
                    }
                    for (file in files) {
                        if (file.id != createdId) {
                            when (val del = deleteById(token, file.id)) {
                                is Result.Err -> return@withToken del
                                is Result.Ok -> Unit
                            }
                        }
                    }
                    Result.Ok(Unit)
                }
            }
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withToken { token ->
        // Idempotent: if the file is already absent, report success (nothing to delete).
        val existing = when (val f = findFile(token, fileName(path))) {
            is Result.Err -> return@withToken f
            is Result.Ok -> f.value ?: return@withToken Result.Ok(Unit)
        }
        val response = client.delete("$apiBase/files/${existing.id}") {
            header("Authorization", "Bearer $token")
        }
        okOrError(response) // Drive returns 204 on success, which okOrError accepts.
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

    override suspend fun exists(path: String): Result<Boolean> = withToken { token ->
        when (val f = findFile(token, fileName(path))) {
            is Result.Err -> f
            is Result.Ok -> Result.Ok(f.value != null)
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

    /** Looks up the single app-data file by name; returns null when absent. */
    private suspend fun findFile(token: String, name: String): Result<DriveFile?> {
        return when (val listResult = listFilesByName(token, name)) {
            is Result.Err -> listResult
            is Result.Ok -> Result.Ok(listResult.value.firstOrNull())
        }
    }

    /** Creates the file in appDataFolder via a multipart/related upload (metadata + media). Returns the created file id. */
    private suspend fun createFile(token: String, name: String, data: ByteArray): Result<String> {
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
        val body = opening + data + closing

        val response = client.post("$uploadBase/files") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("uploadType", "multipart")
                parameters.append("fields", "id,version")
            }
            contentType(ContentType("multipart", "related").withParameter("boundary", boundary))
            setBody(body)
        }
        if (response.status.value !in 200..299) {
            return mapError(response.status.value, response.bodyAsText())
        }
        val id = (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
            ?.get("id")?.jsonPrimitive?.content
            ?: return Result.Err(CloudStorageException("File metadata missing id"))
        return Result.Ok(id)
    }

    /** Overwrites the file's content with a simple media upload. */
    private suspend fun updateFile(token: String, fileId: String, data: ByteArray): Result<Unit> {
        val response = client.patch("$uploadBase/files/$fileId") {
            header("Authorization", "Bearer $token")
            url {
                parameters.append("uploadType", "media")
                parameters.append("fields", "id,version")
            }
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }
        return okOrError(response)
    }

    private suspend fun okOrError(response: HttpResponse): Result<Unit> = when {
        response.status.value in 200..299 -> Result.Ok(Unit)
        else -> mapError(response.status.value, response.bodyAsText())
    }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> {
        val token = accessTokenProvider()
            ?: return Result.Err(CloudAuthException("Not connected to Google Drive"))
        return try {
            block(token)
        } catch (e: Throwable) {
            Result.Err(CloudStorageException(e.message ?: "Google Drive request failed"))
        }
    }

    private fun mapError(status: Int, body: String): Result.Err = when (status) {
        401, 403 -> Result.Err(CloudAuthException("Authentication failed"))
        else -> Result.Err(CloudStorageException("Google Drive error (HTTP $status): ${body.take(200)}"))
    }

    /** Drive files are named, not path-addressed: use the basename of the sync path. */
    private fun fileName(path: String): String = path.substringAfterLast('/')

    private class DriveFile(val id: String, val version: String)

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
    }
}

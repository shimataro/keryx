package works.merc.keryx.app.data.cloud.nextcloud

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.CONNECTION_TIMEOUT_MS
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.FileUploadContent
import works.merc.keryx.app.data.cloud.cloudStorageError
import works.merc.keryx.app.data.cloud.headerRevOrConflictOr
import works.merc.keryx.app.data.cloud.okOrAbsentOr
import works.merc.keryx.app.data.cloud.withCloudCredential
import works.merc.keryx.app.data.cloud.writeBodyToFile
import kotlin.uuid.Uuid

private const val PROVIDER_NAME = "Nextcloud"

/** The folder under the user's files that holds every sync file — fixed, since WebDAV has no app folder. */
internal const val NEXTCLOUD_SYNC_FOLDER = "Keryx"

private val PROPFIND = HttpMethod("PROPFIND")
private val MKCOL = HttpMethod("MKCOL")
private val MOVE = HttpMethod("MOVE")

/** Asks a PROPFIND for the resource's ETag only. */
private const val PROPFIND_ETAG_BODY =
    """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""

/** Response headers carrying a written file's ETag, in the order they are read. */
private val WRITE_REV_HEADERS = listOf("OC-ETag", HttpHeaders.ETag)

/**
 * [CloudStorage] backed by a Nextcloud server's WebDAV API, storing the sync files in
 * `/Keryx/` under the user's own files (`{server}/remote.php/dav/files/{userId}/Keryx`). WebDAV has
 * no hidden app folder like the other providers' (see `docs/sync-architecture.md`'s "Nextcloud").
 *
 * [credentialsProvider] supplies the stored [AppPasswordCredentials]; anything else (or nothing) is
 * treated as not connected, as is a server URL that is not HTTPS — the app password travels on every
 * request as HTTP Basic authentication. Redirects are never followed (the shared client has
 * `followRedirects = false`), so a 3xx is a storage error rather than a hop to an address the user
 * never confirmed.
 *
 * The revision is the file's ETag, compared byte for byte (quotes included) by the server:
 * [upload] sends it back as `If-Match`, and a mismatch (412) or a concurrent write holding the file's
 * lock (423) is a [SyncConflictException]. [create] cannot rely on `If-None-Match: *`, which
 * Nextcloud does not apply atomically, so it uploads to a temporary name and moves it into place with
 * `Overwrite: F` — an existing file then fails the move with 412 instead of being overwritten. A 423
 * at any step of [create] (another write holding a lock on `/Keryx` or the path) is likewise a
 * [SyncConflictException], left to the sync's retry.
 */
class NextcloudStorage(
    private val client: HttpClient,
    private val credentialsProvider: suspend () -> CloudCredentials?,
    private val newTempName: () -> String = { ".upload-${Uuid.random()}.tmp" },
) : CloudStorage {

    /** Checks the credentials with a Depth-0 PROPFIND on the user's root folder. */
    override suspend fun authenticate(): Result<Unit> = withCredentials { credentials ->
        val response = client.request(userRootUrl(credentials)) { propfind(credentials) }
        if (response.status.value == 207) Result.Ok(Unit) else mapError(response)
    }

    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = withCredentials { credentials ->
        // `prepareGet` + `execute` streams the body: a plain `get` would read the whole file into
        // memory before `writeBodyToFile` copies it to disk.
        client.prepareGet(fileUrl(credentials, path)) { authorize(credentials) }.execute { response ->
            when (response.status.value) {
                404 -> Result.Err(CloudStorageException("File not found: ${path.substringAfterLast('/')}"))
                200 -> {
                    // The ETag of the very response being saved — the revision this download is based on.
                    val rev = response.headers[HttpHeaders.ETag]
                    if (rev == null) {
                        Result.Err(CloudStorageException("Missing ETag in download response"))
                    } else {
                        response.writeBodyToFile(destPath, MAX_SYNC_DB_SIZE_BYTES)
                        Result.Ok(CloudFileMeta(rev))
                    }
                }
                else -> mapError(response)
            }
        }
    }

    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> =
        withCredentials { credentials ->
            val response = client.request(fileUrl(credentials, path)) {
                method = HttpMethod.Put
                authorize(credentials)
                // Passed through untouched: the server compares it with the stored ETag byte for byte.
                if (expectedRev != null) header(HttpHeaders.IfMatch, expectedRev)
                setBody(FileUploadContent(sourcePath))
            }
            // 412: the ETag no longer matches (another device wrote first). 423: another device's
            // write holds the file's lock right now.
            response.headerRevOrConflictOr(PROVIDER_NAME, WRITE_REV_HEADERS, conflictStatuses = CONFLICT_STATUSES)
        }

    /**
     * Creates [path] without ever overwriting it: makes sure `/Keryx` exists, uploads to a temporary
     * name in it, then moves that onto [path] with `Overwrite: F`. The temporary file is deleted again
     * (best effort) whenever the move does not happen; one whose delete also fails stays in `/Keryx`,
     * since [CloudStorage] has no listing to find it by later. A 423 from any step is a
     * [SyncConflictException] (see [createStepError]).
     */
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = withCredentials { credentials ->
        val folder = client.request(folderUrl(credentials)) {
            method = MKCOL
            authorize(credentials)
        }
        // 405: the folder already exists.
        if (!folder.status.isSuccess() && folder.status.value != 405) return@withCredentials createStepError(folder)

        val tempUrl = "${folderUrl(credentials)}/${newTempName().encodeURLParameter()}"
        // One cleanup guard over both the PUT and the MOVE: a failure or cancellation anywhere
        // between them deletes the temporary file (bounded, and even while cancelled) and rethrows.
        try {
            val put = client.request(tempUrl) {
                method = HttpMethod.Put
                authorize(credentials)
                setBody(FileUploadContent(sourcePath))
            }
            if (!put.status.isSuccess()) {
                deleteQuietly(credentials, tempUrl)
                return@withCredentials createStepError(put)
            }
            val moved = client.request(tempUrl) {
                method = MOVE
                authorize(credentials)
                header("Destination", fileUrl(credentials, path))
                header("Overwrite", "F")
            }
            if (!moved.status.isSuccess()) deleteQuietly(credentials, tempUrl)
            // 412: the destination already exists — another device created it first. 423: another
            // device's write holds the destination's lock right now.
            moved.headerRevOrConflictOr(PROVIDER_NAME, WRITE_REV_HEADERS, conflictStatuses = CONFLICT_STATUSES)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { deleteQuietly(credentials, tempUrl) }
            }
            throw e
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withCredentials { credentials ->
        client.request(fileUrl(credentials, path)) {
            method = HttpMethod.Delete
            authorize(credentials)
        }.okOrAbsentOr(PROVIDER_NAME)
    }

    /**
     * Moves [from] onto [to] with `Overwrite: F`: a missing source counts as done (404), and an
     * occupied destination (412) is a storage error, never an overwrite.
     */
    override suspend fun rename(from: String, to: String): Result<Unit> = withCredentials { credentials ->
        client.request(fileUrl(credentials, from)) {
            method = MOVE
            authorize(credentials)
            header("Destination", fileUrl(credentials, to))
            header("Overwrite", "F")
        }.okOrAbsentOr(PROVIDER_NAME)
    }

    /** Reads [path]'s ETag with a Depth-0 PROPFIND; `null` when the file (or `/Keryx`) does not exist. */
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = withCredentials { credentials ->
        val response = client.request(fileUrl(credentials, path)) { propfind(credentials) }
        when (response.status.value) {
            207 -> {
                val rev = propfindEtag(response.bodyAsText())
                    ?: return@withCredentials Result.Err(CloudStorageException("Missing ETag in metadata"))
                Result.Ok(CloudFileMeta(rev))
            }
            404 -> Result.Ok(null)
            else -> mapError(response)
        }
    }

    /** Deletes the temporary upload at [url], ignoring any failure — a leftover is accepted. */
    private suspend fun deleteQuietly(credentials: AppPasswordCredentials, url: String) {
        try {
            client.request(url) {
                method = HttpMethod.Delete
                authorize(credentials)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best effort: the leftover stays in /Keryx (see create()).
        }
    }

    private suspend fun <T> withCredentials(block: suspend (AppPasswordCredentials) -> Result<T>): Result<T> =
        withCloudCredential(credentialProvider = { credentialsProvider() as? AppPasswordCredentials }, providerName = PROVIDER_NAME) {
            // A second line of defense behind the connect flow's own check: never send the app
            // password anywhere but HTTPS.
            if (!isHttpsUrl(it.serverUrl)) Result.Err(CloudAuthException("$PROVIDER_NAME server is not HTTPS")) else block(it)
        }

    private suspend fun mapError(response: HttpResponse): Result.Err =
        cloudStorageError(PROVIDER_NAME, response.status.value, response.bodyAsText())

    /**
     * A failed [create] step before the move (the `MKCOL` or the temporary `PUT`): 423 means another
     * write holds a lock on `/Keryx` or the path right now, a conflict for the sync to retry, as in
     * [upload]; anything else is a storage error.
     */
    private suspend fun createStepError(response: HttpResponse): Result.Err =
        if (response.status.value == 423) Result.Err(SyncConflictException()) else mapError(response)

    private fun HttpRequestBuilder.authorize(credentials: AppPasswordCredentials) {
        basicAuth(credentials.loginName, credentials.appPassword)
        header(HttpHeaders.UserAgent, APP_NAME)
    }

    private fun HttpRequestBuilder.propfind(credentials: AppPasswordCredentials) {
        method = PROPFIND
        authorize(credentials)
        header("Depth", "0")
        setBody(TextContent(PROPFIND_ETAG_BODY, ContentType.Application.Xml))
    }

    private fun userRootUrl(credentials: AppPasswordCredentials): String =
        "${credentials.serverUrl}/remote.php/dav/files/${credentials.userId.encodeURLParameter()}/"

    private fun folderUrl(credentials: AppPasswordCredentials): String =
        userRootUrl(credentials) + NEXTCLOUD_SYNC_FOLDER

    /** The full URL of the sync path [path] (`/keryx.db.gz`, …) inside `/Keryx`, each segment encoded. */
    private fun fileUrl(credentials: AppPasswordCredentials, path: String): String =
        folderUrl(credentials) + path.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + it.encodeURLParameter() }

    private companion object {
        val CONFLICT_STATUSES = setOf(412, 423)
    }
}

/**
 * The first non-blank `getetag` in a PROPFIND multistatus [body], matched by local name so the
 * namespace prefix the server chose (`d:`, `D:`, none) does not matter. `null` when there is none.
 */
internal fun propfindEtag(body: String): String? =
    Ksoup.parse(html = body, parser = Parser.xmlParser())
        .getAllElements()
        .firstOrNull { it.tagName().substringAfterLast(':').equals("getetag", ignoreCase = true) && it.text().isNotBlank() }
        ?.text()
        ?.trim()

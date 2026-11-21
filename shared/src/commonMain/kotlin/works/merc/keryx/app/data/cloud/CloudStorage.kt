package works.merc.keryx.app.data.cloud

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException

/**
 * A cloud file's revision, without its contents.
 *
 * Every provider already returns this in the same metadata request that answers "does the file
 * exist" (Dropbox's `get_metadata`, Drive's name lookup, Graph's item GET), so carrying the rev
 * out of that call costs no extra round trip — and lets `SyncRepository` recognise a cloud file
 * it has already merged and skip downloading it again.
 */
class CloudFileMeta(val rev: String)

/**
 * One file in the app's sync folder, as [CloudStorage.list] reports it.
 *
 * @property name The file's basename, with no leading slash (`keryx-sync.json`, …).
 * @property rev The file's revision, in the same format [CloudStorage.metadata] and
 *   [CloudStorage.upload] report for it (Dropbox `rev`; Google Drive `<id>:<version>`; OneDrive
 *   DriveItem `eTag`; Nextcloud WebDAV `ETag`, quotes included).
 * @property size The file's size in bytes.
 */
data class CloudFileEntry(val name: String, val rev: String, val size: Long)

/**
 * Executes [block] with an access token and maps authentication or storage failures to [Result.Err].
 *
 * @param providerName The cloud provider name used in authentication and storage error messages.
 * @param block The operation to execute with the access token.
 * @return The result produced by [block], or an error when authentication or execution fails.
 * @throws CancellationException If the coroutine is cancelled while executing the operation.
 */
internal suspend fun <T> withCloudToken(
    accessTokenProvider: suspend () -> String?,
    providerName: String,
    block: suspend (String) -> Result<T>,
): Result<T> = withCloudCredential(accessTokenProvider, providerName, block)

/**
 * Executes [block] with the credential [credentialProvider] supplies — an access token, or a
 * provider's own credentials type — and maps a missing credential to [CloudAuthException] and any
 * failure thrown by [block] to [CloudStorageException].
 *
 * @param credentialProvider Supplies the credential, or `null` when not connected.
 * @param providerName The cloud provider name used in authentication and storage error messages.
 * @param block The operation to execute with the credential.
 * @return The result produced by [block], or an error when no credential is available or [block] throws.
 * @throws CancellationException If the coroutine is cancelled while executing the operation.
 */
internal suspend fun <C : Any, T> withCloudCredential(
    credentialProvider: suspend () -> C?,
    providerName: String,
    block: suspend (C) -> Result<T>,
): Result<T> {
    val credential = credentialProvider() ?: return Result.Err(CloudAuthException("Not connected to $providerName"))
    return try {
        block(credential)
    } catch (e: CancellationException) {
        // Never swallow coroutine cancellation (e.g. a debounced sync superseded by a newer
        // one) — rethrow so it unwinds silently instead of being mis-logged as a sync error.
        throw e
    } catch (e: Throwable) {
        Result.Err(CloudStorageException(e.message ?: "$providerName request failed"))
    }
}

/**
 * Converts an HTTP error response into a cloud authentication or storage error.
 *
 * @param providerName The name of the cloud provider.
 * @param status The HTTP status code.
 * @param body The response body used to provide context for storage errors.
 * @return An authentication error for status 401 or 403; otherwise, a storage error containing the provider, status, and truncated response body.
 */
internal fun cloudStorageError(providerName: String, status: Int, body: String): Result.Err = when (status) {
    401, 403 -> Result.Err(CloudAuthException("Authentication failed"))
    else -> Result.Err(CloudStorageException("$providerName error (HTTP $status): ${body.take(CLOUD_ERROR_BODY_PREVIEW_LENGTH)}"))
}

/**
 * Maps a file-write response to the revision it produced, a sync conflict, or a storage error.
 *
 * Every provider's successful content write answers with the written item's metadata, so the new
 * revision is read straight from the write itself rather than from a follow-up metadata request,
 * which could otherwise observe another device's later write.
 *
 * @param json The JSON parser used to read the response body.
 * @param providerName The name of the cloud storage provider.
 * @param revKey The top-level response field holding the written revision.
 * @param conflictStatus The HTTP status the provider uses to report a lost write guard.
 * @return The written revision, a conflict, or a mapped storage error.
 */
internal suspend fun HttpResponse.writtenMetaOrConflictOr(
    json: Json,
    providerName: String,
    revKey: String,
    conflictStatus: Int,
): Result<CloudFileMeta> = when {
    status.isSuccess() -> {
        val rev = json.topLevelString(bodyAsText(), revKey)
            ?: return Result.Err(CloudStorageException("Missing $revKey in upload response"))
        Result.Ok(CloudFileMeta(rev))
    }
    status.value == conflictStatus -> Result.Err(SyncConflictException())
    else -> cloudStorageError(providerName, status.value, bodyAsText())
}

/**
 * Maps a file-write response whose revision travels in a response header (WebDAV's `ETag`) to that
 * revision, a sync conflict, or a storage error — the header-based sibling of
 * [writtenMetaOrConflictOr], for the same reason: the revision comes from the write itself, never
 * from a follow-up request that could observe another device's later write.
 *
 * @param providerName The name of the cloud storage provider.
 * @param revHeaders The response headers that may carry the written revision, read in order; the
 *   first one present wins.
 * @param conflictStatuses The HTTP statuses the provider uses to report a lost write guard.
 * @return The written revision, a conflict, or a mapped storage error.
 */
internal suspend fun HttpResponse.headerRevOrConflictOr(
    providerName: String,
    revHeaders: List<String>,
    conflictStatuses: Set<Int>,
): Result<CloudFileMeta> = when {
    status.isSuccess() -> {
        val rev = revHeaders.firstNotNullOfOrNull { name -> headers[name]?.takeIf { it.isNotBlank() } }
            ?: return Result.Err(CloudStorageException("Missing ETag in upload response"))
        Result.Ok(CloudFileMeta(rev))
    }
    status.value in conflictStatuses -> Result.Err(SyncConflictException())
    else -> cloudStorageError(providerName, status.value, bodyAsText())
}

/**
 * Maps an HTTP response to success for a 2xx, or a cloud storage error otherwise.
 *
 * @param providerName The name of the cloud storage provider.
 */
internal suspend fun HttpResponse.okOr(providerName: String): Result<Unit> = when {
    status.isSuccess() -> Result.Ok(Unit)
    else -> cloudStorageError(providerName, status.value, bodyAsText())
}

/**
 * Maps an HTTP response to success for a 2xx or a 404, or a cloud storage error otherwise — for an
 * idempotent delete/rename, where an already-absent item counts as done.
 *
 * @param providerName The name of the cloud storage provider.
 */
internal suspend fun HttpResponse.okOrAbsentOr(providerName: String): Result<Unit> = when {
    status.isSuccess() -> Result.Ok(Unit)
    status.value == 404 -> Result.Ok(Unit)
    else -> cloudStorageError(providerName, status.value, bodyAsText())
}

/**
 * The basename of a sync path — the name a provider that addresses files by name (Google Drive,
 * OneDrive's app folder) stores it under.
 */
internal fun cloudFileName(path: String): String = path.substringAfterLast('/')

/** The string value of the top-level [key] in the JSON object [body], or `null` if there is none. */
internal fun Json.topLevelString(body: String, key: String): String? =
    (parseToJsonElement(body) as? JsonObject)?.get(key)?.jsonPrimitive?.content

/**
 * Abstraction over a cloud storage backend used as the sync bus. Implemented by
 * [DropboxStorage], [GoogleDriveStorage], [OneDriveStorage], and
 * [works.merc.keryx.app.data.cloud.nextcloud.NextcloudStorage].
 */
interface CloudStorage {
    /** Verifies the current credentials are valid. */
    suspend fun authenticate(): Result<Unit>

    /**
     * Downloads [path] into the local file [destPath], replacing anything already there, and
     * returns the downloaded revision.
     *
     * Takes a destination path rather than returning the bytes because the sync DB is the largest
     * payload this app moves and always ends up on disk anyway — the merge attaches it as a file.
     * Streaming straight there keeps peak memory independent of how big the database has grown.
     */
    suspend fun download(path: String, destPath: String): Result<CloudFileMeta>

    /**
     * Uploads the local file [sourcePath] to [path]. When [expectedRev] is non-null, the write
     * fails with [works.merc.keryx.app.core.SyncConflictException] if the remote rev differs
     * (another device wrote first).
     *
     * Streams the file rather than taking its bytes, for the same reason as [download].
     *
     * Returns the revision **this write produced**. The caller records it as the revision it has
     * already merged, so its next sync recognises its own upload and does not download it back.
     * It must come from the write's own response rather than a follow-up [metadata] call: a
     * second request could observe another device's newer write instead, and storing that rev
     * would make the next sync skip a download whose contents were never merged.
     */
    suspend fun upload(path: String, sourcePath: String, expectedRev: String? = null): Result<CloudFileMeta>

    /**
     * Creates [path] from the local file [sourcePath] only if it does not already exist. If the
     * file is already present, fails with [works.merc.keryx.app.core.SyncConflictException]
     * rather than overwriting it — the safe primitive for the first-ever upload, so a
     * wrong "does not exist" reading can never destroy another device's data.
     *
     * Returns the created file's revision, for the same reason as [upload].
     */
    suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta>

    /**
     * Deletes [path]. Idempotent: succeeds with [Result.Ok] when the file is already absent.
     * Used to reset (discard) the cloud sync data so a fresh copy can be uploaded.
     */
    suspend fun delete(path: String): Result<Unit>

    /**
     * Renames (moves) [from] to [to] within the provider's app folder.
     *
     * Idempotent on a missing source: succeeds with [Result.Ok] when [from] does not exist, so a
     * caller archiving a file it may or may not have can treat the archive as done either way.
     *
     * Never overwrites [to]: an existing destination fails with
     * [works.merc.keryx.app.core.CloudStorageException]. The archive is the only copy of the data
     * being replaced, so clobbering it would be the data loss this call exists to prevent.
     * (Google Drive is name-addressed and permits duplicate names, so a destination collision is
     * not observable there; that asymmetry is harmless because the archive name carries a
     * timestamp.)
     *
     * Used to archive the cloud sync DB before recreating it (see `SyncRepository.resetCloudData`).
     */
    suspend fun rename(from: String, to: String): Result<Unit>

    /**
     * Fetches [path]'s revision without its contents, or `null` when the file does not exist.
     *
     * Doubles as the existence check (`null` == absent). Every implementation already issued this
     * exact request to answer that question and discarded the rev; returning it lets a caller
     * compare against the last revision it merged, so an unchanged cloud file need not be
     * downloaded at all.
     */
    suspend fun metadata(path: String): Result<CloudFileMeta?>

    /**
     * Lists every file in the app's sync folder whose basename starts with [prefix] (an empty
     * [prefix] lists them all), following every page of the provider's listing. Folders are
     * excluded, and an app folder that does not exist yet (never provisioned, or nothing written
     * to it) yields an empty list rather than an error.
     *
     * The listing may be eventually consistent (Google Drive, OneDrive): a file written moments
     * ago can still be missing from it. Google Drive is name-addressed and permits duplicates, so
     * the same name can appear twice with different revisions. Callers must tolerate both.
     */
    suspend fun list(prefix: String): Result<List<CloudFileEntry>>
}

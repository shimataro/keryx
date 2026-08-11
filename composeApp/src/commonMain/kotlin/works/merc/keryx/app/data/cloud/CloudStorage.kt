package works.merc.keryx.app.data.cloud

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException

/** A downloaded cloud file plus its revision (used for optimistic-concurrency upload). */
class CloudFile(val data: ByteArray, val rev: String)

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
): Result<T> {
    val token = accessTokenProvider() ?: return Result.Err(CloudAuthException("Not connected to $providerName"))
    return try {
        block(token)
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
 * Maps an HTTP response to a successful result, a sync conflict, or a cloud storage error.
 *
 * @param providerName The name of the cloud storage provider.
 * @param conflictStatus The HTTP status code representing a sync conflict.
 * @return A successful result for 2xx responses, a sync conflict for the configured status, or a cloud storage error otherwise.
 */
internal suspend fun HttpResponse.okOrConflictOr(providerName: String, conflictStatus: Int): Result<Unit> = when {
    status.value in 200..299 -> Result.Ok(Unit)
    status.value == conflictStatus -> Result.Err(SyncConflictException())
    else -> cloudStorageError(providerName, status.value, bodyAsText())
}

/**
 * Abstraction over a cloud storage backend used as the sync bus. Implemented by
 * [DropboxStorage], [GoogleDriveStorage], and [OneDriveStorage].
 */
interface CloudStorage {
    /** Verifies the current credentials are valid. */
    suspend fun authenticate(): Result<Unit>

    suspend fun download(path: String): Result<CloudFile>

    /**
     * Uploads [data] to [path]. When [expectedRev] is non-null, the write fails
     * with [works.merc.keryx.app.core.SyncConflictException] if the remote rev
     * differs (another device wrote first).
     */
    suspend fun upload(path: String, data: ByteArray, expectedRev: String? = null): Result<Unit>

    /**
     * Creates [path] with [data] only if it does not already exist. If the file
     * is already present, fails with [works.merc.keryx.app.core.SyncConflictException]
     * rather than overwriting it — the safe primitive for the first-ever upload, so a
     * wrong "does not exist" reading can never destroy another device's data.
     */
    suspend fun create(path: String, data: ByteArray): Result<Unit>

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

    suspend fun exists(path: String): Result<Boolean>
}

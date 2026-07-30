package works.merc.keryx.app.data.cloud

import kotlinx.coroutines.CancellationException
import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result

/** A downloaded cloud file plus its revision (used for optimistic-concurrency upload). */
class CloudFile(val data: ByteArray, val rev: String)

/**
 * Runs [block] with a valid access token from [accessTokenProvider], converting a missing token or
 * any thrown exception into the appropriate [CloudStorage] error. Shared by every [CloudStorage]
 * implementation's `withToken`; [providerName] only affects the wording of the two fallback messages.
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

/** Maps a non-2xx HTTP [status]/[body] to the appropriate [CloudStorage] error, shared by every implementation's `mapError`. */
internal fun cloudStorageError(providerName: String, status: Int, body: String): Result.Err = when (status) {
    401, 403 -> Result.Err(CloudAuthException("Authentication failed"))
    else -> Result.Err(CloudStorageException("$providerName error (HTTP $status): ${body.take(CLOUD_ERROR_BODY_PREVIEW_LENGTH)}"))
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

    suspend fun exists(path: String): Result<Boolean>
}

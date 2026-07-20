package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.Result

/** A downloaded cloud file plus its revision (used for optimistic-concurrency upload). */
class CloudFile(val data: ByteArray, val rev: String)

/**
 * Abstraction over a cloud storage backend used as the sync bus. Implemented by
 * [DropboxStorage] and [GoogleDriveStorage]; further backends (e.g. OneDrive)
 * would add their own.
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

    suspend fun exists(path: String): Result<Boolean>
}

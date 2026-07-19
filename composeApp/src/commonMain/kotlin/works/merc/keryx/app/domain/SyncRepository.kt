package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.CLOUD_DB_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_STATE_CLOUD_FILE_REV
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.DatabaseMerger
import works.merc.keryx.app.platform.DatabaseSnapshot
import works.merc.keryx.app.platform.FileIO

/**
 * Cloud sync via the "upload the whole SQLite file" strategy. Downloads the
 * cloud DB, merges it into the local DB with [MergeSql] (ATTACH DATABASE), then
 * re-uploads with a rev guard. The FTS5 index is excluded from the uploaded file
 * on a throwaway [DatabaseSnapshot] copy — the live index is never dropped — and
 * merged-in articles are indexed incrementally ([FtsManager.indexMissing]).
 *
 * Also acts as the [SyncScheduler] for the rest of the app (debounced sync).
 */
class SyncRepository(
    private val driver: SqlDriver,
    private val db: KeryxDatabase,
    private val ftsManager: FtsManager,
    private val cloudProvider: () -> CloudStorage?,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val activityCenter: ActivityCenter,
    private val localDbPath: String = FileIO.join(AppDirs.appDataDir(), DB_FILE_NAME),
    private val tempDir: String = AppDirs.tempDir(),
) : SyncScheduler {

    private val mutex = Mutex()
    private var debounceJob: Job? = null

    override fun scheduleSync() {
        if (cloudProvider() == null) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            sync()
        }
    }

    suspend fun sync(): Result<Unit> =
        activityCenter.trackSync { mutex.withLock { syncLocked() } }

    private suspend fun syncLocked(): Result<Unit> {
        val cloud = cloudProvider() ?: return Result.Ok(Unit)

        // First sync ever: no cloud file yet → just upload the local DB.
        when (val exists = cloud.exists(CLOUD_DB_PATH)) {
            is Result.Err -> return exists
            is Result.Ok -> if (!exists.value) return uploadFresh(cloud)
        }

        repeat(SYNC_MAX_RETRY) {
            val cloudFile = when (val d = cloud.download(CLOUD_DB_PATH)) {
                is Result.Ok -> d.value
                is Result.Err -> return d
            }

            when (val merged = mergeCloud(cloudFile.data)) {
                is Result.Err -> return merged
                is Result.Ok -> Unit
            }
            setSyncState(SYNC_STATE_CLOUD_FILE_REV, cloudFile.rev)

            val bytes = snapshotBytesForUpload()

            when (val upload = cloud.upload(CLOUD_DB_PATH, bytes, expectedRev = cloudFile.rev)) {
                is Result.Ok -> {
                    setSyncState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                    return Result.Ok(Unit)
                }
                is Result.Err ->
                    if (upload.exception is SyncConflictException) return@repeat else return upload
            }
        }
        Log.warn(TAG, "Sync failed after $SYNC_MAX_RETRY retries (rev conflict never resolved)")
        return Result.Err(CloudStorageException("Sync failed after $SYNC_MAX_RETRY retries"))
    }

    private suspend fun uploadFresh(cloud: CloudStorage): Result<Unit> {
        val bytes = snapshotBytesForUpload()
        return when (val r = cloud.upload(CLOUD_DB_PATH, bytes, expectedRev = null)) {
            is Result.Ok -> {
                setSyncState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                Result.Ok(Unit)
            }
            is Result.Err -> r
        }
    }

    /** Writes the downloaded cloud DB to a temp file and merges it into the local DB. */
    private fun mergeCloud(data: ByteArray): Result<Unit> {
        val tempPath = FileIO.join(tempDir, "cloud_keryx.db")
        try {
            FileIO.writeBytes(tempPath, data)
            DatabaseMerger.merge(
                localDbPath = localDbPath,
                cloudDbPath = tempPath,
                localSchemaVersion = KeryxDatabase.Schema.version,
                mergeStatements = MergeSql.all,
            )
            // Index the articles the merge brought in (incremental — the live index is never wiped),
            // before notifying listeners so the reactive search re-run sees the new rows.
            ftsManager.indexMissing()
            // The merge writes through DatabaseMerger's own JDBC connection, bypassing the
            // SQLDelight driver, so watchAll() flows aren't notified. Poke the listeners for
            // every table the merge touches so the UI reflects synced changes without a restart.
            driver.notifyListeners("folders", "feeds", "tags", "feed_tags", "articles", "global_settings")
            return Result.Ok(Unit)
        } catch (e: KeryxException) {
            return Result.Err(e)
        } catch (e: Throwable) {
            Log.error(TAG, "Cloud DB merge failed", e)
            return Result.Err(CloudStorageException("Merge failed: ${e.message}"))
        } finally {
            FileIO.delete(tempPath)
        }
    }

    /**
     * Builds the bytes to upload: an FTS-free, consistent snapshot of the local DB. The live DB and
     * its `articles_fts` index are untouched (see [DatabaseSnapshot]), so a concurrent search never
     * sees a missing index.
     */
    private fun snapshotBytesForUpload(): ByteArray {
        val snapshotPath = FileIO.join(tempDir, "upload_keryx.db")
        try {
            DatabaseSnapshot.exportForUpload(localDbPath, snapshotPath)
            return FileIO.readBytes(snapshotPath) ?: ByteArray(0)
        } finally {
            FileIO.delete(snapshotPath)
        }
    }

    private fun setSyncState(key: String, value: String) {
        db.sync_stateQueries.upsert(key, value)
    }

    private companion object {
        const val TAG = "Sync"
    }

    fun lastSyncedAt(): Long? =
        db.sync_stateQueries.get(SYNC_STATE_LAST_SYNCED_AT).executeAsOneOrNull()?.toLongOrNull()
}

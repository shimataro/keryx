package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.CLOUD_DB_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_STATE_CLOUD_FILE_REV
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SchemaVersionException
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
    private val notificationCenter: NotificationCenter,
    private val notificationMessages: NotificationMessages,
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

    suspend fun sync(): Result<Unit> {
        val result = activityCenter.trackSync { mutex.withLock { syncLocked() } }
        emitErrorNotification(result)
        return result
    }

    /**
     * Discards the cloud sync data and re-uploads this device's local DB fresh: deletes the cloud
     * file, then [createFresh]. Recovery path for a corrupt / incompatible cloud DB. Runs the whole
     * delete-then-create under a single lock (calling [sync] here would deadlock — the mutex is not
     * reentrant).
     */
    suspend fun resetCloudData(): Result<Unit> {
        val cloud = cloudProvider() ?: return Result.Ok(Unit)
        val result = activityCenter.trackSync {
            mutex.withLock {
                when (val del = cloud.delete(CLOUD_DB_PATH)) {
                    is Result.Err -> {
                        Log.error(TAG, "Reset: delete failed: ${del.exception.message}")
                        del
                    }
                    // The cloud file is gone, so re-create it from the local DB.
                    is Result.Ok -> createFresh(cloud)
                }
            }
        }
        emitErrorNotification(result)
        return result
    }

    /**
     * Surface a genuine failure to the notification center — callers discard the Result, so without
     * this an auth/storage error is invisible. Conflicts are retried internally (never user-facing)
     * and a no-op returns Ok, so neither notifies.
     */
    private suspend fun emitErrorNotification(result: Result<Unit>) {
        if (result is Result.Err && result.exception !is SyncConflictException) {
            // A corrupt/incompatible cloud DB is only fixable by resetting it, so offer that as an
            // inline action. Other errors (auth, transient) carry no action.
            val action = if (result.exception is CloudDataIncompatibleException) {
                AppNotificationAction.RESET_CLOUD_DATA
            } else {
                null
            }
            notificationCenter.add(
                AppNotification(
                    id = IdGenerator.newId(),
                    level = AppNotificationLevel.ERROR,
                    message = notificationMessages.syncFailed(result.exception),
                    timestampMillis = clock.nowMillis(),
                    action = action,
                ),
            )
        }
    }

    private suspend fun syncLocked(): Result<Unit> {
        val cloud = cloudProvider() ?: run {
            Log.info(TAG, "Sync skipped: no cloud provider connected")
            return Result.Ok(Unit)
        }

        // First sync ever: no cloud file yet → create it (create-only, never overwrite).
        // If the file actually already exists (a wrong `exists=false`, a scope/account mismatch,
        // or a concurrent creator), `createFresh` returns SyncConflictException and we fall through
        // to the download→merge→update path instead of clobbering the other device's data.
        when (val exists = cloud.exists(CLOUD_DB_PATH)) {
            is Result.Err -> {
                Log.error(TAG, "Sync: exists() failed: ${exists.exception.message}")
                return exists
            }
            is Result.Ok -> if (!exists.value) {
                when (val created = createFresh(cloud)) {
                    is Result.Ok -> return Result.Ok(Unit)
                    is Result.Err ->
                        if (created.exception !is SyncConflictException) return created
                    // else: cloud file already exists → continue into the merge path below.
                }
            }
        }

        repeat(SYNC_MAX_RETRY) {
            val cloudFile = when (val d = cloud.download(CLOUD_DB_PATH)) {
                is Result.Ok -> d.value
                is Result.Err -> {
                    Log.error(TAG, "Sync: download failed: ${d.exception.message}")
                    return d
                }
            }

            when (val merged = mergeCloud(cloudFile.data)) {
                is Result.Err -> return merged
                is Result.Ok -> Unit
            }
            setSyncState(SYNC_STATE_CLOUD_FILE_REV, cloudFile.rev)

            val bytes = when (val b = snapshotBytesForUpload()) {
                is Result.Ok -> b.value
                is Result.Err -> return b
            }

            when (val upload = cloud.upload(CLOUD_DB_PATH, bytes, expectedRev = cloudFile.rev)) {
                is Result.Ok -> {
                    setSyncState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                    return Result.Ok(Unit)
                }
                is Result.Err ->
                    if (upload.exception is SyncConflictException) {
                        return@repeat
                    } else {
                        Log.error(TAG, "Sync: upload failed: ${upload.exception.message}")
                        return upload
                    }
            }
        }
        Log.warn(TAG, "Sync failed after $SYNC_MAX_RETRY retries (rev conflict never resolved)")
        return Result.Err(CloudStorageException("Sync failed after $SYNC_MAX_RETRY retries"))
    }

    /**
     * First-ever upload: creates the cloud file with a create-only write (never an unconditional
     * overwrite). Returns [SyncConflictException] if the file already exists, so the caller can fall
     * back to the merge path rather than destroy the existing data.
     */
    private suspend fun createFresh(cloud: CloudStorage): Result<Unit> {
        val bytes = when (val b = snapshotBytesForUpload()) {
            is Result.Ok -> b.value
            is Result.Err -> return b
        }
        return when (val r = cloud.create(CLOUD_DB_PATH, bytes)) {
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
            // The merge-abort path (e.g. an incompatible/newer cloud schema) is otherwise silent:
            // the notification is the only signal. Log it so it's recoverable after release.
            if (e is SchemaVersionException) {
                Log.warn(TAG, "Cloud DB merge aborted: cloud schema v${e.cloudVersion} is incompatible with local v${e.localVersion}")
            } else {
                Log.warn(TAG, "Cloud DB merge aborted: ${e.message}")
            }
            return Result.Err(e)
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            // Distinguish a permanently-unusable cloud DB (corrupt file or incompatible/foreign
            // schema) from a genuinely transient failure: the former will never succeed on retry,
            // so it must not be reported as "try again later".
            if (isUnusableCloudDb(msg)) {
                val hasExpectedSchema = DatabaseMerger.validateSchema(tempPath, KeryxDatabase.Schema.version)
                if (hasExpectedSchema) {
                    // Schema looks correct: this is an app bug, not incompatible data.
                    Log.error(TAG, "Cloud DB merge failed despite compatible schema", e)
                    return Result.Err(CloudStorageException("Merge failed: ${e.message}"))
                }
                Log.warn(TAG, "Cloud DB unusable (corrupt or incompatible schema): $msg")
                return Result.Err(CloudDataIncompatibleException("Cloud DB unusable: $msg"))
            }
            Log.error(TAG, "Cloud DB merge failed", e)
            return Result.Err(CloudStorageException("Merge failed: ${e.message}"))
        } finally {
            FileIO.delete(tempPath)
        }
    }

    /**
     * Heuristic classification of a merge failure as "the cloud DB is unusable" (corrupt or an
     * incompatible schema) rather than transient, by matching SQLite's error text. On no match we
     * fall back to [CloudStorageException] (transient), so a miss never regresses behavior.
     */
    private fun isUnusableCloudDb(message: String): Boolean {
        val m = message.lowercase()
        return listOf(
            "not a database",
            "malformed",
            "disk image",
            "no such column",
            "no such table",
            "file is encrypted",
        ).any { it in m }
    }

    /**
     * Builds the bytes to upload: an FTS-free, consistent snapshot of the local DB. The live DB and
     * its `articles_fts` index are untouched (see [DatabaseSnapshot]), so a concurrent search never
     * sees a missing index.
     *
     * Returns [Result.Err] rather than a partial/empty payload: an export failure or a truncated /
     * non-SQLite snapshot must never be uploaded over good cloud data. The `finally` still deletes
     * the temp file even on failure.
     */
    private fun snapshotBytesForUpload(): Result<ByteArray> {
        val snapshotPath = FileIO.join(tempDir, "upload_keryx.db")
        return try {
            DatabaseSnapshot.exportForUpload(localDbPath, snapshotPath)
            val bytes = FileIO.readBytes(snapshotPath)
            when {
                bytes == null || bytes.size < SQLITE_HEADER.size ->
                    Result.Err(CloudStorageException("Snapshot missing or too small to upload"))
                !bytes.copyOf(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER) ->
                    Result.Err(CloudStorageException("Snapshot is not a valid SQLite file"))
                else -> Result.Ok(bytes)
            }
        } catch (e: Throwable) {
            Log.error(TAG, "Snapshot export failed", e)
            Result.Err(CloudStorageException("Snapshot export failed: ${e.message}"))
        } finally {
            FileIO.delete(snapshotPath)
        }
    }

    private fun setSyncState(key: String, value: String) {
        db.sync_stateQueries.upsert(key, value)
    }

    private companion object {
        const val TAG = "Sync"

        /** The 16-byte magic string every valid SQLite database file starts with. */
        val SQLITE_HEADER = byteArrayOf(
            0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }

    fun lastSyncedAt(): Long? =
        db.sync_stateQueries.get(SYNC_STATE_LAST_SYNCED_AT).executeAsOneOrNull()?.toLongOrNull()
}

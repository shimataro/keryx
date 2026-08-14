package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.CLOUD_DB_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.cloudBackupPath
import works.merc.keryx.app.core.looksLikeSqliteFile
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_STATE_CLOUD_FILE_REV
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SYNC_STATE_LAST_UPLOADED_DIGEST
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.ContentDigest
import works.merc.keryx.app.platform.DatabaseMerger
import works.merc.keryx.app.platform.Gzip
import works.merc.keryx.app.platform.DatabaseSnapshot
import works.merc.keryx.app.platform.FileIO

/**
 * Who asked for a sync. Only [AUTOMATIC] is subject to the unusable-cloud-DB gate
 * ([SyncRepository.autoSyncSuspended]): a person who pressed "sync" must always get a real
 * attempt (and the failure that explains why), or the app would silently do nothing with no way
 * for them to find out.
 */
enum class SyncTrigger { MANUAL, AUTOMATIC }

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
    private val _lastSyncError = MutableStateFlow<String?>(null)
    private val _autoSyncSuspended = MutableStateFlow(false)

    /**
     * Debounce signals, coalesced. [scheduleSync] is called from the UI thread (tag / folder / feed
     * edits), from `HomeViewModel`'s single-threaded write dispatcher (every mark-as-read / star) and
     * from `Dispatchers.Default` (once per feed in a refresh). Cancelling and reassigning a shared
     * `Job` field from three dispatchers can drop a reference, leaving a pending debounce that
     * nothing can cancel — so a write burst fires duplicate full
     * download → merge → `VACUUM INTO` → upload cycles. (Integrity is never at risk: [sync] holds
     * [mutex] and the upload is rev-checked. The cost is the duplicated work.) A conflated channel
     * with the single consumer below keeps the same trailing-debounce semantics with no shared
     * mutable state, mirroring how `SettingsRepository` coalesces its own disk write.
     */
    private val syncSignals = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in syncSignals) {
                // Re-arm as long as calls keep arriving, so the window is measured from the last one.
                while (withTimeoutOrNull(SYNC_DEBOUNCE_MS) { syncSignals.receive() } != null) {
                    // Another scheduleSync() landed inside the window; restart it.
                }
                // This is the *only* consumer, so a throw escaping here would end debounced syncing
                // for the rest of the process while scheduleSync() kept succeeding into a channel
                // nobody reads. sync() is not exception-proof (a bare SQLDelight write in
                // setSyncState, resource loading in emitErrorNotification), which is why its other
                // call sites in main.kt guard the same way.
                runCatching { sync(SyncTrigger.AUTOMATIC) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.error(TAG, "Debounced sync failed", e)
                    }
            }
        }
    }

    /**
     * Why the last sync failed, or null when it succeeded (or none has run yet) — the same localized
     * text as the notification-center entry, kept as state so the cloud-sync settings tab can show
     * "why sync is broken right now" even after that notification was dismissed.
     */
    val lastSyncError: StateFlow<String?> = _lastSyncError

    /**
     * True while the cloud DB is known-unusable and automatic syncing is therefore paused (see
     * [sync]). Deliberately in-memory, not persisted: a process restart is a free, honest retry
     * (another device may have reset the cloud in the meantime), and suppressing repeated failures
     * within one running process — the entire point, since every debounced write and every
     * background cycle would otherwise re-download the same unusable file, re-run the merge, and
     * re-raise the same notification — needs nothing more durable than that.
     */
    val autoSyncSuspended: StateFlow<Boolean> = _autoSyncSuspended

    /**
     * Clears everything tied to the current cloud connection — the mirrored failure reason, the
     * automatic-sync pause, and the "what we last saw / last uploaded" markers — e.g. when the
     * connection that produced them is being torn down so a subsequently-connected provider does
     * not inherit any of it.
     *
     * The revision and digest are dropped for the same reason as the failure state: they describe
     * one provider's file. Each provider's revision is an opaque string in its own format, so a
     * stale one would in practice never match the next provider's — but "would never match" is a
     * weaker guarantee than "cannot", and a match here would skip a download that was never
     * merged. Reconnecting to the *same* provider re-establishes both on the first sync.
     *
     * Runs under [mutex] — the same lock [sync] holds across everything that writes these four
     * fields. Without it, a sync already past its upload would re-write the revision and digest
     * *after* this cleared them, handing the next connection markers describing the old provider's
     * file: the very skipped-download-that-was-never-merged this method exists to prevent.
     */
    suspend fun clearSyncFailureState() {
        mutex.withLock {
            _lastSyncError.value = null
            _autoSyncSuspended.value = false
            setSyncState(SYNC_STATE_CLOUD_FILE_REV, "")
            setSyncState(SYNC_STATE_LAST_UPLOADED_DIGEST, "")
        }
    }

    /**
     * Schedules a debounced cloud synchronization.
     */
    override fun scheduleSync() {
        if (cloudProvider() == null) return
        if (_autoSyncSuspended.value) return
        syncSignals.trySend(Unit)
    }

    /**
     * Synchronizes the local database with the cloud provider.
     *
     * @param trigger Who is asking. [SyncTrigger.AUTOMATIC] is skipped (returning [Result.Ok]) while
     * [autoSyncSuspended] is true, so a known-unusable cloud DB does not get re-downloaded and
     * re-merged on every debounced write or background cycle; [SyncTrigger.MANUAL] (the default)
     * always runs, so a person who explicitly asked for a sync always gets a real attempt.
     * @return The synchronization result.
     */
    suspend fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL): Result<Unit> {
        if (trigger == SyncTrigger.AUTOMATIC && _autoSyncSuspended.value) {
            Log.info(TAG, "Automatic sync skipped: cloud data is unusable until it is reset")
            return Result.Ok(Unit)
        }
        // The gate and the failure text are written inside the lock, not after it: they are two of
        // the four fields clearSyncFailureState() clears, and it takes the same lock so a
        // disconnect can never be undone by a sync that was already in flight.
        return activityCenter.trackSync {
            mutex.withLock {
                val result = syncLocked()
                updateAutoSyncGate(result)
                emitErrorNotification(result)
                result
            }
        }
    }

    /**
     * Pauses automatic syncing once a sync fails with [CloudDataIncompatibleException], and
     * resumes it the moment a sync (or a reset) succeeds. [SchemaVersionException] is deliberately
     * left out: it is equally permanent, but its fix is "update the app", and silencing background
     * syncing would hide the moment a newly-installed version starts working again.
     */
    private fun updateAutoSyncGate(result: Result<Unit>) {
        when {
            result is Result.Err && result.exception is CloudDataIncompatibleException ->
                _autoSyncSuspended.value = true
            result is Result.Ok -> _autoSyncSuspended.value = false
        }
    }

    /**
     * Archives the current cloud database under a timestamped name and replaces it with a fresh
     * snapshot of the local database — the recovery path for a cloud DB this app cannot use.
     *
     * The old file is renamed rather than deleted so a mistaken reset (or a merge bug that only
     * *looked* like corruption) is still recoverable by hand from the provider's app folder.
     * Archives are never pruned automatically.
     *
     * @return A successful result when the cloud database is recreated; otherwise, the archive or
     * creation failure.
     */
    suspend fun resetCloudData(): Result<Unit> {
        val cloud = cloudProvider() ?: return Result.Ok(Unit)
        // Gate and failure text written inside the lock, same reason as in sync().
        return activityCenter.trackSync {
            mutex.withLock {
                val result = when (val archived = archiveCloudDb(cloud)) {
                    is Result.Err -> archived
                    // The old file is out of the way (archived or deleted), so re-create it.
                    is Result.Ok -> createFresh(cloud)
                }
                updateAutoSyncGate(result)
                emitErrorNotification(result)
                result
            }
        }
    }

    /**
     * Moves the cloud DB aside so [createFresh] can recreate it.
     *
     * Only ever targets [CLOUD_DB_GZ_PATH] — the legacy [CLOUD_DB_PATH] is a read-only fallback
     * and is deliberately never touched by a reset, so it stays available as a rollback path.
     * [createFresh] then writes a fresh, valid [CLOUD_DB_GZ_PATH], which self-heals even a reset
     * whose real cause was corruption in the legacy file: the next sync finds the fresh compressed
     * file and never looks at the legacy one again.
     *
     * Falls back to deleting it when the rename fails for a storage reason (an occupied archive
     * name, a provider that rejected the move): reset is the only way out of an unusable cloud DB,
     * so it must never become impossible. An auth failure is not retried as a delete — the same
     * missing credentials would fail it identically, so the error is surfaced as-is.
     */
    private suspend fun archiveCloudDb(cloud: CloudStorage): Result<Unit> {
        val backupPath = cloudBackupPath(clock.nowMillis())
        return when (val renamed = cloud.rename(CLOUD_DB_GZ_PATH, backupPath)) {
            is Result.Ok -> Result.Ok(Unit)
            is Result.Err -> {
                if (renamed.exception is CloudAuthException) {
                    Log.error(TAG, "Reset: archive failed (not authenticated)")
                    return renamed
                }
                Log.warn(TAG, "Reset: archive to $backupPath failed (${renamed.exception.message}); deleting instead")
                when (val deleted = cloud.delete(CLOUD_DB_GZ_PATH)) {
                    is Result.Ok -> Result.Ok(Unit)
                    is Result.Err -> {
                        Log.error(TAG, "Reset: delete fallback failed: ${deleted.exception.message}")
                        deleted
                    }
                }
            }
        }
    }

    /**
     * Publishes a notification for sync failures that require user attention, and mirrors the same
     * failure into [lastSyncError] so the settings screen can show why sync is currently broken even
     * after the notification is dismissed.
     *
     * Sync conflicts are excluded because they are handled internally (and so leave [lastSyncError]
     * as it was — they are not a user-visible failure either way).
     *
     * @param result The outcome of the sync operation.
     */
    private suspend fun emitErrorNotification(result: Result<Unit>) {
        if (result is Result.Err && result.exception !is SyncConflictException) {
            val message = notificationMessages.syncFailed(result.exception)
            _lastSyncError.value = message
            notificationCenter.addCoalescing(
                AppNotification(
                    id = IdGenerator.newId(),
                    level = AppNotificationLevel.ERROR,
                    message = message,
                    timestampMillis = clock.nowMillis(),
                    action = nextActionFor(result.exception),
                ),
            )
        } else if (result is Result.Ok) {
            // Sync works again — drop the stale reason (a SyncConflictException, handled internally,
            // deliberately falls through both branches and changes nothing).
            _lastSyncError.value = null
        }
    }

    /**
     * Selects the user action associated with a sync failure.
     *
     * @return The action for resolving the failure.
     */
    private fun nextActionFor(exception: KeryxException): AppNotificationAction = when (exception) {
        is CloudDataIncompatibleException -> AppNotificationAction.ResetCloudData
        // Tab ids as declared in ui/settings/SettingsDialog.kt.
        is SchemaVersionException -> AppNotificationAction.ShowSettingsTab("updates")
        else -> AppNotificationAction.ShowSettingsTab("cloud_sync")
    }

    /**
     * Synchronizes the local database with the cloud database.
     *
     * Creates the cloud database when it does not exist, or merges and uploads changes
     * using revision checks to handle concurrent updates.
     *
     * @return A successful result when synchronization completes, or an error result
     * when synchronization fails.
     */
    private suspend fun syncLocked(): Result<Unit> {
        val cloud = cloudProvider() ?: run {
            Log.info(TAG, "Sync skipped: no cloud provider connected")
            return Result.Ok(Unit)
        }

        repeat(SYNC_MAX_RETRY) {
            // The compressed file is authoritative. Its metadata request answers both "does it
            // exist" and "is it still the revision we last merged" in one round trip, same as
            // before compression — every provider already returns the revision from this same
            // request. The legacy CLOUD_DB_PATH is consulted only when this comes back absent; see
            // "Compressed Upload / Legacy Fallback" in sync-architecture.md.
            val gzMeta = when (val meta = cloud.metadata(CLOUD_DB_GZ_PATH)) {
                is Result.Err -> {
                    Log.error(TAG, "Sync: metadata() failed: ${meta.exception.message}")
                    return meta
                }
                is Result.Ok -> meta.value
            }

            // `mergedRemote` records whether a download+merge happened this cycle, because a merge
            // can leave the local DB holding rows the cloud lacks (anything local that won
            // last-write-wins), which must still be uploaded even if nothing else changed locally.
            // `expectedRev` is what the upload guards on — `null` means "no compressed file exists
            // remotely yet", which selects create-only below instead of a rev-guarded update.
            val mergedRemote: Boolean
            val expectedRev: String?
            if (gzMeta != null) {
                val changed = gzMeta.rev != getSyncState(SYNC_STATE_CLOUD_FILE_REV)
                if (changed) {
                    when (val merged = downloadAndMerge(cloud, CLOUD_DB_GZ_PATH, compressed = true)) {
                        is Result.Err -> return merged
                        is Result.Ok -> setSyncState(SYNC_STATE_CLOUD_FILE_REV, merged.value.rev)
                    }
                }
                mergedRemote = changed
                expectedRev = gzMeta.rev
            } else {
                val legacyMeta = when (val meta = cloud.metadata(CLOUD_DB_PATH)) {
                    is Result.Err -> {
                        Log.error(TAG, "Sync: legacy metadata() failed: ${meta.exception.message}")
                        return meta
                    }
                    is Result.Ok -> meta.value
                }
                if (legacyMeta == null) {
                    // Nothing in the cloud at all: first sync ever. If the file actually already
                    // exists (a wrong "absent" reading, a scope/account mismatch, or a concurrent
                    // creator), `createFresh` returns SyncConflictException and we retry — the next
                    // attempt finds it and takes the gzMeta-present branch above instead of
                    // clobbering the other device's data.
                    when (val created = createFresh(cloud)) {
                        is Result.Ok -> return Result.Ok(Unit)
                        is Result.Err -> {
                            if (created.exception !is SyncConflictException) return created
                            return@repeat
                        }
                    }
                }
                // Legacy-only cloud: a device (this one, or another) has not written the
                // compressed file yet. Migrate by merging the legacy content once, then always
                // write a fresh compressed file below — there is no compressed revision or digest
                // yet to compare against, so the upload-skip check below is bypassed by
                // `mergedRemote = true`, and `expectedRev = null` selects create-only (guarding
                // against a device racing to migrate at the same time).
                when (val merged = downloadAndMerge(cloud, CLOUD_DB_PATH, compressed = false)) {
                    is Result.Err -> return merged
                    is Result.Ok -> Unit // its revision belongs to the legacy file; nothing to store
                }
                mergedRemote = true
                expectedRev = null
            }

            // Both temp files stay files for their whole life: hashed and compressed from disk,
            // streamed to the cloud from disk, deleted afterwards. The snapshot is the largest
            // thing this app handles, and nothing here needs it as a contiguous array.
            val digest = when (val prepared = prepareCompressedUpload()) {
                is Result.Err -> return prepared
                is Result.Ok -> prepared.value
            }
            try {
                // Skip the upload when the snapshot is byte-identical to the one already in the
                // cloud. The comparison is over the *uncompressed* snapshot's own content
                // (`sync_state` is excluded from it, so it does not drift on its own, and gzip's
                // own output is not byte-stable across otherwise-identical input) — see "Skipping
                // Unchanged Transfers" in sync-architecture.md. Comparing content is what makes
                // skipping safe: a local change cannot hash to the previous digest, so no edit is
                // ever silently dropped. The opposite misjudgement only costs an upload that would
                // have happened anyway — which is also what an unreadable snapshot (null digest) gets.
                if (!mergedRemote && digest != null && digest == getSyncState(SYNC_STATE_LAST_UPLOADED_DIGEST)) {
                    Log.info(TAG, "Sync: nothing changed locally or remotely; skipping transfer")
                    setSyncState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                    return Result.Ok(Unit)
                }

                val upload = if (expectedRev == null) {
                    cloud.create(CLOUD_DB_GZ_PATH, snapshotGzPath)
                } else {
                    cloud.upload(CLOUD_DB_GZ_PATH, snapshotGzPath, expectedRev = expectedRev)
                }
                when (upload) {
                    is Result.Ok -> {
                        // Record the revision this write produced, so the next sync recognises its
                        // own upload and skips downloading it back. It comes from the write's own
                        // response (never a follow-up metadata call, which could return another
                        // device's newer write and make the next sync skip a download it never
                        // merged).
                        setSyncState(SYNC_STATE_CLOUD_FILE_REV, upload.value.rev)
                        if (digest != null) setSyncState(SYNC_STATE_LAST_UPLOADED_DIGEST, digest)
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
            } finally {
                FileIO.delete(snapshotPath)
                FileIO.delete(snapshotGzPath)
            }
        }
        Log.warn(TAG, "Sync failed after $SYNC_MAX_RETRY retries (rev conflict never resolved)")
        return Result.Err(CloudStorageException("Sync failed after $SYNC_MAX_RETRY retries"))
    }

    /**
     * Creates the initial cloud database without overwriting an existing file.
     *
     * @return A successful result when the database is created, or an error result when creation fails,
     * including a conflict if the cloud file already exists.
     */
    private suspend fun createFresh(cloud: CloudStorage): Result<Unit> {
        val digest = when (val prepared = prepareCompressedUpload()) {
            is Result.Err -> return prepared
            is Result.Ok -> prepared.value
        }
        return try {
            when (val r = cloud.create(CLOUD_DB_GZ_PATH, snapshotGzPath)) {
                is Result.Ok -> {
                    // Same bookkeeping as a successful upload: the file we just created is, by
                    // definition, already merged here, so the next sync can skip downloading it.
                    setSyncState(SYNC_STATE_CLOUD_FILE_REV, r.value.rev)
                    if (digest != null) setSyncState(SYNC_STATE_LAST_UPLOADED_DIGEST, digest)
                    setSyncState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                    Result.Ok(Unit)
                }
                is Result.Err -> r
            }
        } finally {
            FileIO.delete(snapshotPath)
            FileIO.delete(snapshotGzPath)
        }
    }

    /**
     * Streams [path] to a temp file and merges it, returning the revision that was downloaded.
     *
     * Owns the temp file's whole lifetime (both of them, when [compressed]), so a failed download,
     * a failed decompression, or a failed merge cannot leave a partial database behind for the
     * next cycle to trip over.
     *
     * @param compressed Whether [path] is gzip-compressed ([works.merc.keryx.app.core.CLOUD_DB_GZ_PATH])
     * and must be decompressed before the merger opens it, or the legacy plain-SQLite fallback
     * ([works.merc.keryx.app.core.CLOUD_DB_PATH]).
     */
    private suspend fun downloadAndMerge(cloud: CloudStorage, path: String, compressed: Boolean): Result<CloudFileMeta> {
        val downloadPath = if (compressed) cloudTempGzPath else cloudTempPath
        try {
            val meta = when (val d = cloud.download(path, downloadPath)) {
                is Result.Ok -> d.value
                is Result.Err -> {
                    Log.error(TAG, "Sync: download failed: ${d.exception.message}")
                    return d
                }
            }
            val mergeTarget = if (compressed) {
                try {
                    Gzip.decompressFile(downloadPath, cloudTempPath, MAX_SYNC_DB_SIZE_BYTES)
                } catch (e: Exception) {
                    // Symmetric with mergeCloud's own SQLite-header check below: a foreign/corrupt
                    // cloud file is the cloud's fault, not a transient/app-side failure, so it is
                    // classified immediately rather than reaching the merger with a decompressed
                    // garbage file.
                    Log.warn(TAG, "Cloud DB is not a valid gzip file: ${e.message}")
                    return Result.Err(CloudDataIncompatibleException("Cloud DB is not a valid gzip file"))
                }
                cloudTempPath
            } else {
                downloadPath
            }
            return when (val merged = mergeCloud(mergeTarget)) {
                is Result.Ok -> Result.Ok(meta)
                is Result.Err -> merged
            }
        } finally {
            FileIO.delete(downloadPath)
            if (compressed) FileIO.delete(cloudTempPath)
        }
    }

    /**
     * Merges downloaded cloud database data into the local database and updates affected search and query listeners.
     *
     * @param cloudDbPath The path to the downloaded cloud database file.
     * @return A successful result when the merge completes, or an error describing why it failed.
     * @throws CancellationException If the coroutine is cancelled during the merge.
     */
    private suspend fun mergeCloud(cloudDbPath: String): Result<Unit> {
        // Symmetric with prepareSnapshot(): reject a payload that is definitely not a SQLite file
        // before the merger opens it. A 0-byte file is a *valid* empty SQLite DB as far as the
        // engine is concerned, so it would otherwise attach cleanly and only fail deep inside the
        // merge statements with an ambiguous "no such table: cloud.folders" — this catches the
        // cheaper, unambiguous case (truncated download, HTML error page, wrong file) up front.
        // Unlike the upload-side check (CloudStorageException, a local guard against sending bad
        // data), this is CloudDataIncompatibleException: the cloud itself is what's broken here,
        // so the user is offered the reset action. Reads only the file's first 16 bytes — the
        // payload is deliberately never loaded whole.
        if (!looksLikeSqliteFile(cloudDbPath)) {
            Log.warn(TAG, "Cloud DB is not a SQLite file")
            return Result.Err(CloudDataIncompatibleException("Cloud DB is not a SQLite file"))
        }
        try {
            DatabaseMerger.merge(
                localDbPath = localDbPath,
                cloudDbPath = cloudDbPath,
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
        } catch (e: CancellationException) {
            // `indexMissing()` suspends on the FTS index-writer mutex inside this `try`, so the
            // catch-all below would otherwise turn a cancellation into a "merge failed" result —
            // and swallow it after the merge had already committed, skipping the notifyListeners
            // that surfaces those writes to the SQLDelight query flows.
            throw e
        } catch (e: Throwable) {
            // DatabaseMerger.merge() already classifies a permanently-unusable cloud DB as
            // CloudDataIncompatibleException (a KeryxException, caught above) using SQLite's error
            // code. Anything reaching this catch-all — including a post-commit failure in
            // indexMissing()/notifyListeners(), which run after the merge has already succeeded —
            // is therefore never the cloud's fault, and must not offer the destructive reset action.
            Log.error(TAG, "Cloud DB merge failed", e)
            return Result.Err(CloudStorageException("Merge failed: ${e.message}"))
        }
    }

    /**
     * Writes the file to upload at [snapshotPath]: an FTS-free, consistent snapshot of the local
     * DB. The live DB and its `articles_fts` index are untouched (see [DatabaseSnapshot]), so a
     * concurrent search never sees a missing index.
     *
     * Returns [Result.Err] rather than leaving a partial/empty payload for the caller to send: an
     * export failure or a truncated / non-SQLite snapshot must never be uploaded over good cloud
     * data. Deletes the file itself on failure; on success the caller owns it (and deletes it once
     * the upload is done or skipped), because the upload streams straight from it.
     */
    private fun prepareSnapshot(): Result<Unit> = try {
        DatabaseSnapshot.exportForUpload(localDbPath, snapshotPath)
        if (looksLikeSqliteFile(snapshotPath)) {
            Result.Ok(Unit)
        } else {
            FileIO.delete(snapshotPath)
            Result.Err(CloudStorageException("Snapshot missing or not a valid SQLite file"))
        }
    } catch (e: Throwable) {
        Log.error(TAG, "Snapshot export failed", e)
        FileIO.delete(snapshotPath)
        Result.Err(CloudStorageException("Snapshot export failed: ${e.message}"))
    }

    /**
     * Builds the file to upload: [prepareSnapshot]'s plain export, gzip-compressed. Returns the
     * plain snapshot's own content digest — the upload-skip check compares on the *uncompressed*
     * content (see [syncLocked]) — or `null` when it could not be hashed.
     *
     * On success the caller owns both [snapshotPath] and [snapshotGzPath] and deletes them once
     * the upload is done or skipped, because the upload streams straight from the compressed file.
     * Both are already cleaned up on failure.
     */
    private fun prepareCompressedUpload(): Result<String?> {
        when (val prepared = prepareSnapshot()) {
            is Result.Err -> return prepared
            is Result.Ok -> Unit
        }
        return try {
            val digest = ContentDigest.sha256File(snapshotPath)
            Gzip.compressFile(snapshotPath, snapshotGzPath)
            Result.Ok(digest)
        } catch (e: Throwable) {
            Log.error(TAG, "Snapshot compression failed", e)
            FileIO.delete(snapshotPath)
            FileIO.delete(snapshotGzPath)
            Result.Err(CloudStorageException("Snapshot compression failed: ${e.message}"))
        }
    }

    /** Temp file the (plain, legacy) cloud DB is streamed into before the merge attaches it. */
    private val cloudTempPath: String get() = FileIO.join(tempDir, "cloud_keryx.db")

    /** Temp file the compressed cloud DB is streamed into before being decompressed to [cloudTempPath]. */
    private val cloudTempGzPath: String get() = FileIO.join(tempDir, "cloud_keryx.db.gz")

    /** Temp file the upload snapshot is written to (plain) before being compressed to [snapshotGzPath]. */
    private val snapshotPath: String get() = FileIO.join(tempDir, "upload_keryx.db")

    /** Temp file the compressed upload snapshot is written to and streamed from. */
    private val snapshotGzPath: String get() = FileIO.join(tempDir, "upload_keryx.db.gz")

    private fun setSyncState(key: String, value: String) {
        db.sync_stateQueries.upsert(key, value)
    }

    private fun getSyncState(key: String): String? =
        db.sync_stateQueries.get(key).executeAsOneOrNull()

    private companion object {
        const val TAG = "Sync"
    }

    fun lastSyncedAt(): Long? =
        db.sync_stateQueries.get(SYNC_STATE_LAST_SYNCED_AT).executeAsOneOrNull()?.toLongOrNull()
}

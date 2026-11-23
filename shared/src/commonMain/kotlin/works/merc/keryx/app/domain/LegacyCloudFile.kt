package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import works.merc.keryx.app.core.CLOUD_DB_BACKUP_SUFFIX
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_STATE_KS_LEGACY_STUB_REV
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.core.cloudBackupPath
import works.merc.keryx.app.core.looksLikeSqliteFile
import works.merc.keryx.app.core.valueOrElse
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.DatabaseMerger
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.Gzip
import works.merc.keryx.app.platform.TransportFiles
import works.merc.keryx.app.platform.TransportValidation
import works.merc.keryx.app.platform.ValidationTarget

/**
 * The pre-delta `keryx.db.gz` — "Migration from the Pre-Delta Format" and the legacy watch of step 1
 * of "Sync Flow" in `docs/sync-architecture.md`. [DeltaSync] calls in only under
 * [SyncRepository]'s mutex.
 *
 * Every step is safe to interrupt, because each is either idempotent or decided again from the
 * cloud on the next sync: merging a full database twice changes nothing (the merge is idempotent),
 * the stub is placed only with a revision check against the version just merged, and until this
 * device has recorded a stub revision ([SYNC_STATE_KS_LEGACY_STUB_REV]) every sync looks at the file
 * again and finishes what is left — a full database is merged and stubbed, a stub is recorded.
 */
internal class LegacyCloudFile(
    private val db: KeryxDatabase,
    private val clock: Clock,
    private val localDbPath: String,
    private val tempDir: String,
    private val setPhase: (SyncPhase) -> Unit,
    /**
     * Runs the given merge (which writes through its own connection, its statements ending with
     * [MARK_INDEX_PENDING_SQL]) and the post-commit steps that follow every merge — SQLDelight's
     * listeners, then the search index (`search_text` recomputed first) — mapping failures to a
     * [Result] like a receive's merge.
     */
    private val mergeAndRefresh: suspend (merge: () -> Unit) -> Result<Unit>,
    private val testHook: () -> (suspend (SyncHookPoint, String) -> Unit)?,
) {
    /** A full pre-delta database downloaded and merged in this sync, still compressed at [gzPath]. */
    class Absorbed(val gzPath: String, val rev: String)

    /** What [absorb] found at `keryx.db.gz`. */
    sealed interface Found {
        /** Nothing there (any more). */
        data object Absent : Found

        /** The stub; its revision is now recorded. */
        data object Stub : Found

        /** A full database, merged into the local database. */
        class Full(val file: Absorbed) : Found
    }

    private var downloads = 0

    /** The stub revision this device placed or last saw, if any. */
    fun stubRev(): String? = db.sync_stateQueries.get(SYNC_STATE_KS_LEGACY_STUB_REV).executeAsOneOrNull()

    /**
     * Whether `keryx.db.gz` needs looking at: it exists with a revision other than [stubRev]. One
     * `metadata` request.
     */
    suspend fun changed(cloud: CloudStorage): Result<Boolean> {
        val meta = cloud.metadata(CLOUD_DB_GZ_PATH).valueOrElse {
            Log.error(TAG, "Sync: metadata of the pre-delta file failed: ${it.exception.message}")
            return it
        } ?: return Result.Ok(false)
        return Result.Ok(meta.rev != stubRev())
    }

    /**
     * Downloads `keryx.db.gz`, validates it ("Validating Cloud Files", the pre-delta target) and —
     * when it is a full database — merges it **with capture on**: no `sync_merging` row, so every
     * row the merge actually changes enters the outbox and reaches the other devices as a delta (a
     * joining device's full send covers them anyway). A stub's revision is recorded. A file failing
     * the gzip/header check is downloaded once more; failing again, it is corrupt.
     *
     * The returned [Found.Full]'s file is the caller's to delete ([discard]).
     */
    suspend fun absorb(cloud: CloudStorage): Result<Found> {
        val n = downloads++
        val gz = FileIO.join(tempDir, "ks-legacy-$n.db.gz")
        val plain = FileIO.join(tempDir, "ks-legacy-$n.db")
        var keepGz = false
        try {
            var problem = ""
            repeat(2) { attempt ->
                setPhase(SyncPhase.DOWNLOADING)
                val downloaded = cloud.download(CLOUD_DB_GZ_PATH, gz).valueOrElse { failure ->
                    val gone = cloud.metadata(CLOUD_DB_GZ_PATH).let { it is Result.Ok && it.value == null }
                    if (gone) return Result.Ok(Found.Absent)
                    Log.error(TAG, "Sync: download of the pre-delta file failed: ${failure.exception.message}")
                    return failure
                }
                setPhase(SyncPhase.MERGING)
                problem = try {
                    Gzip.decompressFile(gz, plain, MAX_SYNC_DB_SIZE_BYTES)
                    if (looksLikeSqliteFile(plain)) "" else "not a SQLite file"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "not a valid gzip file (${e.message})"
                }
                if (problem.isEmpty()) {
                    if (TransportFiles.isLegacyStub(plain)) {
                        db.sync_stateQueries.upsert(SYNC_STATE_KS_LEGACY_STUB_REV, downloaded.rev)
                        return Result.Ok(Found.Stub)
                    }
                    validate(plain)?.let { return it }
                    mergeAndRefresh {
                        DatabaseMerger.merge(
                            localDbPath = localDbPath,
                            cloudDbPath = plain,
                            localSchemaVersion = KeryxDatabase.Schema.version,
                            mergeStatements = MergeSql.all + MARK_INDEX_PENDING_SQL,
                        )
                    }.valueOrElse { return it }
                    Log.info(TAG, "Merged the pre-delta cloud database (rev ${downloaded.rev})")
                    testHook()?.invoke(SyncHookPoint.AFTER_LEGACY_MERGE, CLOUD_DB_GZ_PATH)
                    keepGz = true
                    return Result.Ok(Found.Full(Absorbed(gz, downloaded.rev)))
                }
                deleteDb(plain)
                Log.warn(TAG, "The pre-delta cloud file failed its integrity check (attempt ${attempt + 1}): $problem")
            }
            return Result.Err(CloudDataIncompatibleException("Cloud file is corrupt: $problem"))
        } finally {
            deleteDb(plain)
            if (!keepGz) FileIO.delete(gz)
        }
    }

    /** "Validating Cloud Files" checks 4–9 for a pre-delta database; `null` when it passes. */
    private fun validate(plain: String): Result.Err? =
        when (val verdict = TransportFiles.validate(plain, ValidationTarget.PreDelta(KeryxDatabase.Schema.version))) {
            TransportValidation.Valid -> null
            is TransportValidation.Corrupt -> {
                Log.warn(TAG, "The pre-delta cloud file is corrupt: ${verdict.reason}")
                Result.Err(CloudDataIncompatibleException("Cloud file is corrupt"))
            }
            is TransportValidation.Undetermined -> {
                Log.warn(TAG, "The pre-delta cloud file could not be validated: ${verdict.reason}")
                Result.Err(CloudStorageException("Cloud file could not be validated"))
            }
            is TransportValidation.TooNew ->
                Result.Err(SchemaVersionException(localVersion = KeryxDatabase.Schema.version, cloudVersion = verdict.version))
        }

    /**
     * Puts the stub in place of the full database [merged] (already merged, by this sync's [absorb]),
     * or — when [merged] is `null` — runs the legacy watch: nothing to do unless [changed]; otherwise
     * [absorb] it, and a full database is stubbed the same way.
     *
     * Before the first stub this device places or sees ([stubRev] absent), the full database is the
     * original pre-delta data, so it is archived first as `keryx-YYYYMMDD-HHMMSS.db.gz.bak` (`create`;
     * a name collision is ignored, any other failure stops here and the next sync tries again). This
     * covers a migration interrupted before its archive as well as the migration itself. A full
     * database found after a stub was known is an older build's later write, merged and not archived.
     *
     * The stub replaces the file only with a revision check against the version merged — never a
     * rename, which would let an older build's write between the check and the rename be archived
     * without being merged. On a mismatch the file is absorbed again (a stub ends it, a full
     * database is merged and the replacement retried), at most [SYNC_MAX_RETRY] times; running out
     * is a transient failure, retried by the next sync, as is a local failure preparing the stub
     * ([prepareStub]). The stub's revision is recorded only after
     * the upload succeeded, so a crash in between makes the next sync download the stub and record it.
     */
    suspend fun finish(cloud: CloudStorage, merged: Absorbed?): Result<Unit> {
        var current: Absorbed? = merged
        val stub = FileIO.join(tempDir, "ks-legacy-stub.db")
        val stubGz = "$stub.gz"
        try {
            if (current == null) {
                if (!changed(cloud).valueOrElse { return it }) return Result.Ok(Unit)
                current = when (val found = absorb(cloud).valueOrElse { return it }) {
                    Found.Absent, Found.Stub -> return Result.Ok(Unit)
                    is Found.Full -> found.file
                }
            }
            if (stubRev() == null) backup(cloud, current.gzPath).valueOrElse { return it }
            prepareStub(stub, stubGz).valueOrElse { return it }
            repeat(SYNC_MAX_RETRY) {
                val target = checkNotNull(current)
                setPhase(SyncPhase.ARCHIVING)
                testHook()?.invoke(SyncHookPoint.BEFORE_STUB_UPLOAD, CLOUD_DB_GZ_PATH)
                when (val written = cloud.upload(CLOUD_DB_GZ_PATH, stubGz, expectedRev = target.rev)) {
                    is Result.Ok -> {
                        testHook()?.invoke(SyncHookPoint.AFTER_STUB_UPLOAD, CLOUD_DB_GZ_PATH)
                        db.sync_stateQueries.upsert(SYNC_STATE_KS_LEGACY_STUB_REV, written.value.rev)
                        Log.info(TAG, "Placed the pre-delta stub (rev ${written.value.rev})")
                        return Result.Ok(Unit)
                    }
                    is Result.Err -> {
                        if (written.exception !is SyncConflictException) {
                            Log.error(TAG, "Sync: placing the pre-delta stub failed: ${written.exception.message}")
                            return written
                        }
                        Log.info(TAG, "Sync: the pre-delta file changed before the stub replaced it; absorbing it again")
                        discard(target)
                        current = null
                        current = when (val found = absorb(cloud).valueOrElse { return it }) {
                            Found.Absent, Found.Stub -> return Result.Ok(Unit)
                            is Found.Full -> found.file
                        }
                    }
                }
            }
            Log.warn(TAG, "Placing the pre-delta stub failed after $SYNC_MAX_RETRY attempts (the file kept changing)")
            return Result.Err(CloudStorageException("Placing the pre-delta stub failed after $SYNC_MAX_RETRY retries"))
        } finally {
            current?.let(::discard)
            deleteDb(stub)
            FileIO.delete(stubGz)
        }
    }

    /**
     * The reset's step for `keryx.db.gz` ("Resetting (Archiving) Cloud Data"), run once the new
     * generation's pointer is in place: nothing when the file is absent; a stub has its revision
     * recorded; anything else — a corrupt file, a full database, one that cannot be read — is archived
     * as it is (its raw, still-compressed bytes) as `keryx-YYYYMMDD-HHMMSS.db.gz.bak` and replaced by
     * the stub. Nothing is merged: a reset replaces the cloud by this device's state, and the archive
     * keeps the file recoverable.
     *
     * The stub replaces the file only with a revision check against the version just downloaded and
     * archived, so a version written in between is never discarded unarchived: a mismatch reads the
     * file again and starts over, at most [SYNC_MAX_RETRY] times, after which the reset fails
     * transiently. A download that fails (other than the file being gone) fails the reset the same way
     * rather than replacing a file it could not archive, and so does a local failure preparing the
     * stub ([prepareStub]). Each step is decided again from the cloud, so
     * a crash anywhere converges on the next reset (or, after the stub's upload, on the next sync's
     * legacy watch, which downloads the stub and records it).
     */
    suspend fun replaceOnReset(cloud: CloudStorage): Result<Unit> {
        val n = downloads++
        val gz = FileIO.join(tempDir, "ks-legacy-$n.db.gz")
        val plain = FileIO.join(tempDir, "ks-legacy-$n.db")
        val stub = FileIO.join(tempDir, "ks-legacy-stub.db")
        val stubGz = "$stub.gz"
        var stubReady = false
        try {
            repeat(SYNC_MAX_RETRY) {
                setPhase(SyncPhase.ARCHIVING)
                val meta = cloud.metadata(CLOUD_DB_GZ_PATH).valueOrElse {
                    Log.error(TAG, "Reset: metadata of the pre-delta file failed: ${it.exception.message}")
                    return it
                } ?: return Result.Ok(Unit)
                if (meta.rev == stubRev()) return Result.Ok(Unit)
                val downloaded = cloud.download(CLOUD_DB_GZ_PATH, gz).valueOrElse { failure ->
                    val gone = cloud.metadata(CLOUD_DB_GZ_PATH).let { it is Result.Ok && it.value == null }
                    if (gone) return Result.Ok(Unit)
                    Log.error(TAG, "Reset: download of the pre-delta file failed: ${failure.exception.message}")
                    return failure
                }
                if (isStubFile(gz, plain)) {
                    db.sync_stateQueries.upsert(SYNC_STATE_KS_LEGACY_STUB_REV, downloaded.rev)
                    return Result.Ok(Unit)
                }
                backup(cloud, gz).valueOrElse { return it }
                if (!stubReady) {
                    prepareStub(stub, stubGz).valueOrElse { return it }
                    stubReady = true
                }
                setPhase(SyncPhase.ARCHIVING)
                testHook()?.invoke(SyncHookPoint.BEFORE_STUB_UPLOAD, CLOUD_DB_GZ_PATH)
                when (val written = cloud.upload(CLOUD_DB_GZ_PATH, stubGz, expectedRev = downloaded.rev)) {
                    is Result.Ok -> {
                        testHook()?.invoke(SyncHookPoint.AFTER_STUB_UPLOAD, CLOUD_DB_GZ_PATH)
                        db.sync_stateQueries.upsert(SYNC_STATE_KS_LEGACY_STUB_REV, written.value.rev)
                        Log.info(TAG, "Reset: archived the unusable pre-delta file and placed the stub (rev ${written.value.rev})")
                        return Result.Ok(Unit)
                    }
                    is Result.Err -> if (written.exception !is SyncConflictException) {
                        Log.error(TAG, "Reset: placing the pre-delta stub failed: ${written.exception.message}")
                        return written
                    } else {
                        Log.info(TAG, "Reset: the pre-delta file changed before the stub replaced it; reading it again")
                    }
                }
                FileIO.delete(gz)
            }
            Log.warn(TAG, "Reset: placing the pre-delta stub failed after $SYNC_MAX_RETRY attempts (the file kept changing)")
            return Result.Err(CloudStorageException("Placing the pre-delta stub failed after $SYNC_MAX_RETRY retries"))
        } finally {
            FileIO.delete(gz)
            deleteDb(plain)
            deleteDb(stub)
            FileIO.delete(stubGz)
        }
    }

    /**
     * Writes the stub to [stub] and its compressed copy to [stubGz]. A failure — a local one, such as
     * a full disk — is never the cloud's fault and is a transient [CloudStorageException]: the cloud
     * file is not touched yet, so the next sync (or reset) tries again.
     */
    private fun prepareStub(stub: String, stubGz: String): Result<Unit> = try {
        TransportFiles.createLegacyStub(stub)
        Gzip.compressFile(stub, stubGz)
        Result.Ok(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.error(TAG, "Preparing the pre-delta stub failed", e)
        Result.Err(CloudStorageException("Preparing the pre-delta stub failed: ${e.message}"))
    }

    /**
     * Whether the downloaded [gz] holds the stub, decompressing it to [plain] (deleted again). Any
     * failure to decompress or read it — a corrupt file — is "not the stub".
     */
    private fun isStubFile(gz: String, plain: String): Boolean = try {
        Gzip.decompressFile(gz, plain, MAX_SYNC_DB_SIZE_BYTES)
        looksLikeSqliteFile(plain) && TransportFiles.isLegacyStub(plain)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    } finally {
        deleteDb(plain)
    }

    /** Archives the compressed pre-delta file at [gzPath] as `keryx-YYYYMMDD-HHMMSS.db.gz.bak`. */
    private suspend fun backup(cloud: CloudStorage, gzPath: String): Result<Unit> {
        setPhase(SyncPhase.ARCHIVING)
        val base = cloudBackupPath(clock.nowMillis())
        // An existing archive of the same second may hold *different* bytes (a retry after the file
        // changed under a revision conflict), so a collision never counts as done: the next free
        // name is taken instead, and a duplicate of identical bytes is merely harmless.
        for (attempt in 0 until BACKUP_NAME_ATTEMPTS) {
            val path = if (attempt == 0) base else base.removeSuffix(CLOUD_DB_BACKUP_SUFFIX) + "-$attempt$CLOUD_DB_BACKUP_SUFFIX"
            testHook()?.invoke(SyncHookPoint.BEFORE_LEGACY_BACKUP, path)
            when (val created = cloud.create(path, gzPath)) {
                is Result.Ok -> return Result.Ok(Unit)
                is Result.Err -> if (created.exception !is SyncConflictException) {
                    Log.error(TAG, "Sync: archiving the pre-delta file failed: ${created.exception.message}")
                    return created
                }
            }
        }
        return Result.Err(CloudStorageException("No free archive name for the pre-delta file"))
    }

    /** Deletes [file]'s local copy. */
    fun discard(file: Absorbed) = FileIO.delete(file.gzPath)

    private companion object {
        const val TAG = "Sync"

        /** Archive names tried per second before giving up (transiently) — far beyond any real retry count. */
        const val BACKUP_NAME_ATTEMPTS = 100

        fun deleteDb(path: String) {
            FileIO.delete(path)
            FileIO.delete("$path-journal")
        }
    }
}

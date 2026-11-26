package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.KS_COMPACT_MAX_DELTA_RATIO
import works.merc.keryx.app.core.KS_COMPACT_MIN_DELTAS
import works.merc.keryx.app.core.KS_DELTA_GRACE_MS
import works.merc.keryx.app.core.KS_RECEIVE_STALE_MARGIN_MS
import works.merc.keryx.app.core.KS_RESUME_MAX_AGE_MS
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_POINTER_MAX_BYTES
import works.merc.keryx.app.core.SYNC_POINTER_PATH
import works.merc.keryx.app.core.SYNC_STATE_INDEX_PENDING
import works.merc.keryx.app.core.SYNC_STATE_KS_ATTEMPT
import works.merc.keryx.app.core.SYNC_STATE_KS_BASES
import works.merc.keryx.app.core.SYNC_STATE_KS_DEVICE_ID
import works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION
import works.merc.keryx.app.core.SYNC_STATE_KS_LAST_SEQ
import works.merc.keryx.app.core.SYNC_STATE_KS_POINTER_REV
import works.merc.keryx.app.core.SYNC_STATE_KS_RECEIVED_AT
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.core.looksLikeSqliteFile
import works.merc.keryx.app.core.valueOrElse
import works.merc.keryx.app.data.cloud.CloudFileEntry
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.ContentDigest
import works.merc.keryx.app.platform.DatabaseMerger
import works.merc.keryx.app.platform.DeltaExporter
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.Gzip
import works.merc.keryx.app.platform.TransportFiles
import works.merc.keryx.app.platform.TransportValidation
import works.merc.keryx.app.platform.ValidationTarget

/** Points in a sync where a test can interrupt it (by throwing) or observe it — see [DeltaSync.testHook]. */
internal enum class SyncHookPoint {
    /** A send is recorded (`ks_attempt`) and its file saved; the upload is next. */
    BEFORE_SEND_UPLOAD,

    /** The send's file is in the cloud; the commit is next. */
    AFTER_SEND_UPLOAD,

    /** The send is committed; deleting its saved file is next. */
    AFTER_SEND_COMMIT,

    /** A new base is built; its upload is next. */
    BEFORE_BASE_UPLOAD,

    /** A garbage-collection deletion of the named file is next. */
    BEFORE_GC_DELETE,

    /** This device created the pointer; the rest of the sync is next. */
    AFTER_POINTER_CREATE,

    /** A full pre-delta database was merged; what follows it (joining, archiving, the stub) is next. */
    AFTER_LEGACY_MERGE,

    /** The pre-delta file's archive (the named `.bak`) is to be uploaded next — by a sync or a reset. */
    BEFORE_LEGACY_BACKUP,

    /** The stub is to replace the pre-delta file next — by a sync or a reset. */
    BEFORE_STUB_UPLOAD,

    /** The stub is in place; recording its revision is next. */
    AFTER_STUB_UPLOAD,
}

/**
 * The delta sync flow — "Sync Flow", "Receiving", "Sending", "Bases and Garbage Collection" and
 * "Joining, Reset, Disconnect" in `docs/sync-architecture.md`. [SyncRepository] owns the scheduling,
 * the gates, the mutex and the user-facing state, and calls into this class **only while holding
 * its mutex**, so nothing here runs concurrently with itself.
 *
 * Every local bookkeeping change that must happen together is one SQLDelight transaction (joining,
 * committing a send, disconnecting), or part of the merge's own transaction (what a receive merged),
 * so a crash at any point leaves either the state before or the state after. Cloud files are
 * immutable and named by their content's hash, so every upload is a plain `create` whose "already
 * exists" answer means the same file is there.
 */
internal class DeltaSync(
    private val driver: SqlDriver,
    private val db: KeryxDatabase,
    private val ftsManager: FtsManager,
    private val clock: Clock,
    private val localDbPath: String,
    private val tempDir: String,
    /** Where a send's file is saved until it is committed — durable storage, never a temp dir. */
    private val dataDir: String,
    private val newId: () -> String,
    /** Whether folding may run now ([NetworkUsagePolicy.compactionAllowed]). */
    private val compactionAllowed: suspend () -> Boolean,
    private val setPhase: (SyncPhase) -> Unit,
    /**
     * Republishes `ks_id_remap` to the ids held outside the database ([TagFolderIdRemaps.publishFromDb])
     * after a merge has committed — a merge may have moved tags or folders onto their canonical ids
     * ([V2IdentitySql]). Never called inside the merge's transaction.
     */
    private val publishIdRemaps: suspend () -> Unit = {},
) {
    /** Called at each [SyncHookPoint] with the file name concerned; for tests only. */
    var testHook: (suspend (SyncHookPoint, String) -> Unit)? = null

    /** The pre-delta `keryx.db.gz`: the migration from it and the legacy watch. */
    private val legacy = LegacyCloudFile(
        db = db,
        clock = clock,
        localDbPath = localDbPath,
        tempDir = tempDir,
        setPhase = setPhase,
        mergeAndRefresh = { merge -> mergeAndRefresh(merge) },
        testHook = { testHook },
    )

    private sealed interface Pointer {
        data object Absent : Pointer
        data class Present(val generation: String, val rev: String) : Pointer
    }

    /** The current generation's files, deduplicated by name (Drive can list a name twice). */
    private class Listing(val deltas: Map<TransportFileName, CloudFileEntry>, val bases: MutableMap<TransportFileName, CloudFileEntry>) {
        val deltaNames: MutableSet<TransportFileName> = deltas.keys.toMutableSet()
    }

    /** A full pre-delta database a sync attempt merged, until [LegacyCloudFile.finish] takes it over. */
    private class LegacyHandoff {
        var absorbed: LegacyCloudFile.Absorbed? = null
    }

    /** Validated files downloaded during one sync, by name, so a fold does not download them twice. */
    private class Downloads(private val tempDir: String) {
        private val paths = mutableMapOf<String, String>()
        private var counter = 0

        fun pathFor(name: TransportFileName): String? = paths[name.fileName]

        fun newPath(): String = FileIO.join(tempDir, "ks-in-${counter++}.db")

        fun record(name: TransportFileName, path: String) {
            paths[name.fileName] = path
        }

        fun deleteAll() {
            for (path in paths.values) deleteDb(path)
            paths.clear()
        }
    }

    // ---- Entry points ----

    /** One sync of [cloud] — steps 0–6 of "Sync Flow". */
    suspend fun sync(cloud: CloudStorage): Result<Unit> {
        repeat(SYNC_MAX_RETRY) {
            val handoff = LegacyHandoff()
            try {
                return syncAttempt(cloud, handoff) ?: return@repeat
            } finally {
                // Left over only when the attempt ended before legacy.finish took the file over.
                handoff.absorbed?.let(legacy::discard)
            }
        }
        Log.warn(TAG, "Sync failed after $SYNC_MAX_RETRY attempts (the pointer kept changing)")
        return Result.Err(CloudStorageException("Sync failed after $SYNC_MAX_RETRY retries"))
    }

    /**
     * One attempt of [sync]: its result, or `null` when the pointer changed under it (another device
     * created it first) and the attempt must start over.
     */
    private suspend fun syncAttempt(cloud: CloudStorage, handoff: LegacyHandoff): Result<Unit>? {
        setPhase(SyncPhase.CHECKING)
        val generation: String
        var joining = false
        when (val pointer = readPointer(cloud).valueOrElse { return it }) {
            Pointer.Absent -> {
                // No pointer but a pre-delta database ("Migration"): merge it first, so the full
                // send below — the first base, or an ordinary join's — carries its rows.
                if (legacy.changed(cloud).valueOrElse { return it }) {
                    val found = legacy.absorb(cloud).valueOrElse { return it }
                    if (found is LegacyCloudFile.Found.Full) handoff.absorbed = found.file
                }
                val own = ownGeneration()
                if (own != null && SendAttempt.decode(state(SYNC_STATE_KS_ATTEMPT))?.name?.generation == own) {
                    resumeAttempt(cloud, own).valueOrElse { return it }
                }
                if (own != null && lastSeq() >= 1) {
                    // This device already sent into its generation (as its first device, perhaps
                    // interrupted before creating the pointer): point at it.
                    when (val created = createPointer(cloud, own)) {
                        is Result.Err -> if (created.exception is SyncConflictException) return null else return created
                        is Result.Ok -> Unit
                    }
                    generation = own
                } else {
                    val fresh = newId()
                    join(fresh, pointerRev = null)
                    sendFull(cloud, fresh, exceptPath = null, asBase = true).valueOrElse { return it }
                    when (val created = createPointer(cloud, fresh)) {
                        // Another device created the pointer first: the next read sees its generation
                        // and this device rejoins there. The base just sent is never pointed at.
                        is Result.Err -> if (created.exception is SyncConflictException) return null else return created
                        is Result.Ok -> Unit
                    }
                    generation = fresh
                }
            }
            is Pointer.Present -> {
                generation = pointer.generation
                if (pointer.generation != ownGeneration()) {
                    join(pointer.generation, pointer.rev)
                    joining = true
                } else {
                    resumeAttempt(cloud, generation).valueOrElse { return it }
                }
            }
        }

        // The migration's archive and stub, or — every sync — the legacy watch ("Migration").
        val merged = handoff.absorbed
        handoff.absorbed = null
        legacy.finish(cloud, merged).valueOrElse { return it }

        val listing = list(cloud, generation).valueOrElse { return it }
        if (!joining && cloneDetectedInListing(listing)) {
            Log.warn(TAG, "Sync: another copy of this database writes as this device; rejoining")
            join(generation, state(SYNC_STATE_KS_POINTER_REV))
            joining = true
        }
        val downloads = Downloads(tempDir)
        var exceptPath: String? = null
        try {
            exceptPath = receive(cloud, listing, joining, downloads).valueOrElse { return it }
            if (!joining && cloneDetectedInBases(listing)) {
                Log.warn(TAG, "Sync: a base covers deltas this device never wrote; rejoining")
                exceptPath?.let(::deleteDb)
                join(generation, state(SYNC_STATE_KS_POINTER_REV))
                exceptPath = receive(cloud, listing, joining = true, downloads).valueOrElse { return it }
            }
            val sent = send(cloud, generation, exceptPath).valueOrElse { return it }
            exceptPath?.let(::deleteDb)
            exceptPath = null
            if (sent != null) listing.deltaNames += sent
            compact(cloud, generation, listing, downloads).valueOrElse { return it }
        } finally {
            exceptPath?.let(::deleteDb)
            downloads.deleteAll()
        }
        setState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
        return Result.Ok(Unit)
    }

    /**
     * `resetCloudData()`: a new generation whose base is this device's full state, then the pointer
     * replaced with a revision check. Losing that check to another device's reset aborts and syncs
     * into the generation it created instead. Once the pointer is in place, an unusable pre-delta
     * `keryx.db.gz` is archived and replaced by the stub ([LegacyCloudFile.replaceOnReset]), so the
     * legacy watch stops failing every sync on it.
     */
    suspend fun reset(cloud: CloudStorage): Result<Unit> {
        setPhase(SyncPhase.CHECKING)
        val current = cloud.metadata(SYNC_POINTER_PATH).valueOrElse { return it }
        val generation = newId()
        join(generation, pointerRev = null)
        sendFull(cloud, generation, exceptPath = null, asBase = true).valueOrElse { return it }
        setPhase(SyncPhase.UPLOADING)
        val written = writePointer(cloud, generation, expectedRev = current?.rev, createOnly = current == null)
        return when (written) {
            is Result.Ok -> {
                setState(SYNC_STATE_KS_POINTER_REV, written.value)
                legacy.replaceOnReset(cloud).valueOrElse { return it }
                setState(SYNC_STATE_LAST_SYNCED_AT, clock.nowMillis().toString())
                Result.Ok(Unit)
            }
            is Result.Err -> if (written.exception is SyncConflictException) {
                Log.warn(TAG, "Reset: another device reset the cloud data first; joining its generation")
                sync(cloud)
            } else {
                written
            }
        }
    }

    /**
     * Disconnect / switch: recording off, and everything tied to the connection gone — the `ks_*` keys,
     * the outbox, `sync_applied`, the cursors and every saved send file ([sweepPendingFiles] — including
     * one a commit left behind, which no record names any more). `ks_search_pending` stays (it belongs
     * to the local search index), and so do the feeds' raw folder assignments (`ks_feed_folder_raw`):
     * they are this device's data — the source of truth `feeds.folder_id` is resolved from — not a
     * record of the connection's files.
     */
    fun clearConnectionState() {
        db.transaction {
            db.sync_stateQueries.deleteConnectionKeys()
            db.sync_bookkeepingQueries.clearOutbox()
            db.sync_bookkeepingQueries.clearApplied()
            db.sync_bookkeepingQueries.clearCursors()
            db.sync_bookkeepingQueries.setCapture(enabled = 0, fullPending = 0)
        }
        sweepPendingFiles()
    }

    /** Whether there is anything to send: an outbox entry or a full send due, while recording. */
    fun hasPendingChanges(): Boolean {
        val capture = db.sync_bookkeepingQueries.captureState().executeAsOneOrNull() ?: return false
        if (capture.enabled != 1L) return false
        return capture.full_pending == 1L || db.sync_bookkeepingQueries.outboxNonEmpty().executeAsOne()
    }

    // ---- Step 1: the pointer ----

    private suspend fun readPointer(cloud: CloudStorage): Result<Pointer> {
        val meta = cloud.metadata(SYNC_POINTER_PATH).valueOrElse {
            Log.error(TAG, "Sync: pointer metadata failed: ${it.exception.message}")
            return it
        } ?: return Result.Ok(Pointer.Absent)
        val own = ownGeneration()
        if (own != null && meta.rev == state(SYNC_STATE_KS_POINTER_REV)) return Result.Ok(Pointer.Present(own, meta.rev))
        val path = FileIO.join(tempDir, "ks-pointer.json")
        try {
            val downloaded = cloud.download(SYNC_POINTER_PATH, path).valueOrElse { return it }
            val size = SystemFileSystem.metadataOrNull(Path(path))?.size ?: 0L
            if (size > SYNC_POINTER_MAX_BYTES) {
                return Result.Err(CloudDataIncompatibleException("Cloud sync pointer is unusable: too large"))
            }
            val generation = SyncPointer.parse(FileIO.readText(path).orEmpty()).valueOrElse { return it }
            if (generation == own) setState(SYNC_STATE_KS_POINTER_REV, downloaded.rev)
            return Result.Ok(Pointer.Present(generation, downloaded.rev))
        } finally {
            FileIO.delete(path)
        }
    }

    /** Creates the pointer for [generation]; a conflict means another device created one first. */
    private suspend fun createPointer(cloud: CloudStorage, generation: String): Result<Unit> {
        setPhase(SyncPhase.UPLOADING)
        val rev = writePointer(cloud, generation, expectedRev = null, createOnly = true).valueOrElse { return it }
        setState(SYNC_STATE_KS_POINTER_REV, rev)
        testHook?.invoke(SyncHookPoint.AFTER_POINTER_CREATE, SYNC_POINTER_PATH)
        return Result.Ok(Unit)
    }

    private suspend fun writePointer(cloud: CloudStorage, generation: String, expectedRev: String?, createOnly: Boolean): Result<String> {
        val path = FileIO.join(tempDir, "ks-pointer-out.json")
        try {
            FileIO.writeText(path, SyncPointer.encode(generation))
            val written = if (createOnly) cloud.create(SYNC_POINTER_PATH, path) else cloud.upload(SYNC_POINTER_PATH, path, expectedRev)
            return when (written) {
                is Result.Ok -> Result.Ok(written.value.rev)
                is Result.Err -> written
            }
        } finally {
            FileIO.delete(path)
        }
    }

    // ---- Joining and identities ----

    /**
     * Joins [generation] ("Joining"): a new sync identity, and — in the same transaction — the cursors,
     * `sync_applied`, the outbox, the base cache and a pending send cleared, recording on and a full send
     * due, so every write from then on is recorded and everything before it is in the full send. Every
     * saved send file goes afterwards ([sweepPendingFiles]), orphans included.
     */
    private fun join(generation: String, pointerRev: String?) {
        val device = newId()
        db.transaction {
            setState(SYNC_STATE_KS_DEVICE_ID, device)
            setState(SYNC_STATE_KS_GENERATION, generation)
            setState(SYNC_STATE_KS_LAST_SEQ, "0")
            if (pointerRev != null) setState(SYNC_STATE_KS_POINTER_REV, pointerRev) else deleteState(SYNC_STATE_KS_POINTER_REV)
            deleteState(SYNC_STATE_KS_ATTEMPT)
            deleteState(SYNC_STATE_KS_RECEIVED_AT)
            deleteState(SYNC_STATE_KS_BASES)
            db.sync_bookkeepingQueries.clearCursors()
            db.sync_bookkeepingQueries.clearApplied()
            db.sync_bookkeepingQueries.clearOutbox()
            db.sync_bookkeepingQueries.setCapture(enabled = 1, fullPending = 1)
        }
        sweepPendingFiles()
        Log.info(TAG, "Joined generation $generation as device $device")
    }

    /**
     * A new sync identity within the current generation, keeping the outbox (so the changes an
     * unverifiable or too old send covered are sent again, with a fresh creation time) — step 0 of
     * "Sync Flow".
     */
    private fun takeNewIdentity() {
        db.transaction {
            setState(SYNC_STATE_KS_DEVICE_ID, newId())
            setState(SYNC_STATE_KS_LAST_SEQ, "0")
            deleteState(SYNC_STATE_KS_ATTEMPT)
        }
        sweepPendingFiles()
    }

    // ---- Step 0: resuming a send ----

    private suspend fun resumeAttempt(cloud: CloudStorage, generation: String): Result<Unit> {
        val raw = state(SYNC_STATE_KS_ATTEMPT)
        if (raw == null) {
            // No send is being resumed, so every saved send file is left over: from a crash between a
            // commit and deleting its file, or from a record cleared before its file was deleted.
            sweepPendingFiles()
            return Result.Ok(Unit)
        }
        val attempt = SendAttempt.decode(raw) ?: run {
            // Unreadable (never expected): what it named may be in the cloud, so its number must not
            // be reused under this identity.
            Log.warn(TAG, "Sync: the recorded send is unreadable; taking a new sync identity")
            db.transaction {
                setState(SYNC_STATE_KS_DEVICE_ID, newId())
                setState(SYNC_STATE_KS_LAST_SEQ, "0")
                deleteState(SYNC_STATE_KS_ATTEMPT)
            }
            return Result.Ok(Unit)
        }
        val pending = pendingPath(attempt.number)
        if (attempt.name.generation != generation || attempt.name.device != state(SYNC_STATE_KS_DEVICE_ID)) {
            Log.warn(TAG, "Sync: dropping a send of another generation or identity")
            takeNewIdentity()
            return Result.Ok(Unit)
        }
        val age = clock.nowMillis() - attempt.name.createdAt
        if (age !in 0..KS_RESUME_MAX_AGE_MS) {
            // Uploaded now under its old creation time, it could be garbage-collected before a device
            // that syncs occasionally lists it; a send dated in the future proves nothing about its age.
            Log.warn(TAG, "Sync: the recorded send is too old to resume; taking a new sync identity")
            takeNewIdentity()
            return Result.Ok(Unit)
        }
        if (!pendingFileMatches(pending, attempt.name.hash12)) {
            Log.warn(TAG, "Sync: the saved send is missing or damaged; taking a new sync identity")
            takeNewIdentity()
            return Result.Ok(Unit)
        }
        setPhase(SyncPhase.UPLOADING)
        return uploadAndCommit(cloud, attempt, pending)
    }

    private fun pendingFileMatches(pending: String, hash12: String): Boolean {
        if (!FileIO.exists(pending)) return false
        val plain = FileIO.join(tempDir, "ks-verify.db")
        return try {
            Gzip.decompressFile(pending, plain, MAX_SYNC_DB_SIZE_BYTES)
            ContentDigest.sha256File(plain)?.lowercase()?.startsWith(hash12) == true
        } catch (e: Exception) {
            Log.warn(TAG, "Sync: the saved send cannot be read: ${e.message}")
            false
        } finally {
            deleteDb(plain)
        }
    }

    // ---- Step 2: listing and clones ----

    private suspend fun list(cloud: CloudStorage, generation: String): Result<Listing> {
        val entries = cloud.list(TransportFileName.prefixOf(generation)).valueOrElse {
            Log.error(TAG, "Sync: listing failed: ${it.exception.message}")
            return it
        }
        val deltas = mutableMapOf<TransportFileName, CloudFileEntry>()
        val bases = mutableMapOf<TransportFileName, CloudFileEntry>()
        for (entry in entries) {
            // Anything that does not match the pattern is not ours, and is ignored.
            val name = TransportFileName.parse(entry.name)?.takeIf { it.generation == generation } ?: continue
            val target = if (name.kind == TransportFileKind.DELTA) deltas else bases
            val existing = target[name]
            if (existing == null || entry.size > existing.size) target[name] = entry
        }
        return Result.Ok(Listing(deltas, bases))
    }

    /**
     * Step 2's rule: a delta carrying this device's identity that it did not write — a `seq <=
     * ks_last_seq` it never committed, or a `seq > ks_last_seq` that is not its pending send — means a
     * copy of this database writes as the same device. A delta of its own that a known base covers is
     * not counted (it may have been garbage-collected and still be listed for a while).
     */
    private fun cloneDetectedInListing(listing: Listing): Boolean {
        val self = state(SYNC_STATE_KS_DEVICE_ID) ?: return false
        val last = lastSeq()
        val attemptName = SendAttempt.decode(state(SYNC_STATE_KS_ATTEMPT))?.name
        val applied = appliedNames()
        val known = loadBases().values
        return listing.deltas.keys.any { name ->
            name.device == self && if (name.number <= last) {
                name.fileName !in applied && known.none { it.covers(name) }
            } else {
                name != attemptName
            }
        }
    }

    /** A listed base this device knows of that covers a delta of its own above `ks_last_seq`. */
    private fun cloneDetectedInBases(listing: Listing): Boolean {
        val self = state(SYNC_STATE_KS_DEVICE_ID) ?: return false
        val last = lastSeq()
        return loadBases().values.filter { b -> listing.bases.keys.any { it.fileName == b.name } }.any { base ->
            (base.vector[self] ?: 0L) > last ||
                base.covered.keys.mapNotNull(TransportFileName::parse).any { it.device == self && it.number > last }
        }
    }

    // ---- Step 3: receiving ----

    /**
     * "Receiving": decides what to fetch, validates and folds it into one temporary transport file,
     * and merges that once. Returns the folded file's path when anything was fetched (the caller keeps
     * it as a full send's except-file, then deletes it), or `null`.
     */
    private suspend fun receive(cloud: CloudStorage, listing: Listing, joining: Boolean, downloads: Downloads): Result<String?> {
        val self = checkNotNull(state(SYNC_STATE_KS_DEVICE_ID)) { "No sync identity" }
        val generation = checkNotNull(ownGeneration()) { "No generation" }
        val cache = loadBases().filterKeys { name -> listing.bases.keys.any { it.fileName == name } }.toMutableMap()
        val applied = appliedNames()
        val appliedSeqs = applied.mapNotNull(TransportFileName::parse).filter { it.kind == TransportFileKind.DELTA }
            .mapTo(mutableSetOf()) { it.device to it.number }
        val cursor = db.sync_bookkeepingQueries.cursorAll().executeAsList().associate { it.device to it.seq }
        val receivedAt = state(SYNC_STATE_KS_RECEIVED_AT)?.toLongOrNull()
        // A receive time in the future (the clock was set back since) proves nothing about what was seen.
        val sinceReceived = receivedAt?.let { clock.nowMillis() - it }
        val stale = joining || sinceReceived == null || sinceReceived < 0 || sinceReceived > KS_DELTA_GRACE_MS - KS_RECEIVE_STALE_MARGIN_MS

        // A delta a base names explicitly is applied only if its own name is in sync_applied: the cursor
        // proves only that *some* file of each (device, seq) was merged, and a clone's duplicate of a
        // seq below it is a different file whose changes may be nowhere else.
        fun lacks(base: BaseCoverage): Boolean =
            base.vector.any { (device, seq) ->
                val from = cursor[device] ?: 0L
                seq > from && ((from + 1)..seq).any { (device to it) !in appliedSeqs }
            } || base.covered.keys.mapNotNull(TransportFileName::parse).any { it.fileName !in applied }

        val fetchedBases = mutableListOf<BaseCoverage>()
        val accumulator = FileIO.join(tempDir, "ks-receive.db")
        var accumulatorCreated = false
        var succeeded = false

        // A listed file that is gone by the time it is downloaded (garbage-collected, and the listing
        // not caught up yet) counts as not listed: the cursor stops below it, and the next sync looks
        // again — exactly like a file the listing does not show yet.
        var vanished = false

        suspend fun fetchAndFold(name: TransportFileName): Result<BaseCoverage?> {
            val coverage = fetch(cloud, name, downloads).valueOrElse { return it } ?: run {
                vanished = true
                return Result.Ok(null)
            }
            if (!accumulatorCreated) {
                TransportFiles.createEmpty(accumulator, TransportMeta(generation, TransportFileKind.BASE, self, 1, clock.nowMillis()))
                accumulatorCreated = true
            }
            setPhase(SyncPhase.MERGING)
            TransportFiles.fold(accumulator, checkNotNull(downloads.pathFor(name)), name, extendVector = false)
            return Result.Ok(coverage)
        }

        try {
            // Bases first: what they cover decides which deltas are still needed.
            val firstBases = listing.bases.keys.filter { name ->
                val known = cache[name.fileName]
                if (known != null) lacks(known) else stale
            }
            for (name in firstBases.sortedBy { it.fileName }) {
                val coverage = fetchAndFold(name).valueOrElse { return it } ?: continue
                fetchedBases += coverage
                cache[coverage.name] = coverage
            }

            fun deltasToFetch() = listing.deltas.keys.filter { name ->
                name.fileName !in applied && fetchedBases.none { it.covers(name) }
            }

            fun gaps(toFetch: List<TransportFileName>): Boolean {
                val listedSeqs = listing.deltas.keys.mapTo(mutableSetOf()) { it.device to it.number }
                val maxSeq = listing.deltas.keys.groupBy { it.device }.mapValues { (_, names) -> names.maxOf { it.number } }
                return maxSeq.any { (device, top) ->
                    val from = cursor[device] ?: 0L
                    from < top && ((from + 1)..top).any { seq ->
                        (device to seq) !in appliedSeqs && (device to seq) !in listedSeqs &&
                            fetchedBases.none { it.coversSeq(device, seq) } && toFetch.none { it.device == device && it.number == seq }
                    }
                }
            }

            var toFetch = deltasToFetch()
            if (gaps(toFetch)) {
                // A delta was garbage-collected: a base this device has not looked at may cover it.
                val unknown = listing.bases.keys.filter { name -> name.fileName !in cache && fetchedBases.none { it.name == name.fileName } }
                for (name in unknown.sortedBy { it.fileName }) {
                    val coverage = fetchAndFold(name).valueOrElse { return it } ?: continue
                    fetchedBases += coverage
                    cache[coverage.name] = coverage
                }
                toFetch = deltasToFetch()
            }
            val gapsRemain = gaps(toFetch)
            val fetchedDeltas = mutableListOf<TransportFileName>()
            for (name in toFetch.sortedBy { it.fileName }) {
                if (fetchAndFold(name).valueOrElse { return it } != null) fetchedDeltas += name
            }

            if (accumulatorCreated) {
                // A merged base's explicitly covered names are recorded too, listed or not (garbage-
                // collected meanwhile), so `lacks` does not fetch that base again for them.
                val merged = fetchedBases.map { it.name } + fetchedDeltas.map { it.fileName } +
                    listing.deltas.keys.filter { name -> fetchedBases.any { it.covers(name) } }.map { it.fileName } +
                    fetchedBases.flatMap { it.covered.keys }.filter { TransportFileName.parse(it) != null }
                val mergedSeqs = merged.mapNotNull(TransportFileName::parse).filter { it.kind == TransportFileKind.DELTA }
                    .mapTo(mutableSetOf()) { it.device to it.number }
                val devices = cursor.keys + fetchedBases.flatMap { it.vector.keys } + mergedSeqs.map { it.first }
                val cursorUpdates = devices.associateWith { device ->
                    var seq = maxOf(cursor[device] ?: 0L, fetchedBases.maxOfOrNull { it.vector[device] ?: 0L } ?: 0L)
                    while ((device to seq + 1).let { it in appliedSeqs || it in mergedSeqs } ||
                        fetchedBases.any { it.coversSeq(device, seq + 1) }
                    ) {
                        seq++
                    }
                    seq
                }.filter { (device, seq) -> seq > (cursor[device] ?: 0L) }
                mergeFolded(accumulator, merged, cursorUpdates).valueOrElse { return it }
            } else if (state(SYNC_STATE_INDEX_PENDING) != null) {
                // An earlier merge committed but its indexing failed: retry it now.
                finishIndexing().valueOrElse { return it }
            }
            dropUnlisted(listing, self, cache.values)
            saveBases(cache.values)
            // Only once every base is accounted for: a joining device whose listing does not show the
            // generation's base yet keeps looking for it ("stale") until it does.
            val sawBase = listing.bases.keys.any { it.fileName in cache }
            if (!gapsRemain && !vanished && sawBase) setState(SYNC_STATE_KS_RECEIVED_AT, clock.nowMillis().toString())
            succeeded = true
            return Result.Ok(if (accumulatorCreated) accumulator else null)
        } finally {
            if (!succeeded && accumulatorCreated) deleteDb(accumulator)
        }
    }

    /**
     * Downloads and validates [name] ("Validating Cloud Files") into a file [downloads] records, and
     * returns its coverage — or `null` when the download failed because the file no longer exists
     * (`metadata` finds nothing). A file failing check 3 is downloaded once more; failing again, it is
     * corrupt.
     */
    private suspend fun fetch(cloud: CloudStorage, name: TransportFileName, downloads: Downloads): Result<BaseCoverage?> {
        downloads.pathFor(name)?.let { return Result.Ok(TransportFiles.readCoverage(it, name)) }
        val plain = downloads.newPath()
        val gz = "$plain.gz"
        var problem = ""
        repeat(2) { attempt ->
            setPhase(SyncPhase.DOWNLOADING)
            val downloaded = cloud.download("/${name.fileName}", gz)
            if (downloaded is Result.Err) {
                FileIO.delete(gz)
                val gone = cloud.metadata("/${name.fileName}").let { it is Result.Ok && it.value == null }
                if (gone) {
                    Log.info(TAG, "Sync: $name is listed but no longer exists")
                    return Result.Ok(null)
                }
                Log.error(TAG, "Sync: download of $name failed: ${downloaded.exception.message}")
                return downloaded
            }
            setPhase(SyncPhase.MERGING)
            problem = try {
                Gzip.decompressFile(gz, plain, MAX_SYNC_DB_SIZE_BYTES)
                when {
                    !looksLikeSqliteFile(plain) -> "not a SQLite file"
                    ContentDigest.sha256File(plain)?.lowercase()?.startsWith(name.hash12) != true -> "hash mismatch"
                    else -> ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "not a valid gzip file (${e.message})"
            } finally {
                FileIO.delete(gz)
            }
            if (problem.isEmpty()) {
                return when (val verdict = TransportFiles.validate(plain, ValidationTarget.Transport(name))) {
                    TransportValidation.Valid -> {
                        downloads.record(name, plain)
                        Result.Ok(TransportFiles.readCoverage(plain, name))
                    }
                    is TransportValidation.Corrupt -> {
                        deleteDb(plain)
                        Log.warn(TAG, "Cloud file $name is corrupt: ${verdict.reason}")
                        Result.Err(CloudDataIncompatibleException("Cloud file is corrupt"))
                    }
                    is TransportValidation.Undetermined -> {
                        deleteDb(plain)
                        Log.warn(TAG, "Cloud file $name could not be validated: ${verdict.reason}")
                        Result.Err(CloudStorageException("Cloud file could not be validated"))
                    }
                    is TransportValidation.TooNew -> {
                        deleteDb(plain)
                        Result.Err(SchemaVersionException(localVersion = TransportSchema.VERSION, cloudVersion = verdict.version))
                    }
                }
            }
            deleteDb(plain)
            Log.warn(TAG, "Cloud file $name failed its integrity check (attempt ${attempt + 1}): $problem")
        }
        return Result.Err(CloudDataIncompatibleException("Cloud file is corrupt: $problem"))
    }

    /**
     * Step 4 of "Receiving": one [DatabaseMerger.mergeTransport] transaction that marks itself as a
     * merge (so the capture triggers record nothing), merges, records what it merged and marks the
     * search index as behind ([SYNC_STATE_INDEX_PENDING]) — then the post-commit steps (step 5).
     */
    private suspend fun mergeFolded(accumulator: String, merged: List<String>, cursorUpdates: Map<String, Long>): Result<Unit> {
        val statements = buildList {
            add("INSERT INTO main.sync_merging (flag) VALUES (1)")
            addAll(MergeSql.transport)
            for (name in merged.distinct()) {
                // Only names that parsed (lowercase hex, digits, '-' and '.') reach SQL.
                checkNotNull(TransportFileName.parse(name)) { "Malformed name" }
                add("INSERT OR IGNORE INTO main.sync_applied (name) VALUES ('$name')")
            }
            for ((device, seq) in cursorUpdates) {
                check(TransportFileName.isSyncId(device)) { "Malformed device" }
                add(
                    "INSERT INTO main.sync_cursor (device, seq) VALUES ('$device', $seq) " +
                        "ON CONFLICT(device) DO UPDATE SET seq = MAX(seq, excluded.seq)",
                )
            }
            add(MARK_INDEX_PENDING_SQL)
            add("DELETE FROM main.sync_merging")
        }
        return mergeAndRefresh {
            DatabaseMerger.mergeTransport(localDbPath = localDbPath, transportPath = accumulator, mergeStatements = statements)
        }
    }

    /**
     * Runs [merge] (a [DatabaseMerger] call, which writes through its own connection and whose
     * statements end with [MARK_INDEX_PENDING_SQL]) and the steps after its commit (step 5):
     * SQLDelight's listeners, then [finishIndexing]. A [KeryxException] (an unusable or too-new cloud
     * file, classified by the merger) is returned as is; anything else, including a post-commit
     * failure, is never the cloud's fault and is a transient [CloudStorageException].
     */
    private suspend fun mergeAndRefresh(merge: () -> Unit): Result<Unit> {
        setPhase(SyncPhase.MERGING)
        try {
            merge()
        } catch (e: KeryxException) {
            if (e is SchemaVersionException) {
                Log.warn(TAG, "Merge aborted: cloud version ${e.cloudVersion} is newer than ${e.localVersion}")
            } else {
                Log.warn(TAG, "Merge aborted: ${e.message}")
            }
            return Result.Err(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // DatabaseMerger classifies an unusable cloud file itself; anything else is never the
            // cloud's fault.
            Log.error(TAG, "Merge failed", e)
            return Result.Err(CloudStorageException("Merge failed: ${e.message}"))
        }
        // Committed. Ids held outside the database are brought onto any re-key first, so a list query
        // that the notification below re-runs is never delivered to a holder (the feed list's delete
        // fallback, say) that still knows the old ids. The holders also read the published map at each
        // decision, so this order is a reinforcement, not the only guard.
        try {
            publishIdRemapsAfterCommit()
        } finally {
            // The merge wrote through its own connection, bypassing SQLDelight's listeners, so the UI is
            // told at once — whatever happens to the indexing after it, and even when the sync is
            // cancelled while publishing (waiting for the re-key map's lock, say): the changes are
            // committed, so they are always announced. The cancellation still propagates.
            driver.notifyListeners(*MERGED_TABLES)
        }
        return finishIndexing()
    }

    /**
     * Brings the ids held outside the database (the selection, the feed list's expansion, the local
     * settings) onto any canonical ids the committed merge moved tags or folders to. A failure is
     * logged, not returned: the merge is committed and the database is the source of truth — every
     * write resolves a stale id itself (tags.sq / folders.sq), the next merge publishes again, and the
     * next launch re-reads the table — so failing the sync for it would only report a fault that
     * leaves nothing wrong behind.
     */
    private suspend fun publishIdRemapsAfterCommit() {
        try {
            publishIdRemaps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.warn(TAG, "Publishing tag/folder id re-keys failed; the next merge or launch catches up: ${e.message}")
        }
    }

    /**
     * Step 5's indexing: `indexMissing()` recomputes the search_text of the rows a merge registered in
     * `ks_search_pending` before it indexes anything, so they are indexed with their real text. Only
     * once it succeeds is [SYNC_STATE_INDEX_PENDING] cleared; a failure — never the cloud's fault —
     * leaves it for the next sync to retry.
     */
    private suspend fun finishIndexing(): Result<Unit> {
        setPhase(SyncPhase.INDEXING)
        try {
            ftsManager.indexMissing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(TAG, "Indexing merged articles failed; the next sync retries it", e)
            return Result.Err(CloudStorageException("Indexing merged articles failed: ${e.message}"))
        }
        // A search showing re-runs on an `articles` change: now it finds the merged rows indexed.
        driver.notifyListeners("articles")
        deleteState(SYNC_STATE_INDEX_PENDING)
        return Result.Ok(Unit)
    }

    /**
     * Step 6: names no longer listed leave `sync_applied` — except a name a known listed base ([known])
     * names explicitly, which `lacks` would otherwise take as missing and fetch that base for again.
     * This device's own deltas stay until a known base covers them, so a listing that does not show a
     * just-sent delta yet never makes it look like someone else's (step 2's clone rule).
     */
    private fun dropUnlisted(listing: Listing, self: String, known: Collection<BaseCoverage>) {
        val listed = listing.deltas.keys.mapTo(mutableSetOf()) { it.fileName } + listing.bases.keys.map { it.fileName }
        val namedByBases = known.flatMapTo(mutableSetOf()) { it.covered.keys }
        val stale = appliedNames().filter { name ->
            if (name in listed || name in namedByBases) return@filter false
            val parsed = TransportFileName.parse(name) ?: return@filter true
            parsed.kind == TransportFileKind.BASE || parsed.device != self || known.any { it.covers(parsed) }
        }
        if (stale.isEmpty()) return
        db.transaction { stale.forEach { db.sync_bookkeepingQueries.deleteApplied(it) } }
    }

    // ---- Step 4: sending ----

    /** "Sending": the full send while one is due, otherwise an ordinary delta. Returns what was sent. */
    private suspend fun send(cloud: CloudStorage, generation: String, exceptPath: String?): Result<TransportFileName?> {
        val capture = db.sync_bookkeepingQueries.captureState().executeAsOne()
        return if (capture.full_pending == 1L) {
            sendFull(cloud, generation, exceptPath, asBase = false)
        } else {
            sendDelta(cloud, generation)
        }
    }

    private suspend fun sendDelta(cloud: CloudStorage, generation: String): Result<TransportFileName?> {
        setPhase(SyncPhase.PREPARING)
        val plain = FileIO.join(tempDir, "ks-send.db")
        val n = lastSeq() + 1
        val meta = TransportMeta(generation, TransportFileKind.DELTA, deviceId(), n, clock.nowMillis())
        val export = try {
            DeltaExporter.exportDelta(localDbPath, plain, meta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(TAG, "Building the delta failed", e)
            deleteDb(plain)
            return Result.Err(CloudStorageException("Building the delta failed: ${e.message}"))
        } ?: return Result.Ok(null)
        return recordAndUpload(cloud, plain, meta, export.highWaterSeq, full = false, empty = export.rowCounts.values.all { it == 0L })
    }

    /**
     * The full send: from a `VACUUM INTO` copy, leaving out rows identical to [exceptPath]'s. As a base
     * ([asBase] — the first device's, a reset's) it covers its own delta `1` through its vector and is
     * uploaded even when empty, since a pointer must always name a generation that has a base.
     */
    private suspend fun sendFull(cloud: CloudStorage, generation: String, exceptPath: String?, asBase: Boolean): Result<TransportFileName?> {
        setPhase(SyncPhase.PREPARING)
        val plain = FileIO.join(tempDir, "ks-send.db")
        val copy = FileIO.join(tempDir, "ks-full-copy.db")
        val device = deviceId()
        val n = lastSeq() + 1
        val kind = if (asBase) TransportFileKind.BASE else TransportFileKind.DELTA
        val meta = TransportMeta(generation, kind, device, n, clock.nowMillis())
        val export = try {
            DeltaExporter.copyForFullSend(localDbPath, copy)
            DeltaExporter.exportFull(copy, plain, meta, exceptPath, vector = if (asBase) mapOf(device to n) else emptyMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(TAG, "Building the full send failed", e)
            deleteDb(plain)
            return Result.Err(CloudStorageException("Building the full send failed: ${e.message}"))
        } finally {
            deleteDb(copy)
        }
        return recordAndUpload(
            cloud, plain, meta, export.highWaterSeq, full = true,
            empty = !asBase && export.rowCounts.values.all { it == 0L },
        )
    }

    /**
     * Steps 2–4 of "Sending" for the file at [plain]: name it by its hash (computed on the exporter's
     * own output, which is never opened for writing again), save it compressed in the data directory
     * and record `ks_attempt`, upload, commit. A file with no rows is not uploaded: what it covered is
     * committed at once.
     */
    private suspend fun recordAndUpload(
        cloud: CloudStorage,
        plain: String,
        meta: TransportMeta,
        highWaterSeq: Long?,
        full: Boolean,
        empty: Boolean,
    ): Result<TransportFileName?> {
        if (empty) {
            deleteDb(plain)
            db.transaction {
                highWaterSeq?.let { db.sync_bookkeepingQueries.deleteOutboxUpTo(it) }
                if (full) db.sync_bookkeepingQueries.clearFullPending()
            }
            return Result.Ok(null)
        }
        val pending = pendingPath(meta.number)
        val name = try {
            val hash = checkNotNull(ContentDigest.sha256File(plain)) { "The file to send cannot be read" }.lowercase()
            val name = meta.fileName(hash.take(12))
            if (meta.kind == TransportFileKind.BASE) {
                saveBases(loadBases().values + TransportFiles.readCoverage(plain, name))
            }
            Gzip.compressFile(plain, pending)
            name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(TAG, "Preparing the send failed", e)
            FileIO.delete(pending)
            return Result.Err(CloudStorageException("Preparing the send failed: ${e.message}"))
        } finally {
            deleteDb(plain)
        }
        val attempt = SendAttempt(meta.number, name, highWaterSeq, full)
        setState(SYNC_STATE_KS_ATTEMPT, attempt.encode())
        setPhase(SyncPhase.UPLOADING)
        uploadAndCommit(cloud, attempt, pending).valueOrElse { return it }
        return Result.Ok(name)
    }

    private suspend fun uploadAndCommit(cloud: CloudStorage, attempt: SendAttempt, pending: String): Result<Unit> {
        val name = attempt.name.fileName
        testHook?.invoke(SyncHookPoint.BEFORE_SEND_UPLOAD, name)
        when (val created = cloud.create("/$name", pending)) {
            is Result.Ok -> Unit
            // The same name means the same content: the file is already there.
            is Result.Err -> if (created.exception !is SyncConflictException) {
                Log.error(TAG, "Sync: upload of $name failed: ${created.exception.message}")
                return created
            }
        }
        testHook?.invoke(SyncHookPoint.AFTER_SEND_UPLOAD, name)
        db.transaction {
            attempt.highWaterSeq?.let { db.sync_bookkeepingQueries.deleteOutboxUpTo(it) }
            setState(SYNC_STATE_KS_LAST_SEQ, attempt.number.toString())
            db.sync_bookkeepingQueries.insertApplied(name)
            db.sync_bookkeepingQueries.upsertCursor(attempt.name.device, attempt.number)
            if (attempt.full) db.sync_bookkeepingQueries.clearFullPending()
            deleteState(SYNC_STATE_KS_ATTEMPT)
        }
        testHook?.invoke(SyncHookPoint.AFTER_SEND_COMMIT, name)
        FileIO.delete(pending)
        return Result.Ok(Unit)
    }

    // ---- Step 5: folding and garbage collection ----

    /**
     * "Bases and Garbage Collection". Folding is skipped unless [compactionAllowed]; a transient
     * failure is logged and left to a later sync (nothing waits for a fold), while a corrupt or too-new
     * input is reported like any other cloud file.
     */
    private suspend fun compact(cloud: CloudStorage, generation: String, listing: Listing, downloads: Downloads): Result<Unit> {
        if (!compactionAllowed()) return Result.Ok(Unit)
        val outcome = try {
            compactUnchecked(cloud, generation, listing, downloads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(TAG, "Folding failed", e)
            Result.Ok(Unit)
        }
        return when (outcome) {
            is Result.Err -> when (outcome.exception) {
                is CloudDataIncompatibleException, is SchemaVersionException -> outcome
                else -> {
                    Log.warn(TAG, "Folding skipped: ${outcome.exception.message}")
                    Result.Ok(Unit)
                }
            }
            is Result.Ok -> outcome
        }
    }

    private suspend fun compactUnchecked(cloud: CloudStorage, generation: String, listing: Listing, downloads: Downloads): Result<Unit> {
        val self = deviceId()
        val cache = loadBases().toMutableMap()
        fun listedKnown() = listing.bases.keys.mapNotNull { cache[it.fileName] }
        fun nonDominated(bases: List<BaseCoverage>) = bases.filter { b -> bases.none { it.dominates(b) } }
        fun uncovered(bases: List<BaseCoverage>) = listing.deltaNames.filter { d -> bases.none { it.covers(d) } }
        fun thresholdMet(bases: List<BaseCoverage>): Boolean {
            val pending = uncovered(bases)
            if (pending.isEmpty()) return false
            if (pending.size >= KS_COMPACT_MIN_DELTAS) return true
            val largestBase = listing.bases.values.maxOfOrNull { it.size } ?: return true
            val pendingSize = pending.sumOf { listing.deltas[it]?.size ?: 0L }
            return pendingSize >= KS_COMPACT_MAX_DELTA_RATIO * largestBase
        }

        if (thresholdMet(listedKnown())) {
            // Bases this device has never looked at may already cover those deltas.
            for (name in listing.bases.keys.filter { it.fileName !in cache }.sortedBy { it.fileName }) {
                currentCoroutineContext().ensureActive()
                // The network may stop allowing folding mid-way: stop the whole compaction, as [compact] does.
                if (!compactionAllowed()) return Result.Ok(Unit)
                // Gone meanwhile (garbage-collected by another device): nothing to learn from it.
                cache[name.fileName] = fetch(cloud, name, downloads).valueOrElse { return it } ?: continue
            }
            saveBases(cache.values.filter { b -> listing.bases.keys.any { it.fileName == b.name } })
            if (thresholdMet(listedKnown())) {
                val built = buildBase(cloud, generation, self, nonDominated(listedKnown()), listing, downloads).valueOrElse { return it }
                if (built != null) {
                    cache[built.first.name] = built.first
                    listing.bases[built.first.fileName] = built.second
                    saveBases(cache.values.filter { b -> listing.bases.keys.any { it.fileName == b.name } })
                }
            }
        }
        collectGarbage(cloud, listing, listedKnown())
        return Result.Ok(Unit)
    }

    /**
     * Folds [inputBases] and every listed delta none of them covers into a new base — **never from the
     * local database**, whose merge may have skipped rows — and uploads it. Returns its coverage and
     * listing entry, or `null` when it would add nothing or [compactionAllowed] stops allowing the fold
     * before an input's download or the upload.
     */
    private suspend fun buildBase(
        cloud: CloudStorage,
        generation: String,
        self: String,
        inputBases: List<BaseCoverage>,
        listing: Listing,
        downloads: Downloads,
    ): Result<Pair<BaseCoverage, CloudFileEntry>?> {
        val inputDeltas = listing.deltaNames.filter { d -> inputBases.none { it.covers(d) } }
        if (inputDeltas.isEmpty()) return Result.Ok(null)
        val number = (listing.bases.keys.filter { it.device == self }.maxOfOrNull { it.number } ?: 0L) + 1
        val accumulator = FileIO.join(tempDir, "ks-base.db")
        val gz = FileIO.join(tempDir, "ks-base.db.gz")
        try {
            setPhase(SyncPhase.PREPARING)
            // The name carries the same creation time, and finalizeBase derives the impliable cutoff from it.
            val meta = TransportMeta(generation, TransportFileKind.BASE, self, number, clock.nowMillis())
            TransportFiles.createEmpty(accumulator, meta)
            // Each covered delta carries its creation time into ks_covered (TransportFiles.fold).
            for (name in inputBases.map { it.fileName } + inputDeltas.sortedBy { it.fileName }) {
                currentCoroutineContext().ensureActive()
                // The network may stop allowing a fold midway (a switch to a metered network): stop
                // before the next download rather than finish a fold the policy no longer allows.
                if (!compactionAllowed()) return Result.Ok(null)
                // An input gone meanwhile leaves the base incomplete: build it on a later sync.
                fetch(cloud, name, downloads).valueOrElse { return it } ?: return Result.Ok(null)
                setPhase(SyncPhase.PREPARING)
                TransportFiles.fold(accumulator, checkNotNull(downloads.pathFor(name)), name, extendVector = false)
            }
            TransportFiles.finalizeBase(accumulator)
            val hash = checkNotNull(ContentDigest.sha256File(accumulator)) { "The new base cannot be read" }.lowercase()
            val name = meta.fileName(hash.take(12))
            val coverage = TransportFiles.readCoverage(accumulator, name)
            Gzip.compressFile(accumulator, gz)
            val size = SystemFileSystem.metadataOrNull(Path(gz))?.size ?: 0L
            currentCoroutineContext().ensureActive()
            if (!compactionAllowed()) return Result.Ok(null)
            setPhase(SyncPhase.UPLOADING)
            testHook?.invoke(SyncHookPoint.BEFORE_BASE_UPLOAD, name.fileName)
            when (val created = cloud.create("/${name.fileName}", gz)) {
                is Result.Ok -> Unit
                is Result.Err -> if (created.exception !is SyncConflictException) return created
            }
            Log.info(TAG, "Folded ${inputDeltas.size} deltas into base $name")
            return Result.Ok(coverage to CloudFileEntry(name.fileName, "", size))
        } finally {
            deleteDb(accumulator)
            FileIO.delete(gz)
        }
    }

    /**
     * Garbage collection, keeping every change reachable: a base goes only while another base strictly
     * dominates it, and a delta only once a base covers it and the delta itself was created at least
     * [KS_DELTA_GRACE_MS] ago ([BaseCoverage.coveredBefore]) — the covering base confirmed to exist
     * (`metadata`) just before. Best effort: a
     * failure is logged, and a later sync tries again.
     */
    private suspend fun collectGarbage(cloud: CloudStorage, listing: Listing, bases: List<BaseCoverage>) {
        val confirmed = mutableMapOf<String, Boolean>()
        suspend fun exists(base: BaseCoverage): Boolean = confirmed.getOrPut(base.name) {
            cloud.metadata("/${base.name}").let { it is Result.Ok && it.value != null }
        }
        val deleted = mutableSetOf<String>()
        for (base in bases.sortedBy { it.name }) {
            currentCoroutineContext().ensureActive()
            val dominator = bases.firstOrNull { it.name !in deleted && it.dominates(base) } ?: continue
            if (!exists(dominator)) continue
            testHook?.invoke(SyncHookPoint.BEFORE_GC_DELETE, base.name)
            when (val r = cloud.delete("/${base.name}")) {
                is Result.Ok -> deleted += base.name
                is Result.Err -> Log.warn(TAG, "GC: deleting base ${base.name} failed: ${r.exception.message}")
            }
        }
        if (deleted.isNotEmpty()) {
            listing.bases.keys.removeAll { it.fileName in deleted }
            saveBases(loadBases().values.filter { it.name !in deleted })
        }
        val cutoff = clock.nowMillis() - KS_DELTA_GRACE_MS
        val alive = bases.filter { it.name !in deleted }
        for (delta in listing.deltaNames.sortedBy { it.fileName }) {
            currentCoroutineContext().ensureActive()
            val base = alive.firstOrNull { it.coveredBefore(delta, cutoff) } ?: continue
            if (!exists(base)) continue
            testHook?.invoke(SyncHookPoint.BEFORE_GC_DELETE, delta.fileName)
            val r = cloud.delete("/${delta.fileName}")
            if (r is Result.Err) Log.warn(TAG, "GC: deleting delta $delta failed: ${r.exception.message}")
        }
    }

    // ---- State helpers ----

    private fun pendingPath(number: Long): String = FileIO.join(dataDir, "pending-$number.db.gz")

    /**
     * Deletes every saved send file in [dataDir], whether or not a record still names it. Called only
     * once no send is being resumed (its record cleared, or none recorded). Best effort: a file that
     * cannot be deleted is logged and left for the next sweep, and nothing is thrown, so a disconnect
     * or a join is never stopped by it — a leftover file is never uploaded, since only a recorded send
     * is.
     */
    private fun sweepPendingFiles() {
        val entries = try {
            val dir = Path(dataDir)
            if (!SystemFileSystem.exists(dir)) return
            SystemFileSystem.list(dir).filter { PENDING_FILE_NAME.matches(it.name) }
        } catch (e: Exception) {
            Log.warn(TAG, "Sync: listing saved send files failed: ${e.message}")
            return
        }
        for (path in entries) {
            try {
                SystemFileSystem.delete(path, mustExist = false)
            } catch (e: Exception) {
                Log.warn(TAG, "Sync: deleting the saved send file ${path.name} failed: ${e.message}")
            }
        }
    }

    private fun state(key: String): String? = db.sync_stateQueries.get(key).executeAsOneOrNull()

    private fun setState(key: String, value: String) = db.sync_stateQueries.upsert(key, value)

    private fun deleteState(key: String) = db.sync_stateQueries.delete(key)

    private fun ownGeneration(): String? = state(SYNC_STATE_KS_GENERATION)?.takeIf(TransportFileName::isSyncId)

    private fun deviceId(): String = checkNotNull(state(SYNC_STATE_KS_DEVICE_ID)?.takeIf(TransportFileName::isSyncId)) { "No sync identity" }

    private fun lastSeq(): Long = state(SYNC_STATE_KS_LAST_SEQ)?.toLongOrNull() ?: 0L

    private fun appliedNames(): Set<String> = db.sync_bookkeepingQueries.appliedAll().executeAsList().toSet()

    private fun loadBases(): Map<String, BaseCoverage> = SyncBaseCache.decode(state(SYNC_STATE_KS_BASES))

    private fun saveBases(bases: Collection<BaseCoverage>) = setState(SYNC_STATE_KS_BASES, SyncBaseCache.encode(bases.distinctBy { it.name }))

    private companion object {
        const val TAG = "Sync"

        /** A saved send's file name ([pendingPath]). */
        val PENDING_FILE_NAME = Regex("pending-\\d+\\.db\\.gz")

        /**
         * The tables a merge writes whose queries the UI watches: the synced tables, plus
         * `feed_fetch_state`, which the feed identity step moves onto a feed's canonical id.
         */
        val MERGED_TABLES = arrayOf("folders", "feeds", "tags", "feed_tags", "articles", "global_settings", "feed_fetch_state")

        fun deleteDb(path: String) {
            FileIO.delete(path)
            FileIO.delete("$path-journal")
        }
    }
}

/**
 * The merge statement that marks the search index as behind ([SYNC_STATE_INDEX_PENDING]) in the
 * merge's own transaction, so an indexing failure after the commit is retried by the next sync. Every
 * merge run through `DeltaSync.mergeAndRefresh` — a receive's and the pre-delta migration's — ends
 * with it.
 */
internal const val MARK_INDEX_PENDING_SQL =
    "INSERT OR REPLACE INTO main.sync_state (key, value) VALUES ('$SYNC_STATE_INDEX_PENDING', '1')"

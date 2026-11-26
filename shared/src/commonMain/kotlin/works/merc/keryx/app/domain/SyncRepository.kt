package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.DEFERRED_SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.LOCAL_WRITES_FLUSH_WAIT_MS
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SETTINGS_TAB_CLOUD_SYNC
import works.merc.keryx.app.core.SETTINGS_TAB_UPDATES
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.runCatchingUnlessCancelled
import works.merc.keryx.app.core.syncErrorKind
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.ExternalAppLaunch
import works.merc.keryx.app.platform.databaseFilePath

/**
 * Who asked for a sync. Only [AUTOMATIC] is subject to the unusable-cloud-DB gate
 * ([SyncRepository.autoSyncSuspended]) and the network gate ([SyncRepository.syncDeferredForNetwork]):
 * a person who pressed "sync" must always get a real attempt (and the failure that explains why),
 * or the app would silently do nothing with no way for them to find out.
 */
enum class SyncTrigger { MANUAL, AUTOMATIC }

/**
 * The step [SyncRepository] is currently on, for live progress feedback in the cloud-sync settings
 * tab. [IDLE] outside any [SyncRepository.sync]/[SyncRepository.resetCloudData] call — including
 * the entire duration of an [SyncTrigger.AUTOMATIC] call skipped by [SyncRepository.autoSyncSuspended]
 * or deferred for the network ([SyncRepository.syncDeferredForNetwork]), neither of which enters
 * [ActivityCenter.trackSync] at all. Updated only from inside the single mutex
 * both of those methods hold, so a queued sync never sees a stale phase left by the one ahead of it.
 */
enum class SyncPhase { IDLE, CHECKING, DOWNLOADING, MERGING, INDEXING, PREPARING, UPLOADING, ARCHIVING }

/**
 * Cloud sync. Each device appends only the changes it made itself to the cloud, as small immutable
 * delta files, merges everyone else's, and now and then folds them into a base — the flow in
 * "Sync Flow" of `docs/sync-architecture.md`, run by [DeltaSync]. This class owns what surrounds it:
 * the [mutex] every sync, reset and disconnect holds, the automatic-sync gates, the debounce, and the
 * user-facing state ([lastSyncError], [syncPhase], the notification center).
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
    private val localDbPath: String = databaseFilePath(),
    private val tempDir: String = AppDirs.tempDir(),
    /**
     * Where a send's file is saved until it is committed ("Sending"): durable storage, since a
     * crash between recording a send and committing it must find the file again.
     */
    private val dataDir: String = AppDirs.appDataDir(),
    /**
     * Whether an [SyncTrigger.AUTOMATIC] sync may use the current network
     * ([NetworkUsagePolicy.automaticSyncAllowed]), as a flow so a deferred sync can wait for the
     * moment it becomes true. A lambda, like [cloudProvider], because what it reads
     * (`SettingsRepository`) itself depends on this class as its [SyncScheduler]. The default (always
     * allowed) is for tests that do not exercise the gate.
     */
    private val automaticSyncAllowedFlow: () -> Flow<Boolean> = { flowOf(true) },
    /**
     * The local writes queued but not yet run (a global-setting change, a feed-list action), which
     * schedule their sync only once they run; [flushPendingSync] waits for them. Shared with
     * `SettingsRepository` and `HomeViewModel` in production.
     */
    private val pendingLocalWrites: PendingLocalWrites = PendingLocalWrites(),
    /**
     * Whether a sync may fold deltas into a base now ([NetworkUsagePolicy.compactionAllowed]) — never on
     * a metered network, whatever the trigger. The default (always) is for tests.
     */
    private val compactionAllowedFlow: () -> Flow<Boolean> = { flowOf(true) },
    /** Issues generation and device ids; [IdGenerator.newId] in production, deterministic in tests. */
    newId: () -> String = IdGenerator::newId,
    /**
     * Republishes the tag/folder id re-keys after a merge commits ([TagFolderIdRemaps.publishFromDb]);
     * the app's one [TagFolderIdRemaps] in production. A lambda, like [cloudProvider], so resolving it
     * stays lazy. The default (nothing) is for tests that do not observe re-keys.
     */
    publishIdRemaps: suspend () -> Unit = {},
) : SyncScheduler {

    private val mutex = Mutex()
    private val _syncPhase = MutableStateFlow(SyncPhase.IDLE)

    private val engine = DeltaSync(
        driver = driver,
        db = db,
        ftsManager = ftsManager,
        clock = clock,
        localDbPath = localDbPath,
        tempDir = tempDir,
        dataDir = dataDir,
        newId = newId,
        compactionAllowed = { compactionAllowedFlow().first() },
        setPhase = { _syncPhase.value = it },
        publishIdRemaps = publishIdRemaps,
    )

    /** Interrupts or observes a sync at a [SyncHookPoint]; for tests only. */
    internal var testHook: (suspend (SyncHookPoint, String) -> Unit)?
        get() = engine.testHook
        set(value) {
            engine.testHook = value
        }
    private val _lastSyncError = MutableStateFlow<ErrorKind?>(null)

    /**
     * Whether [lastSyncError] is an authentication failure specifically, rather than any other
     * reason sync can break — i.e. [ErrorKind.CLOUD_AUTH]. Written in the same step as
     * [_lastSyncError] (never derived asynchronously from it), so the two can never be observed
     * disagreeing.
     *
     * The cloud-sync settings tab uses this to offer reconnecting instead of resetting cloud data —
     * a reset needs a working authorization of its own, so it is precisely the wrong recovery to
     * offer while authorization is what failed.
     */
    private val _lastSyncAuthFailed = MutableStateFlow(false)
    private val _autoSyncSuspended = MutableStateFlow(false)
    private val _syncDeferredForNetwork = MutableStateFlow(false)

    /** Guards [deferredSyncWaiter], [deferralGeneration] (and their pairing with [_syncDeferredForNetwork]). */
    private val deferralMutex = Mutex()

    /**
     * Bumped each time a sync is deferred. A sync that succeeds clears the deferral only if this is
     * unchanged since it started: a deferral raised while it ran covers changes it did not upload.
     */
    private var deferralGeneration = 0

    /** Waits for the network to allow automatic syncing again, then schedules the deferred sync. */
    private var deferredSyncWaiter: Job? = null

    /**
     * Debounce signals, coalesced. [scheduleSync] is called from the UI thread (tag / folder / feed
     * edits), from `HomeViewModel`'s single-threaded write dispatcher (every mark-as-read / star) and
     * from `Dispatchers.Default` (once per feed in a refresh). Cancelling and reassigning a shared
     * `Job` field from three dispatchers can drop a reference, leaving a pending debounce that
     * nothing can cancel — so a write burst would fire duplicate syncs, each writing a delta.
     * (Integrity is never at risk: [sync] holds [mutex]. The cost is the duplicated work.) A
     * conflated channel with the single consumer below keeps the same trailing-debounce semantics
     * with no shared mutable state, mirroring how `SettingsRepository` coalesces its own disk write.
     *
     * A signal carries no payload: which window applies is read from [soonRequested] each time the
     * window is (re-)armed, since a conflated channel would let a later deferred signal overwrite an
     * earlier urgent one.
     */
    private val syncSignals = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Local changes exist that no sync has picked up yet. Set by [scheduleSync] and
     * [scheduleDeferredSync], cleared when a sync starts (its delta will include them), and restored
     * if that sync does not succeed. [flushPendingSync] and an expiring debounce window both sync
     * only while something is pending ([hasPendingChanges]: this flag, or an outbox entry or a full
     * send still unsent), so a window outlived by a refresh cycle's own sync costs nothing.
     *
     * Neither this nor [soonRequested] is ever collected: a `MutableStateFlow` is used only as a
     * thread-safe flag with an atomic [getAndUpdate], since commonMain has no stable atomic type.
     */
    private val unsyncedChanges = MutableStateFlow(false)

    /**
     * A structural change ([scheduleSync]) is waiting, so the short [SYNC_DEBOUNCE_MS] window
     * applies rather than [DEFERRED_SYNC_DEBOUNCE_MS]. Cleared when the window expires.
     */
    private val soonRequested = MutableStateFlow(false)

    init {
        scope.launch {
            for (signal in syncSignals) {
                // Re-arm as long as calls keep arriving, so the window is measured from the last one.
                // The window is re-read on every re-arm, so an urgent change shortens a deferred wait.
                while (withTimeoutOrNull(debounceWindowMs()) { syncSignals.receive() } != null) {
                    // Another schedule call landed inside the window; restart it.
                }
                soonRequested.value = false
                // This is the *only* consumer, so a throw escaping here would end debounced syncing
                // for the rest of the process while scheduleSync() kept succeeding into a channel
                // nobody reads. Neither the pending check (a DB read) nor sync() is exception-proof
                // (a bare SQLDelight write in setSyncState, resource loading in
                // emitErrorNotification), which is why sync()'s other call sites in main.kt guard the
                // same way. A failure is logged, and the next signal is waited for.
                runCatchingUnlessCancelled {
                    // A sync that ran meanwhile (a refresh cycle, a manual sync, a flush) already
                    // uploaded what this window was waiting for.
                    if (hasPendingChanges()) sync(SyncTrigger.AUTOMATIC)
                }.onFailure { e -> Log.error(TAG, "Debounced sync failed", e) }
            }
        }
    }

    /**
     * Why the last sync failed, or null when it succeeded (or none has run yet) — the same reason
     * as the notification-center entry's [NotificationText.SyncFailed], kept as state so the
     * cloud-sync settings tab can show "why sync is broken right now" even after that notification
     * was dismissed. The UI localizes it.
     */
    val lastSyncError: StateFlow<ErrorKind?> = _lastSyncError

    /** See [_lastSyncAuthFailed]. Always false while [lastSyncError] is null. */
    val lastSyncAuthFailed: StateFlow<Boolean> = _lastSyncAuthFailed

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
     * The step the currently-running (or most recently finished) sync is on. Mirrors [_syncPhase],
     * which every phase transition writes under [mutex] — see [SyncPhase] for why that ordering
     * matters. The cloud-sync settings tab pairs this with [ActivitySnapshot.syncing] (from
     * [ActivityCenter.activity], via `SettingsViewModel`) to show live progress text on the
     * connected provider's row.
     */
    val syncPhase: StateFlow<SyncPhase> = _syncPhase

    /**
     * True while an [SyncTrigger.AUTOMATIC] sync was held back because the current network does not
     * allow it ([NetworkUsagePolicy.automaticSyncAllowed] — a metered network without the user's
     * opt-in, or the OS's data-saving mode) and the network has not allowed one since. While it is
     * true, a waiter schedules a sync the moment the network allows one (and drops the flag then); Android additionally hands the
     * deferred sync to a `WorkManager` job constrained to an unmetered network, so it also happens
     * with the app closed.
     *
     * Deliberately in-memory, like [autoSyncSuspended]: the pending changes themselves are persisted
     * in `sync_outbox`, so the next sync that is allowed sends every change made in the meantime. Also cleared by a successful sync (a manual one included) unless
     * another sync was deferred while it ran, when automatic syncing becomes suspended, and by
     * [clearSyncFailureState]. A sync that fails after the network allowed it reports through
     * [lastSyncError] instead, so the flag never claims a wait that nothing is performing.
     */
    val syncDeferredForNetwork: StateFlow<Boolean> = _syncDeferredForNetwork

    /**
     * Clears everything tied to the current cloud connection — the mirrored failure reason, the
     * automatic-sync pause, and the delta sync's bookkeeping (recording off; the `ks_*` keys, the
     * outbox, the applied names, the cursors and a saved send gone — see "Joining, Reset,
     * Disconnect" in sync-architecture.md) — when the connection is being torn down, so a
     * subsequently-connected provider does not inherit any of it. Its first sync joins afresh.
     *
     * The feeds' raw folder assignments (`ks_feed_folder_raw`) are kept: they are this device's
     * data — what `feeds.folder_id` is resolved from and what a send carries — not a record of the
     * old provider's files.
     *
     * Runs under [mutex], the same lock [sync] holds: without it, a sync already past its commit
     * could re-write the bookkeeping *after* this cleared it, handing the next connection the old
     * provider's identity and cursors — or turn recording back on while local-only.
     */
    suspend fun clearSyncFailureState() {
        mutex.withLock {
            _lastSyncError.value = null
            _lastSyncAuthFailed.value = false
            _autoSyncSuspended.value = false
            engine.clearConnectionState()
        }
        clearNetworkDeferral()
    }

    /** Whether anything is waiting to be sent — see [unsyncedChanges]. */
    private fun hasPendingChanges(): Boolean = unsyncedChanges.value || engine.hasPendingChanges()

    private fun debounceWindowMs(): Long = if (soonRequested.value) SYNC_DEBOUNCE_MS else DEFERRED_SYNC_DEBOUNCE_MS

    /**
     * Schedules a debounced cloud synchronization.
     */
    override fun scheduleSync() = schedule(soon = true)

    /** Schedules a sync after [DEFERRED_SYNC_DEBOUNCE_MS] of quiet — see [SyncScheduler.scheduleDeferredSync]. */
    override fun scheduleDeferredSync() = schedule(soon = false)

    private fun schedule(soon: Boolean) {
        if (cloudProvider() == null) return
        if (_autoSyncSuspended.value) return
        unsyncedChanges.value = true
        if (soon) soonRequested.value = true
        syncSignals.trySend(Unit)
    }

    /**
     * [flushPendingSync] for a mobile app leaving the foreground, except when it left only because it
     * sent the user to another app itself (opening an article in the browser — see
     * [ExternalAppLaunch]): that would write one delta per article read that way. The
     * changes stay pending for the long window, the next sync, or the next real exit.
     */
    suspend fun flushOnLeavingForeground() {
        if (ExternalAppLaunch.consumeLaunched()) return
        flushPendingSync()
    }

    /**
     * Syncs now if local changes are still waiting for a debounce window, and returns once that
     * sync has finished; does nothing otherwise. Called when the app leaves the foreground, so
     * deferred reading-state changes reach other devices without waiting out their long window.
     *
     * A sync already in flight is waited out first: it cleared the flag when it started, so checking
     * at once would return while its upload is still running — and on iOS the caller ends its
     * background task on return, letting the OS suspend that upload. Should that sync fail or be
     * cancelled (iOS cancels the refresh cycle's on backgrounding), it restores the flag, and this
     * flush sends the changes itself.
     *
     * Before that, local writes still queued ([pendingLocalWrites] — a global setting changed, or a
     * feed-list action, just before leaving) are waited for, for at most [LOCAL_WRITES_FLUSH_WAIT_MS]:
     * such a write schedules its sync only once it runs, so checking first would miss it. A sync the
     * write's own schedule starts meanwhile is then waited out like any other.
     */
    suspend fun flushPendingSync() {
        withTimeoutOrNull(LOCAL_WRITES_FLUSH_WAIT_MS) { pendingLocalWrites.awaitIdle() }
        mutex.withLock { }
        if (!hasPendingChanges()) return
        sync(SyncTrigger.AUTOMATIC)
    }

    /**
     * Synchronizes the local database with the cloud provider.
     *
     * @param trigger Who is asking. [SyncTrigger.AUTOMATIC] is skipped (returning [Result.Ok]) while
     * [autoSyncSuspended] is true, so a known-unusable cloud DB does not get re-downloaded and
     * re-merged on every debounced write or background cycle, and is deferred (also returning
     * [Result.Ok], see [syncDeferredForNetwork]) while the current network does not allow it;
     * [SyncTrigger.MANUAL] (the default) always runs, so a person who explicitly asked for a sync
     * always gets a real attempt.
     * @return The synchronization result.
     */
    suspend fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL): Result<Unit> {
        if (trigger == SyncTrigger.AUTOMATIC) {
            if (_autoSyncSuspended.value) {
                Log.info(TAG, "Automatic sync skipped: cloud data is unusable until it is reset")
                // Nothing will resume a deferral while automatic syncing is suspended.
                clearNetworkDeferral()
                return Result.Ok(Unit)
            }
            // Nothing to defer with no provider connected; syncLocked() would skip it anyway.
            if (!automaticSyncAllowedFlow().first() && cloudProvider() != null) {
                Log.info(TAG, "Automatic sync deferred: the current network does not allow it")
                deferUntilNetworkAllows()
                return Result.Ok(Unit)
            }
        }
        val deferralAtStart = deferralMutex.withLock { deferralGeneration }
        // The gate and the failure text are written inside the lock, not after it: they are two of
        // the four fields clearSyncFailureState() clears, and it takes the same lock so a
        // disconnect can never be undone by a sync that was already in flight.
        return activityCenter.trackSync {
            mutex.withLock {
                // Cleared before the delta is built, so a change made during this sync re-arms
                // it; restored on every exit but success (an error result, a throw, a cancellation)
                // so a sync that uploaded nothing doesn't drop pending changes.
                val hadUnsyncedChanges = unsyncedChanges.getAndUpdate { false }
                var succeeded = false
                try {
                    val result = syncLocked()
                    succeeded = result is Result.Ok
                    updateAutoSyncGate(result)
                    emitErrorNotification(result)
                    result
                } finally {
                    if (!succeeded && hadUnsyncedChanges) unsyncedChanges.value = true
                    _syncPhase.value = SyncPhase.IDLE
                }
            }
        }.also { result ->
            when {
                result is Result.Ok -> clearNetworkDeferral(onlyIfGeneration = deferralAtStart)
                // A failure that suspends automatic syncing leaves nothing to resume the deferral.
                _autoSyncSuspended.value -> clearNetworkDeferral()
            }
        }
    }

    /**
     * Marks a sync as deferred for the network and starts — once, however many syncs get deferred
     * meanwhile — a waiter that schedules a sync as soon as [automaticSyncAllowedFlow] turns true,
     * at which point the deferral is over (the flag drops): the sync it schedules either runs, and
     * reports its own failure, or is deferred anew should the network turn restrictive again first.
     */
    private suspend fun deferUntilNetworkAllows() {
        deferralMutex.withLock {
            // A disconnect may have landed since the caller looked; it must not be undone.
            if (cloudProvider() == null) return
            deferralGeneration++
            _syncDeferredForNetwork.value = true
            if (deferredSyncWaiter?.isActive == true) return
            deferredSyncWaiter = scope.launch {
                val self = currentCoroutineContext()[Job]
                // A gate flow that completes without ever allowing a sync leaves nothing to schedule.
                automaticSyncAllowedFlow().firstOrNull { it } ?: return@launch
                val current = deferralMutex.withLock {
                    (deferredSyncWaiter === self).also {
                        if (it) {
                            _syncDeferredForNetwork.value = false
                            deferredSyncWaiter = null
                        }
                    }
                }
                if (!current) return@launch
                Log.info(TAG, "Network allows automatic sync again; scheduling the deferred sync")
                scheduleSync()
            }
        }
    }

    /**
     * Drops the deferral: nothing is pending any more (a sync took over, or the connection is gone).
     *
     * @param onlyIfGeneration When set, does nothing if another sync was deferred since that
     * [deferralGeneration] was read.
     */
    private suspend fun clearNetworkDeferral(onlyIfGeneration: Int? = null) {
        deferralMutex.withLock {
            if (onlyIfGeneration != null && onlyIfGeneration != deferralGeneration) return
            _syncDeferredForNetwork.value = false
            deferredSyncWaiter?.cancel()
            deferredSyncWaiter = null
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
     * Resets the cloud sync data — the recovery path for cloud data this app cannot use (see
     * "Resetting (Archiving) Cloud Data" in sync-architecture.md). Deletes nothing: this device joins
     * a new generation, uploads its full state as that generation's base, and replaces the pointer
     * with a revision check; the previous generation's files stay in place, unread, as the archive.
     * Every other device then rejoins with its own full send. A pre-delta `keryx.db.gz` that is not
     * the stub (corrupt, a full database) is then archived as a `.bak` and replaced by the stub, so it
     * no longer blocks syncing.
     *
     * @return A successful result when the new generation is in place; otherwise the failure.
     */
    suspend fun resetCloudData(): Result<Unit> {
        val cloud = cloudProvider() ?: return Result.Ok(Unit)
        // Gate and failure text written inside the lock, same reason as in sync().
        return activityCenter.trackSync {
            mutex.withLock {
                try {
                    val result = engine.reset(cloud)
                    updateAutoSyncGate(result)
                    emitErrorNotification(result)
                    result
                } finally {
                    _syncPhase.value = SyncPhase.IDLE
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
            val reason = result.exception.syncErrorKind
            _lastSyncError.value = reason
            _lastSyncAuthFailed.value = reason == ErrorKind.CLOUD_AUTH
            notificationCenter.addCoalescing(
                newNotification(
                    level = AppNotificationLevel.ERROR,
                    text = NotificationText.SyncFailed(reason),
                    nowMillis = clock.nowMillis(),
                    action = nextActionFor(result.exception),
                ),
            )
        } else if (result is Result.Ok) {
            // Sync works again — drop the stale reason (a SyncConflictException, handled internally,
            // deliberately falls through both branches and changes nothing).
            _lastSyncError.value = null
            _lastSyncAuthFailed.value = false
        }
    }

    /**
     * Selects the user action associated with a sync failure.
     *
     * @return The action for resolving the failure.
     */
    private fun nextActionFor(exception: KeryxException): AppNotificationAction = when (exception) {
        is CloudDataIncompatibleException -> AppNotificationAction.ResetCloudData
        is SchemaVersionException -> AppNotificationAction.ShowSettingsTab(SETTINGS_TAB_UPDATES)
        is FeedFetchException, is FeedParseException, is FeedDiscoveryException, is FeedTimeoutException,
        is FeedNotFoundException, is CloudAuthException, is CloudStorageException, is SyncConflictException,
        is UpdateException, is NextcloudServerException,
        -> AppNotificationAction.ShowSettingsTab(SETTINGS_TAB_CLOUD_SYNC)
    }

    /**
     * Synchronizes the local database with the cloud database — the delta flow ("Sync Flow" in
     * sync-architecture.md), run by [engine].
     */
    private suspend fun syncLocked(): Result<Unit> {
        val cloud = cloudProvider() ?: run {
            Log.info(TAG, "Sync skipped: no cloud provider connected")
            return Result.Ok(Unit)
        }
        return engine.sync(cloud)
    }

    private fun setSyncState(key: String, value: String) {
        db.sync_stateQueries.upsert(key, value)
    }

    private fun getSyncState(key: String): String? =
        db.sync_stateQueries.get(key).executeAsOneOrNull()

    fun lastSyncedAt(): Long? = getSyncState(SYNC_STATE_LAST_SYNCED_AT)?.toLongOrNull()

    private companion object {
        const val TAG = "Sync"
    }
}

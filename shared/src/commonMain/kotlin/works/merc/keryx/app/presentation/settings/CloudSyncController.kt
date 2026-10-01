package works.merc.keryx.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.awaitCancellableConnect
import works.merc.keryx.app.presentation.formatTimestamp

/**
 * The settings screen's cloud-sync state and actions: connecting/disconnecting/switching/
 * reconnecting a provider, resetting cloud data, and running a manual sync — shared with the
 * SwiftUI app (via `KeryxSdk.cloudSyncController`) so both UIs follow the same connect →
 * complete-connect → initial-sync ordering, and the same `canSyncNow` gating Home's own cloud
 * button follows. Split out of what was `SettingsViewModel` (see `.claude/CLAUDE.md`'s "Apple
 * Native Apps (SwiftUI)" in `docs/app-architecture.md`); [SettingsViewModel] in `:composeApp` now
 * wraps this rather than owning the logic itself.
 */
class CloudSyncController(
    private val cloudSession: CloudSession,
    private val syncRepository: SyncRepository,
    private val cloudConnectionService: CloudConnectionService,
    private val activityCenter: ActivityCenter,
    // Watched for provider changes made outside this controller (see [connectedType]).
    private val settingsRepository: SettingsRepository,
    // Token store / sync touch the OS Keychain (macOS shells out to `security`, which may
    // block and show an authorization dialog), so keep them off the Main/EDT dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> = CloudStorageAvailability.available

    /** The provider selection [connectedType]'s initial value was read against. */
    private val initialCloudStorageTypeId = settingsRepository.localSettings.value.cloudStorageType

    /**
     * The currently-connected provider, or null (local-only). At most one at a time.
     *
     * This controller's own connect / disconnect / switch paths write it directly, so the row
     * updates the instant they finish; it is also re-derived from [CloudSession.connectedType]
     * whenever the persisted provider selection (`cloudStorageType`) changes, because a provider
     * can be connected or disconnected by code that never goes through this controller — the
     * first-run setup screen's own connect (`SetupController`), most importantly, which on desktop
     * runs while this controller already exists. Without that, [canSyncNow] would stay false and
     * the cloud-sync tab would show "not connected" until the app restarted. The same signal
     * Home's `cloudConnected` re-evaluates on.
     */
    private val _connectedType = MutableStateFlow(cloudSession.connectedType())
    val connectedType = _connectedType.asStateFlow()

    /** The provider whose connect flow is currently running, or null. */
    private val _connectingType = MutableStateFlow<CloudStorageType?>(null)
    val connectingType: StateFlow<CloudStorageType?> = _connectingType.asStateFlow()

    /** The provider whose connect-time initial sync is running, or null (see [connect]). */
    private val _initialSyncingType = MutableStateFlow<CloudStorageType?>(null)
    val initialSyncingType: StateFlow<CloudStorageType?> = _initialSyncingType.asStateFlow()

    /** The provider whose last connect attempt failed, or null. */
    private val _connectFailedType = MutableStateFlow<CloudStorageType?>(null)
    val connectFailedType: StateFlow<CloudStorageType?> = _connectFailedType.asStateFlow()

    private var authorizationJob: Job? = null

    /** True only while actively waiting on the OAuth browser redirect for [connectingType]. */
    private val _canCancelConnect = MutableStateFlow(false)
    val canCancelConnect = _canCancelConnect.asStateFlow()

    /** True while a "reset cloud data" (delete + fresh re-upload) is running. */
    private val _resetting = MutableStateFlow(false)
    val resetting = _resetting.asStateFlow()

    /** Timestamp of the last successful sync, formatted for display. null when never synced or not connected. */
    private val _lastSyncedAtText = MutableStateFlow<String?>(null)
    val lastSyncedAtText: StateFlow<String?> = _lastSyncedAtText.asStateFlow()

    /**
     * Why the last sync failed, or null when sync is healthy. Mirrors [SyncRepository.lastSyncError],
     * so the cloud-sync tab shows the current reason even after the notification was dismissed.
     * Distinct from [connectFailedType], which only covers a failed connect (OAuth) flow.
     */
    private val _lastSyncError = MutableStateFlow<ErrorKind?>(null)
    val lastSyncError: StateFlow<ErrorKind?> = _lastSyncError.asStateFlow()

    /**
     * Whether [lastSyncError] is an authentication failure specifically. Mirrors
     * [SyncRepository.lastSyncAuthFailed].
     */
    private val _lastSyncAuthFailed = MutableStateFlow(false)
    val lastSyncAuthFailed = _lastSyncAuthFailed.asStateFlow()

    /**
     * Mirrors [ActivityCenter.activity]'s [works.merc.keryx.app.domain.ActivitySnapshot.syncing] —
     * true for every sync in progress (manual "sync now" on Home, a debounced sync, the background
     * loop, and the connect-time initial sync this controller itself starts), not only the ones
     * this controller initiates. The cloud-sync tab pairs this with [syncPhase] to show live
     * progress on the connected provider's row.
     */
    private val _syncing = MutableStateFlow(activityCenter.activity.value.syncing)
    val syncing = _syncing.asStateFlow()

    /** Mirrors [SyncRepository.syncPhase] — the step the current (or most recent) sync is on. */
    private val _syncPhase = MutableStateFlow(syncRepository.syncPhase.value)
    val syncPhase = _syncPhase.asStateFlow()

    /** True while [disconnect] is tearing down the connected provider — see that function's KDoc. */
    private val _disconnecting = MutableStateFlow(false)
    val disconnecting = _disconnecting.asStateFlow()

    /**
     * Mirrors [works.merc.keryx.app.domain.ActivitySnapshot.idle] — no refresh or sync running
     * anywhere. Gates [canSyncNow] the same way Home's cloud button is gated.
     */
    private val _idle = MutableStateFlow(activityCenter.activity.value.idle)
    val idle = _idle.asStateFlow()

    /**
     * Whether the cloud-sync tab's "sync now" button is enabled: a provider is connected, nothing
     * else is running (see [idle]), no connect / switch / disconnect / reset is in flight (each
     * would race the sync), and the last sync did not fail on authorization — a sync then would
     * only repeat that failure, and the row's own "reconnect" is the action that fixes it.
     */
    val canSyncNow: StateFlow<Boolean>

    /** [canSyncNow]'s condition, read synchronously — what [syncNow]'s own guard checks. */
    private fun canSyncNowNow(): Boolean =
        _connectedType.value != null && _idle.value && _connectingType.value == null && _initialSyncingType.value == null &&
            !_disconnecting.value && !_resetting.value && !_lastSyncAuthFailed.value && !_manualSyncInFlight.value

    /**
     * True while a manual sync started by [syncNow] is in flight. Prevents a second click from
     * racing past [canSyncNow] before the [ActivityCenter] collector updates [idle]: a redundant
     * call would otherwise queue behind the mutex in [syncRepository.sync] and run a second sync
     * once the first finishes.
     */
    private val _manualSyncInFlight = MutableStateFlow(false)

    init {
        // Derived, not stateIn'd: its value is recomputed from the inputs on every read, so it can
        // never lag them (a stateIn copy would briefly show a stale answer right after an input
        // changed), while collectors still hear about every change.
        val busy = combine(_disconnecting, _resetting, _lastSyncAuthFailed, _manualSyncInFlight) { a, b, c, d -> a || b || c || d }
        canSyncNow = DerivedStateFlow(
            compute = ::canSyncNowNow,
            changes = combine(_connectedType, _idle, _connectingType, _initialSyncingType, busy) { _, _, _, _, _ -> canSyncNowNow() },
        )
    }

    init {
        refreshLastSyncedAt()
        viewModelScope.launch {
            // Skips the subscription-time replay while it still matches what the initializer above
            // already read, so construction does not pay a second secure-store round trip; any
            // later (or already-different) selection re-reads it. collectLatest: a newer selection
            // supersedes a read still in flight, so a slow read can never land after a newer one.
            var skipUnchanged = true
            settingsRepository.localSettings.map { it.cloudStorageType }.distinctUntilChanged().collectLatest { id ->
                val unchanged = skipUnchanged && id == initialCloudStorageTypeId
                skipUnchanged = false
                if (unchanged) return@collectLatest
                _connectedType.value = withContext(dispatcher) { cloudSession.connectedType() }
            }
        }
        viewModelScope.launch {
            syncRepository.lastSyncError.collect { _lastSyncError.value = it }
        }
        viewModelScope.launch {
            syncRepository.lastSyncAuthFailed.collect { _lastSyncAuthFailed.value = it }
        }
        viewModelScope.launch {
            syncRepository.syncPhase.collect { _syncPhase.value = it }
        }
        viewModelScope.launch {
            // Collects the subscription-time replay too, not just later changes: the property
            // initializer above reads activityCenter.activity.value synchronously at construction,
            // but this launch only starts collecting once viewModelScope actually dispatches it,
            // so the StateFlow's value can have moved on in between. Dropping that replay (as a
            // once-tried `drop(1)` did) would silently swallow a real transition happening in that
            // window; collecting it is safe since it just repeats work this controller already does
            // at startup (refreshLastSyncedAt() is a pure, idempotent read).
            activityCenter.activity.map { it.syncing }.distinctUntilChanged().collect { isSyncing ->
                _syncing.value = isSyncing
                // Guarded: a transient read failure must not kill this long-lived collector (which
                // would silently stop all future last-synced refreshes) or leak as an uncaught
                // exception. Best-effort UI state — log and carry on.
                if (!isSyncing) {
                    runCatching { refreshLastSyncedAt() }
                        .onFailure { Log.warn(TAG, "Failed to refresh last-synced time", it) }
                }
            }
        }
        viewModelScope.launch {
            activityCenter.activity.map { it.idle }.distinctUntilChanged().collect { _idle.value = it }
        }
    }

    /**
     * Runs a manual sync — the same [SyncRepository.sync] Home's cloud button triggers. Progress,
     * the new last-synced time and any failure all surface through the state this controller
     * already mirrors ([syncing], [syncPhase], [lastSyncedAtText], [lastSyncError]).
     */
    fun syncNow() {
        if (!canSyncNowNow()) return
        _manualSyncInFlight.value = true
        viewModelScope.launch {
            try {
                withContext(dispatcher) { syncRepository.sync() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.error(TAG, "Manual sync failed", e)
            } finally {
                _manualSyncInFlight.value = false
            }
        }
    }

    /**
     * Connects to the selected cloud storage provider and starts synchronization after successful
     * authorization.
     *
     * Split into two distinct phases against [connectingType] / [initialSyncingType]: OAuth and
     * token save are comparatively quick and cannot yet be exited from mid-flight ([cancelConnect]
     * is the only way out), so every other cloud-storage action on this provider's row stays
     * blocked for that phase. The initial sync that follows is often the longest sync this app ever
     * runs (a first-ever connection merges the *entire* existing cloud database), so it gets its
     * own, looser phase: [initialSyncingType] still blocks "reset"/"switch provider" (both would
     * race the sync still writing to this row), but leaves "disconnect" available throughout, since
     * disconnecting is always a safe exit regardless of what a sync is doing.
     *
     * @param type The cloud storage provider to connect to.
     */
    fun connect(type: CloudStorageType) {
        viewModelScope.launch {
            val connected = try {
                _connectingType.value = type
                _connectFailedType.value = null
                runConnectFlow(type)
            } finally {
                _connectingType.value = null
            }
            if (!connected) return@launch
            _initialSyncingType.value = type
            try {
                withContext(dispatcher) { syncRepository.sync() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.error(TAG, "Initial sync failed", e)
            } finally {
                _initialSyncingType.value = null
            }
        }
    }

    /**
     * Runs the OAuth authorization for [type] and, on success, saves its tokens and marks it
     * connected. Returns whether it succeeded; [connect] runs the initial sync only when it did,
     * and always after this function returns — see [connect]'s KDoc for why sync is deliberately
     * kept out of [connectingType].
     */
    private suspend fun runConnectFlow(type: CloudStorageType): Boolean {
        val flow = cloudSession.connectFlow(type)
        if (flow == null) {
            _connectFailedType.value = type
            return false
        }
        val result = coroutineScope {
            awaitCancellableConnect(
                flow,
                onJobChange = { authorizationJob = it },
                onCanCancelChange = { _canCancelConnect.value = it },
            )
        } ?: return false
        return when (result) {
            is Result.Ok -> {
                // Saves the tokens and flushes the provider selection to disk before [connect]
                // starts the initial sync — see CloudConnectionService.completeConnect.
                withContext(dispatcher) { cloudConnectionService.completeConnect(type, result.value) }
                _connectedType.value = type
                true
            }
            is Result.Err -> {
                _connectFailedType.value = type
                false
            }
        }
    }

    fun cancelConnect() {
        authorizationJob?.cancel()
    }

    /**
     * Disconnects [type] and clears everything a subsequent connect must not inherit: the domain
     * state via [CloudConnectionService.tearDown] (tokens, sync failure reason — so a fresh connect
     * doesn't start out showing the old provider's error — and the persisted provider setting),
     * then this controller's connected state and last-synced timestamp. Shared by [disconnect] and
     * [switchTo], which differ only in what runs before/after this teardown.
     */
    private suspend fun tearDownConnection(type: CloudStorageType) {
        withContext(dispatcher) { cloudConnectionService.tearDown(type) }
        _connectedType.value = null
        _lastSyncedAtText.value = null
    }

    /**
     * Disconnects the connected provider. [disconnecting] covers this whole call, because
     * [tearDownConnection] calls [SyncRepository.clearSyncFailureState], which takes the same
     * mutex a running sync holds (see "Skipping Unchanged Transfers" in sync-architecture.md) — so
     * disconnecting while a sync is in flight waits it out. Without a state to show for that wait,
     * the row would look exactly as stuck as the bug this whole feature exists to fix.
     */
    fun disconnect() {
        val type = _connectedType.value ?: return
        viewModelScope.launch {
            _disconnecting.value = true
            try {
                tearDownConnection(type)
            } finally {
                _disconnecting.value = false
            }
        }
    }

    /**
     * Re-authorizes the connected provider: the same teardown [disconnect] performs, immediately
     * followed by a fresh [connect] to the same provider. Offered in place of a cloud-data reset
     * while [lastSyncAuthFailed] holds (see `CloudSyncTab`).
     *
     * Disconnecting first is what makes this work rather than being a cosmetic wrapper around
     * [connect]: on Android's Google Drive the disconnect is what clears Play services' cached
     * access token, without which the connect would be answered from that cache and quietly
     * succeed with a token the provider has already rejected (see
     * `data/cloud/PlayServicesGoogleDriveAuth.kt`). The same shape as [switchTo], differing only in
     * connecting back to the provider it just tore down.
     */
    fun reconnect() {
        val type = _connectedType.value ?: return
        viewModelScope.launch {
            _connectingType.value = type
            tearDownConnection(type)
            connect(type)
        }
    }

    /**
     * Discards the cloud sync data and re-uploads this device's local DB fresh — recovery for a
     * corrupt / incompatible cloud DB. Errors surface via the notification center (from
     * [SyncRepository]); on success a new sync timestamp is shown.
     */
    fun resetCloudData() {
        if (_connectedType.value == null) return
        viewModelScope.launch {
            _resetting.value = true
            try {
                withContext(dispatcher) { syncRepository.resetCloudData() }
            } finally {
                _resetting.value = false
            }
            refreshLastSyncedAt()
        }
    }

    /**
     * Switches from the currently connected provider to [newType]. Only ever called from the UI
     * once a provider is already connected (`CloudSyncTab`'s onSelect routes a no-provider-yet
     * selection through [connect] directly instead) — the guard below is a no-op for every real
     * caller, matching [resetCloudData]'s own style.
     */
    fun switchTo(newType: CloudStorageType) {
        val oldType = _connectedType.value ?: return
        viewModelScope.launch {
            _connectingType.value = newType
            tearDownConnection(oldType)
            connect(newType)
        }
    }

    private fun refreshLastSyncedAt() {
        _lastSyncedAtText.value = syncRepository.lastSyncedAt()?.let { formatTimestamp(it) }
    }

    private companion object {
        const val TAG = "CloudSyncController"
    }
}

/**
 * A read-only [StateFlow] whose [value] is [compute]d from other state flows on every read, and
 * whose collectors receive [changes] (deduplicated) — a derived value that, unlike one produced by
 * `stateIn`, can never be observed out of step with its inputs.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow<T>(
    private val compute: () -> T,
    private val changes: Flow<T>,
) : StateFlow<T> {
    override val value: T get() = compute()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

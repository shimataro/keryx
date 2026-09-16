package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.opml.OpmlCodec
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.awaitCancellableConnect
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncPhase
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.OpenFileRequest
import works.merc.keryx.app.platform.PlatformFileSelector
import works.merc.keryx.app.platform.SaveFileRequest
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.file_filter_opml
import works.merc.keryx.app.resources.file_overwrite_message
import works.merc.keryx.app.resources.file_overwrite_replace
import works.merc.keryx.app.resources.file_overwrite_title
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_import_opml

import works.merc.keryx.app.ui.home.formatTimestamp
import works.merc.keryx.app.ui.home.groupFeedsByFolder

/** A transient result of an OPML operation, surfaced inline near the action. */
sealed interface OpmlResult {
    data class Imported(val added: Int, val failed: Int) : OpmlResult
    data object Exported : OpmlResult
    data object ExportFailed : OpmlResult
    data object ImportFailed : OpmlResult
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val cloudSession: CloudSession,
    private val syncRepository: SyncRepository,
    private val feedRepository: FeedRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
    private val opmlImporter: OpmlImporter,
    private val updateRepository: UpdateRepository,
    private val activityCenter: ActivityCenter,
    // Token store / sync touch the OS Keychain (macOS shells out to `security`, which may
    // block and show an authorization dialog), so keep them off the Main/EDT dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileSelector: FileSelector = PlatformFileSelector,
) : ViewModel() {

    val localSettings = settingsRepository.localSettings

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> = CloudStorageAvailability.available

    var readTimeoutSeconds by mutableStateOf(settingsRepository.getReadTimeoutSeconds())
        private set

    /** null == unlimited. */
    var cacheRetentionDays by mutableStateOf(settingsRepository.getCacheRetentionDays())
        private set

    /** The currently-connected provider, or null (local-only). At most one at a time. */
    var connectedType by mutableStateOf(cloudSession.connectedType())
        private set

    /** The provider whose connect flow is currently running, or null. */
    var connectingType by mutableStateOf<CloudStorageType?>(null)
        private set

    /**
     * The provider whose connect-time initial sync is currently running, or null. Distinct from
     * [connectingType], which covers only the OAuth-and-token-save half of [connect]: once that
     * half finishes, [connectingType] drops to null and this becomes the connected provider until
     * the sync itself finishes. The cloud-sync tab uses this split to keep the connected row's
     * "disconnect" action available throughout the (potentially long) first sync — it is a safe
     * exit at any point — while still blocking "reset"/"switch provider" until it settles, since
     * either would race the sync that is still writing to the same row.
     */
    var initialSyncingType by mutableStateOf<CloudStorageType?>(null)
        private set

    /** The provider whose last connect attempt failed, or null. */
    var connectFailedType by mutableStateOf<CloudStorageType?>(null)
        private set

    private var authorizationJob: Job? = null

    /** True only while actively waiting on the OAuth browser redirect for [connectingType]. */
    var canCancelConnect by mutableStateOf(false)
        private set

    var opmlResult by mutableStateOf<OpmlResult?>(null)

    /** True while an OPML import is running (a native file dialog then per-feed fetches). */
    var importingOpml by mutableStateOf(false)
        private set

    /** True while an OPML export is running. */
    var exportingOpml by mutableStateOf(false)
        private set

    /** The in-app update's state machine (idle/checking/available/downloading/…), shared
     * process-wide via [UpdateRepository] — the tray and notification center read the same
     * instance. */
    val updateState: StateFlow<UpdateState> = updateRepository.state

    /** True while a "reset cloud data" (delete + fresh re-upload) is running. */
    var resetting by mutableStateOf(false)
        private set

    /** Timestamp of the last successful sync, formatted for display. null when never synced or not connected. */
    var lastSyncedAtText by mutableStateOf<String?>(null)
        private set

    /**
     * Why the last sync failed, or null when sync is healthy. Mirrors [SyncRepository.lastSyncError],
     * so the cloud-sync tab shows the current reason even after the notification was dismissed.
     * Distinct from [connectFailedType], which only covers a failed connect (OAuth) flow.
     */
    var lastSyncErrorText by mutableStateOf<String?>(null)
        private set

    /**
     * Whether [lastSyncErrorText] is an authentication failure specifically. Mirrors
     * [SyncRepository.lastSyncAuthFailed]; see that property for why it travels separately from the
     * message rather than being matched out of it.
     */
    var lastSyncAuthFailed by mutableStateOf(false)
        private set

    /**
     * Mirrors [ActivityCenter.syncing] — true for every sync in progress (manual "sync now" on
     * Home, a debounced sync, the background loop, and the connect-time initial sync this
     * ViewModel itself starts), not only the ones this ViewModel initiates. The cloud-sync tab
     * pairs this with [syncPhase] to show live progress on the connected provider's row.
     */
    var syncing by mutableStateOf(activityCenter.syncing.value)
        private set

    /** Mirrors [SyncRepository.syncPhase] — the step the current (or most recent) sync is on. */
    var syncPhase by mutableStateOf(syncRepository.syncPhase.value)
        private set

    /** True while [disconnect] is tearing down the connected provider — see that function's KDoc. */
    var disconnecting by mutableStateOf(false)
        private set

    init {
        refreshLastSyncedAt()
        viewModelScope.launch {
            syncRepository.lastSyncError.collect { lastSyncErrorText = it }
        }
        viewModelScope.launch {
            syncRepository.lastSyncAuthFailed.collect { lastSyncAuthFailed = it }
        }
        viewModelScope.launch {
            syncRepository.syncPhase.collect { syncPhase = it }
        }
        viewModelScope.launch {
            // Skip the initial replay (current state at VM creation) — already handled by the
            // property initializer above. Only react to genuine sync completions afterward,
            // covering sync paths this ViewModel has no other visibility into (manual "sync now" on
            // Home, debounced syncs, the background loop).
            activityCenter.syncing.drop(1).collect { isSyncing ->
                syncing = isSyncing
                // Guarded: a transient read failure must not kill this long-lived collector (which
                // would silently stop all future last-synced refreshes) or leak as an uncaught
                // exception. Best-effort UI state — log and carry on.
                if (!isSyncing) {
                    runCatching { refreshLastSyncedAt() }
                        .onFailure { Log.warn(TAG, "Failed to refresh last-synced time", it) }
                }
            }
        }
    }

    private fun update(transform: (LocalSettings) -> LocalSettings) {
        settingsRepository.mutateLocalSettings(transform)
    }

    /**
     * Updates the selected theme mode.
     *
     * @param mode The theme mode to apply.
     */
    fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }
    fun setFontScale(scale: Double) = update { it.copy(fontSizeScale = scale) }
    fun setRefreshIntervalMinutes(minutes: Int) = update { it.copy(refreshIntervalMinutes = minutes) }
    fun setNotificationEnabled(enabled: Boolean) = update { it.copy(notificationEnabled = enabled) }
    fun setStartMinimized(enabled: Boolean) = update { it.copy(startMinimized = enabled) }
    fun setUpdateCheckIntervalHours(hours: Int) = update { it.copy(updateCheckIntervalHours = hours) }

    /**
     * Manual "check for update" (Updates tab). Deliberately does not touch
     * [LocalSettings.lastUpdateCheckAt] — that timestamp belongs to the automatic
     * startup/background schedule (see main.kt's `checkForUpdateAndNotify`), so a manual check
     * never perturbs it. A no-op while [updateState] is already [UpdateState.Checking].
     */
    fun checkForUpdate() {
        if (updateState.value is UpdateState.Checking) return
        viewModelScope.launch(dispatcher) { updateRepository.check() }
    }

    /** Starts downloading the update currently reported by [updateState], if one can be installed
     * here. See [UpdateRepository.startDownload]. */
    fun startDownload() = updateRepository.startDownload()

    /** Cancels an in-progress download started by [startDownload]. */
    fun cancelDownload() = updateRepository.cancelDownload()

    /** Hands the current [UpdateState.Ready] download off to the OS installer. */
    fun installUpdate() = updateRepository.install()

    fun updateReadTimeout(seconds: Int) {
        settingsRepository.setReadTimeoutSeconds(seconds)
        readTimeoutSeconds = seconds
    }

    fun updateCacheRetention(days: Int?) {
        settingsRepository.setCacheRetentionDays(days)
        cacheRetentionDays = days
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
                connectingType = type
                connectFailedType = null
                runConnectFlow(type)
            } finally {
                connectingType = null
            }
            if (!connected) return@launch
            initialSyncingType = type
            try {
                withContext(dispatcher) { syncRepository.sync() }
            } finally {
                initialSyncingType = null
            }
        }
    }

    /**
     * Runs the OAuth authorization for [type] and, on success, saves its tokens and marks it
     * connected. Returns whether it succeeded; [connect] runs the initial sync only when it did,
     * and always after this function returns — see [connect]'s KDoc for why sync is deliberately
     * kept out of [connectingType].
     */
    private suspend fun CoroutineScope.runConnectFlow(type: CloudStorageType): Boolean {
        val flow = cloudSession.connectFlow(type)
        if (flow == null) {
            connectFailedType = type
            return false
        }
        val result = awaitCancellableConnect(
            flow,
            onJobChange = { authorizationJob = it },
            onCanCancelChange = { canCancelConnect = it },
        ) ?: return false
        return when (result) {
            is Result.Ok -> {
                withContext(dispatcher) { cloudSession.saveTokens(type, result.value) }
                update { it.copy(cloudStorageType = type.id) }
                // Persist the provider selection to disk before the initial sync. Tokens are
                // saved durably to the keychain above, so without this flush a crash could leave
                // tokens present but cloudStorageType null → every later sync a silent no-op.
                withContext(dispatcher) { settingsRepository.flush() }
                connectedType = type
                true
            }
            is Result.Err -> {
                connectFailedType = type
                false
            }
        }
    }

    fun cancelConnect() {
        authorizationJob?.cancel()
    }

    /**
     * Disconnects [type] and clears everything a subsequent connect must not inherit: the sync
     * failure reason (so a fresh connect doesn't start out showing the old provider's error), the
     * persisted provider setting, and the last-synced timestamp. Shared by [disconnect] and
     * [switchTo], which differ only in what runs before/after this teardown.
     */
    private suspend fun tearDownConnection(type: CloudStorageType) {
        withContext(dispatcher) { cloudSession.disconnect(type) }
        syncRepository.clearSyncFailureState()
        update { it.copy(cloudStorageType = null) }
        connectedType = null
        lastSyncedAtText = null
    }

    /**
     * Disconnects the connected provider. [disconnecting] covers this whole call, because
     * [tearDownConnection] calls [SyncRepository.clearSyncFailureState], which takes the same
     * mutex a running sync holds (see "Skipping Unchanged Transfers" in sync-architecture.md) — so
     * disconnecting while a sync is in flight waits it out. Without a state to show for that wait,
     * the row would look exactly as stuck as the bug this whole feature exists to fix.
     */
    fun disconnect() {
        val type = connectedType ?: return
        viewModelScope.launch {
            disconnecting = true
            try {
                tearDownConnection(type)
            } finally {
                disconnecting = false
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
        val type = connectedType ?: return
        viewModelScope.launch {
            connectingType = type
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
        if (connectedType == null) return
        viewModelScope.launch {
            resetting = true
            try {
                withContext(dispatcher) { syncRepository.resetCloudData() }
            } finally {
                resetting = false
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
        val oldType = connectedType ?: return
        viewModelScope.launch {
            connectingType = newType
            tearDownConnection(oldType)
            connect(newType)
        }
    }

    private fun refreshLastSyncedAt() {
        lastSyncedAtText = syncRepository.lastSyncedAt()?.let { formatTimestamp(it) }
    }

    /**
     * Exports subscribed feeds, folders, and tags to a user-selected OPML file.
     *
     * Updates [opmlResult] to [OpmlResult.Exported] on success, [OpmlResult.ExportFailed] on failure,
     * or `null` if the user cancels the file picker.
     */
    fun exportOpml() {
        if (exportingOpml || importingOpml) return
        viewModelScope.launch {
            exportingOpml = true
            try {
                val request = SaveFileRequest(
                    title = getString(Res.string.settings_export_opml),
                    defaultName = "keryx.opml",
                    overwriteTitle = getString(Res.string.file_overwrite_title),
                    overwriteMessage = getString(Res.string.file_overwrite_message),
                    overwriteReplaceLabel = getString(Res.string.file_overwrite_replace),
                    overwriteCancelLabel = getString(Res.string.common_cancel),
                )
                val target = fileSelector.pickSaveFile(request)
                if (target == null) {
                    opmlResult = null
                    return@launch
                }
                opmlResult = try {
                    withContext(dispatcher) { target.writeText(buildOpmlDocument()) }
                    OpmlResult.Exported
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.warn(TAG, "Failed to export OPML", e)
                    OpmlResult.ExportFailed
                }
            } finally {
                exportingOpml = false
            }
        }
    }

    /**
     * Builds an OPML document containing the current subscriptions, organized by folder and annotated with tags.
     *
     * @return The serialized OPML document.
     */
    internal fun buildOpmlDocument(): String {
        val feeds = feedRepository.getAllFeeds()
        val folders = folderRepository.getAllFolders()
        val allTags = tagRepository.getAllTags() // already in display order
        val feedTagMap = tagRepository.getFeedTagMap()
        val groups = groupFeedsByFolder(feeds, folders)
            .map { (folder, groupFeeds) ->
                folder?.name to groupFeeds.map { feed ->
                    val tagIds = feedTagMap[feed.id].orEmpty()
                    OpmlCodec.ExportFeed(
                        title = feed.displayTitle(),
                        xmlUrl = feed.url,
                        htmlUrl = feed.site_url,
                        tags = allTags.filter { it.id in tagIds }.map { it.name },
                    )
                }
            }
            .filter { (_, groupFeeds) -> groupFeeds.isNotEmpty() }
        return OpmlCodec.export(groups)
    }

    /**
     * Imports feeds, folders, and tags from a selected OPML or XML file.
     */
    fun importOpml() {
        if (importingOpml || exportingOpml) return
        viewModelScope.launch {
            importingOpml = true
            try {
                val request = OpenFileRequest(
                    title = getString(Res.string.settings_import_opml),
                    extensions = listOf("opml", "xml"),
                    filterLabel = getString(Res.string.file_filter_opml),
                )
                val source = fileSelector.pickOpenFile(request)
                if (source == null) {
                    opmlResult = null
                    return@launch
                }
                opmlResult = try {
                    val outcome = withContext(dispatcher) { source.readText()?.let { opmlImporter.import(it) } }
                    if (outcome == null) OpmlResult.ImportFailed else OpmlResult.Imported(outcome.added, outcome.failed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.warn(TAG, "Failed to import OPML", e)
                    OpmlResult.ImportFailed
                }
            } finally {
                importingOpml = false
            }
        }
    }

    /**
     * Clears the latest OPML import or export result.
     */
    fun clearOpmlResult() {
        opmlResult = null
    }

    private companion object {
        const val TAG = "SettingsVM"
    }
}

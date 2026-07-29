package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FilePicker
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_import_opml

import works.merc.keryx.app.ui.home.formatTimestamp
import works.merc.keryx.app.ui.home.groupFeedsByFolder

/** A transient result of an OPML operation, surfaced inline near the action. */
sealed interface OpmlResult {
    data class Imported(val added: Int, val failed: Int) : OpmlResult
    data object Exported : OpmlResult
    data object Cancelled : OpmlResult
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val cloudSession: CloudSession,
    private val syncRepository: SyncRepository,
    private val feedRepository: FeedRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
    private val updateChecker: UpdateChecker,
    private val activityCenter: ActivityCenter,
    // Token store / sync touch the OS Keychain (macOS shells out to `security`, which may
    // block and show an authorization dialog), so keep them off the Main/EDT dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    var checkingForUpdate by mutableStateOf(false)
        private set

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

    /** Set by [checkForUpdate]. Does not affect the automatic update-check schedule. */
    var updateCheckResult by mutableStateOf<UpdateStatus?>(null)
        private set

    init {
        refreshLastSyncedAt()
        viewModelScope.launch {
            syncRepository.lastSyncError.collect { lastSyncErrorText = it }
        }
        viewModelScope.launch {
            // Skip the initial replay (current state at VM creation) — already handled by the
            // explicit call above. Only react to genuine sync completions afterward, covering
            // sync paths this ViewModel has no other visibility into (manual "sync now" on Home,
            // debounced syncs, the background loop).
            activityCenter.syncing.drop(1).collect { syncing ->
                // Guarded: a transient read failure must not kill this long-lived collector (which
                // would silently stop all future last-synced refreshes) or leak as an uncaught
                // exception. Best-effort UI state — log and carry on.
                if (!syncing) {
                    runCatching { refreshLastSyncedAt() }
                        .onFailure { Log.warn(TAG, "Failed to refresh last-synced time", it) }
                }
            }
        }
    }

    private fun update(transform: (LocalSettings) -> LocalSettings) {
        settingsRepository.saveLocalSettings(transform(settingsRepository.getLocalSettings()))
    }

    fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }
    fun setFontScale(scale: Double) = update { it.copy(fontSizeScale = scale) }
    fun setRefreshIntervalMinutes(minutes: Int) = update { it.copy(refreshIntervalMinutes = minutes) }
    fun setNotificationEnabled(enabled: Boolean) = update { it.copy(notificationEnabled = enabled) }
    fun setStartMinimized(enabled: Boolean) = update { it.copy(startMinimized = enabled) }
    fun setUpdateCheckIntervalHours(hours: Int) = update { it.copy(updateCheckIntervalHours = hours) }

    /**
     * Manual "check for update" (About section). Deliberately does not touch
     * [LocalSettings.lastUpdateCheckAt] — that timestamp belongs to the automatic
     * startup/background schedule (see main.kt's `checkForUpdateAndNotify`), so a manual check
     * never perturbs it.
     */
    fun checkForUpdate() {
        if (checkingForUpdate) return
        viewModelScope.launch {
            checkingForUpdate = true
            updateCheckResult = updateChecker.check()
            checkingForUpdate = false
        }
    }

    fun updateReadTimeout(seconds: Int) {
        settingsRepository.setReadTimeoutSeconds(seconds)
        readTimeoutSeconds = seconds
    }

    fun updateCacheRetention(days: Int?) {
        settingsRepository.setCacheRetentionDays(days)
        cacheRetentionDays = days
    }

    fun connect(type: CloudStorageType) {
        viewModelScope.launch {
            connectingType = type
            connectFailedType = null
            val flow = cloudSession.connectFlow(type)
            if (flow == null) {
                connectFailedType = type
                connectingType = null
                return@launch
            }
            // Run only the interruptible OAuth-authorization wait as a child job. Cancelling a
            // child does not propagate up to the parent (structured concurrency), so the success
            // tail (saveTokens -> update settings -> sync) runs to completion once authorization
            // resolves — never leaving durable tokens/settings behind a cancelled UI.
            val waitJob = async { flow.connect() }
            authorizationJob = waitJob
            canCancelConnect = true
            val result = try {
                waitJob.await()
            } catch (e: CancellationException) {
                connectingType = null
                return@launch
            } finally {
                authorizationJob = null
                canCancelConnect = false
            }
            when (result) {
                is Result.Ok -> {
                    withContext(dispatcher) { cloudSession.saveTokens(type, result.value) }
                    update { it.copy(cloudStorageType = type.id) }
                    // Persist the provider selection to disk before the initial sync. Tokens are
                    // saved durably to the keychain above, so without this flush a crash could leave
                    // tokens present but cloudStorageType null → every later sync a silent no-op.
                    withContext(dispatcher) { settingsRepository.flush() }
                    connectedType = type
                    withContext(dispatcher) { syncRepository.sync() }
                }
                is Result.Err -> connectFailedType = type
            }
            connectingType = null
        }
    }

    fun cancelConnect() {
        authorizationJob?.cancel()
    }

    fun disconnect() {
        val type = connectedType ?: return
        viewModelScope.launch {
            withContext(dispatcher) { cloudSession.disconnect(type) }
            // Clear before exposing the disconnect, so a subsequent connect (to this or another
            // provider) never inherits this provider's stale failure reason.
            syncRepository.clearLastSyncError()
            update { it.copy(cloudStorageType = null) }
            connectedType = null
            lastSyncedAtText = null
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

    fun switchTo(newType: CloudStorageType) {
        val oldType = connectedType ?: return connect(newType)
        viewModelScope.launch {
            connectingType = newType
            withContext(dispatcher) { cloudSession.disconnect(oldType) }
            // Clear before connecting the new provider, so it never inherits the old provider's
            // stale failure reason.
            syncRepository.clearLastSyncError()
            update { it.copy(cloudStorageType = null) }
            connectedType = null
            lastSyncedAtText = null
            connect(newType)
        }
    }

    private fun refreshLastSyncedAt() {
        lastSyncedAtText = syncRepository.lastSyncedAt()?.let { formatTimestamp(it) }
    }

    /**
     * Exports subscribed feeds, including their folders and tags, to an OPML file selected by the user.
     *
     * Updates the OPML result to indicate whether the export completed or was canceled.
     */
    fun exportOpml() {
        if (exportingOpml || importingOpml) return
        viewModelScope.launch {
            exportingOpml = true
            try {
                val path = FilePicker.pickSaveFile(getString(Res.string.settings_export_opml), "keryx.opml")
                if (path == null) {
                    opmlResult = OpmlResult.Cancelled
                    return@launch
                }
                FileIO.writeText(path, buildOpmlDocument())
                opmlResult = OpmlResult.Exported
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
                val path = FilePicker.pickOpenFile(getString(Res.string.settings_import_opml), listOf("opml", "xml"))
                if (path == null) {
                    opmlResult = OpmlResult.Cancelled
                    return@launch
                }
                val xml = FileIO.readText(path) ?: run {
                    opmlResult = OpmlResult.Cancelled
                    return@launch
                }
                opmlResult = applyOpmlDocument(xml)
            } finally {
                importingOpml = false
            }
        }
    }

    /**
     * Imports feeds from an OPML document and synchronizes their folders and tags.
     *
     * @param xml The OPML document to import.
     * @return The number of feeds added and the number of subscriptions that failed.
     */
    internal suspend fun applyOpmlDocument(xml: String): OpmlResult.Imported {
        // Each distinct folder / tag name is resolved once per import run, not once per feed:
        // FolderRepository.createFolder re-appends an already-active folder to the end of the folder
        // sort order and bumps its updated_at on every call, so calling it per feed would reshuffle
        // folder order and emit needless sync writes.
        val folderIdByName = mutableMapOf<String, String>()
        val tagIdByName = mutableMapOf<String, String>()
        // Snapshot of the pre-import attachments, so each feed's tag diff below is computed against
        // the state before this run started changing things.
        val previousFeedTagMap = tagRepository.getFeedTagMap()
        var added = 0
        var failed = 0
        for (entry in OpmlCodec.import(xml)) {
            when (val subscribed = feedRepository.subscribeFeed(entry.xmlUrl)) {
                is Result.Ok -> {
                    added++
                    val feed = subscribed.value
                    val folderId = entry.folderName?.let { name ->
                        folderIdByName.getOrPut(name) { folderRepository.createFolder(name) }
                    }
                    // Guarded so a re-import that changes nothing writes nothing.
                    if (feed.folder_id != folderId) feedRepository.moveFeed(feed.id, folderId)

                    val newTagIds = entry.tags
                        .map { name -> tagIdByName.getOrPut(name) { tagRepository.createTag(name) } }
                        .toSet()
                    val currentTagIds = previousFeedTagMap[feed.id].orEmpty()
                    (currentTagIds - newTagIds).forEach { tagRepository.setFeedTag(feed.id, it, false) }
                    (newTagIds - currentTagIds).forEach { tagRepository.setFeedTag(feed.id, it, true) }
                }
                is Result.Err -> failed++
            }
        }
        return OpmlResult.Imported(added, failed)
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

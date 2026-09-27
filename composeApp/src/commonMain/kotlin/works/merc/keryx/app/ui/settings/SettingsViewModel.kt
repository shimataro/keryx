package works.merc.keryx.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.PlatformFileSelector
import works.merc.keryx.app.presentation.settings.CloudSyncController
import works.merc.keryx.app.presentation.settings.OpmlTransfer
import works.merc.keryx.app.presentation.settings.PreferencesController

/** A transient result of an OPML operation, surfaced inline near the action. */
sealed interface OpmlResult {
    data class Imported(val added: Int, val failed: Int) : OpmlResult
    data object Exported : OpmlResult
    data object ExportFailed : OpmlResult
    data object ImportFailed : OpmlResult
}

/**
 * The Compose settings screen's own ViewModel — a thin wrapper around the shared
 * [CloudSyncController] / [PreferencesController] / [OpmlTransfer], plus the two things that stay
 * Compose/desktop-only: the in-app updater ([UpdateRepository], since the SwiftUI app updates
 * through the App Store or Sparkle instead) and OPML file picking (native file dialogs are
 * platform-specific; [OpmlTransfer] only builds/parses the document itself).
 */
class SettingsViewModel(
    private val cloudSyncController: CloudSyncController,
    private val preferencesController: PreferencesController,
    private val opmlTransfer: OpmlTransfer,
    private val updateRepository: UpdateRepository,
    // Token store / sync touch the OS Keychain (macOS shells out to `security`, which may
    // block and show an authorization dialog), so keep them off the Main/EDT dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileSelector: FileSelector = PlatformFileSelector,
    private val opmlFileRequests: OpmlFileRequests = ComposeOpmlFileRequests,
) : ViewModel() {

    // --- Preferences (delegates to PreferencesController) ---

    val localSettings = preferencesController.localSettings
    val readTimeoutSeconds = preferencesController.readTimeoutSeconds
    val cacheRetentionDays = preferencesController.cacheRetentionDays

    fun setThemeMode(mode: String) = preferencesController.setThemeMode(mode)
    fun setFontScale(scale: Double) = preferencesController.setFontScale(scale)
    fun setRefreshIntervalMinutes(minutes: Int) = preferencesController.setRefreshIntervalMinutes(minutes)
    fun setNotificationEnabled(enabled: Boolean) = preferencesController.setNotificationEnabled(enabled)
    fun setStartMinimized(enabled: Boolean) = preferencesController.setStartMinimized(enabled)
    fun setUpdateCheckIntervalHours(hours: Int) = preferencesController.setUpdateCheckIntervalHours(hours)
    fun updateReadTimeout(seconds: Int) = preferencesController.updateReadTimeout(seconds)
    fun updateCacheRetention(days: Int?) = preferencesController.updateCacheRetention(days)

    // --- Cloud sync (delegates to CloudSyncController) ---

    val availableCloudTypes: List<CloudStorageType> = cloudSyncController.availableCloudTypes
    val connectedType = cloudSyncController.connectedType
    val connectingType = cloudSyncController.connectingType
    val initialSyncingType = cloudSyncController.initialSyncingType
    val connectFailedType = cloudSyncController.connectFailedType
    val canCancelConnect = cloudSyncController.canCancelConnect
    val resetting = cloudSyncController.resetting
    val lastSyncedAtText = cloudSyncController.lastSyncedAtText
    val lastSyncError = cloudSyncController.lastSyncError
    val lastSyncAuthFailed = cloudSyncController.lastSyncAuthFailed
    val syncing = cloudSyncController.syncing
    val syncPhase = cloudSyncController.syncPhase
    val disconnecting = cloudSyncController.disconnecting
    val idle = cloudSyncController.idle
    val canSyncNow = cloudSyncController.canSyncNow

    fun syncNow() = cloudSyncController.syncNow()
    fun connect(type: CloudStorageType) = cloudSyncController.connect(type)
    fun cancelConnect() = cloudSyncController.cancelConnect()
    fun disconnect() = cloudSyncController.disconnect()
    fun reconnect() = cloudSyncController.reconnect()
    fun resetCloudData() = cloudSyncController.resetCloudData()
    fun switchTo(newType: CloudStorageType) = cloudSyncController.switchTo(newType)

    // --- In-app update (Compose/desktop-only; the SwiftUI app updates via the App Store/Sparkle) ---

    /** The in-app update's state machine (idle/checking/available/downloading/…), shared
     * process-wide via [UpdateRepository] — the tray and notification center read the same
     * instance. */
    val updateState: StateFlow<UpdateState> = updateRepository.state

    /**
     * Manual "check for update" (Updates tab). Deliberately does not touch
     * [works.merc.keryx.app.data.local.LocalSettings.lastUpdateCheckAt] — that timestamp belongs to
     * the automatic startup/background schedule (see main.kt's `checkForUpdateAndNotify`), so a
     * manual check never perturbs it. A no-op while [updateState] is already [UpdateState.Checking].
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

    // --- OPML (file picking + busy state stay here; the byte-level work is OpmlTransfer's) ---

    private val _opmlResult = MutableStateFlow<OpmlResult?>(null)
    val opmlResult: StateFlow<OpmlResult?> = _opmlResult.asStateFlow()

    /** True while an OPML import is running (a native file dialog then per-feed fetches). */
    private val _importingOpml = MutableStateFlow(false)
    val importingOpml = _importingOpml.asStateFlow()

    /** True while an OPML export is running. */
    private val _exportingOpml = MutableStateFlow(false)
    val exportingOpml = _exportingOpml.asStateFlow()

    /**
     * Exports subscribed feeds, folders, and tags to a user-selected OPML file.
     *
     * Updates [opmlResult] to [OpmlResult.Exported] on success, [OpmlResult.ExportFailed] on failure,
     * or `null` if the user cancels the file picker.
     */
    fun exportOpml() {
        if (_exportingOpml.value || _importingOpml.value) return
        viewModelScope.launch {
            _exportingOpml.value = true
            try {
                val target = fileSelector.pickSaveFile(opmlFileRequests.export())
                if (target == null) {
                    _opmlResult.value = null
                    return@launch
                }
                _opmlResult.value = try {
                    withContext(dispatcher) { target.writeText(opmlTransfer.exportOpml()) }
                    OpmlResult.Exported
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.warn(TAG, "Failed to export OPML", e)
                    OpmlResult.ExportFailed
                }
            } finally {
                _exportingOpml.value = false
            }
        }
    }

    /**
     * Imports feeds, folders, and tags from a selected OPML or XML file.
     */
    fun importOpml() {
        if (_importingOpml.value || _exportingOpml.value) return
        viewModelScope.launch {
            _importingOpml.value = true
            try {
                val source = fileSelector.pickOpenFile(opmlFileRequests.import())
                if (source == null) {
                    _opmlResult.value = null
                    return@launch
                }
                _opmlResult.value = try {
                    val outcome = withContext(dispatcher) { source.readText()?.let { opmlTransfer.importOpml(it) } }
                    if (outcome == null) OpmlResult.ImportFailed else OpmlResult.Imported(outcome.added, outcome.failed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.warn(TAG, "Failed to import OPML", e)
                    OpmlResult.ImportFailed
                }
            } finally {
                _importingOpml.value = false
            }
        }
    }

    /**
     * Clears the latest OPML import or export result.
     */
    fun clearOpmlResult() {
        _opmlResult.value = null
    }

    private companion object {
        const val TAG = "SettingsVM"
    }
}

package works.merc.keryx.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.PickedFile
import works.merc.keryx.app.platform.PlatformFileSelector
import works.merc.keryx.app.presentation.settings.CloudSyncController
import works.merc.keryx.app.presentation.settings.OpmlOperation
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlResult
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import works.merc.keryx.app.presentation.settings.PreferencesController

/**
 * The Compose settings screen's own ViewModel — a thin wrapper around the shared
 * [CloudSyncController] / [PreferencesController] / [OpmlTransferController], plus the two things
 * that stay Compose/desktop-only: the in-app updater ([UpdateRepository], since the SwiftUI app
 * updates through the App Store or Sparkle instead) and OPML file picking (native file dialogs are
 * platform-specific; [OpmlTransferController] owns the busy/result state and the document work).
 */
class SettingsViewModel(
    private val cloudSyncController: CloudSyncController,
    private val preferencesController: PreferencesController,
    private val opmlController: OpmlTransferController,
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
     * manual check never perturbs it.
     *
     * Every user-initiated route goes through here — the tab's "check now" button, the tab's own
     * check on open (`UpdatesTab.kt`'s `shouldAutoCheckOnOpen`), and the tray/Help menu's update
     * entry (`main.kt`'s `onUpdateMenuItemClicked`). A no-op while one of those is still in flight:
     * [checkInFlight] is claimed synchronously, before the launched check has had a chance to move
     * [updateState] to [UpdateState.Checking], so the menu entry's check followed at once by the tab
     * opening (and auto-checking) cannot start a second one. A check already running from the
     * automatic schedule ([updateState] is [UpdateState.Checking]) is left alone too.
     */
    fun checkForUpdate() {
        if (updateState.value is UpdateState.Checking) return
        if (!checkInFlight.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch(dispatcher) {
            try {
                updateRepository.check()
            } finally {
                checkInFlight.value = false
            }
        }
    }

    /** Set from the moment [checkForUpdate] starts a check until it finishes — see its KDoc. */
    private val checkInFlight = MutableStateFlow(false)

    /** Starts downloading the update currently reported by [updateState], if one can be installed
     * here. See [UpdateRepository.startDownload]. */
    fun startDownload() = updateRepository.startDownload()

    /** Cancels an in-progress download started by [startDownload]. */
    fun cancelDownload() = updateRepository.cancelDownload()

    /** Hands the current [UpdateState.Ready] download off to the OS installer. */
    fun installUpdate() = updateRepository.install()

    // --- OPML (file picking stays here; busy/result/requests are OpmlTransferController's) ---

    /** The last OPML operation's outcome, until the Data tab shows it ([clearOpmlResult]). */
    val opmlResult: StateFlow<OpmlResult?> = opmlController.result

    /** Whether any OPML operation is running, whichever route started it. */
    val opmlBusy: StateFlow<Boolean> = opmlController.busy

    /** The running OPML operation (for the matching button's spinner), or `null`. */
    val opmlRunning: StateFlow<OpmlOperation?> = opmlController.running

    /** An import/export asked for from the File menu or an opened `.opml` file, waiting for the Data tab. */
    val pendingOpmlRequest: StateFlow<OpmlRequest?> = opmlController.pendingRequest

    /** Takes the waiting [pendingOpmlRequest] when nothing is running (see [OpmlTransferController.consumeRequest]). */
    fun consumeOpmlRequest(): OpmlRequest? = opmlController.consumeRequest()

    /**
     * Exports subscribed feeds, folders, and tags to a user-selected OPML file.
     *
     * Finishes with [OpmlResult.Exported] on success, [OpmlResult.ExportFailed] on failure, or `null`
     * if the user cancels the file picker. A no-op while any OPML operation is running.
     */
    fun exportOpml() {
        if (!opmlController.tryBegin(OpmlOperation.Exporting)) return
        viewModelScope.launch {
            var result: OpmlResult? = null
            try {
                val target = fileSelector.pickSaveFile(opmlFileRequests.export()) ?: return@launch
                result = try {
                    withContext(dispatcher) { target.writeText(opmlController.exportDocument()) }
                    OpmlResult.Exported
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.warn(TAG, "Failed to export OPML", e)
                    OpmlResult.ExportFailed
                }
            } finally {
                opmlController.finish(result)
            }
        }
    }

    /**
     * Imports feeds, folders, and tags from a selected OPML or XML file. A no-op while any OPML
     * operation is running.
     */
    fun importOpml() {
        if (!opmlController.tryBegin(OpmlOperation.Importing)) return
        viewModelScope.launch {
            var result: OpmlResult? = null
            try {
                val source = fileSelector.pickOpenFile(opmlFileRequests.import()) ?: return@launch
                // Only the read hops here; importResult dispatches the import itself.
                val xml = withContext(dispatcher) { readOrNull(source) }
                result = opmlController.importResult(xml)
            } finally {
                opmlController.finish(result)
            }
        }
    }

    /**
     * Imports an already-read OPML document (an `.opml` file the app was opened with); `null` [xml]
     * means reading it failed and finishes with [OpmlResult.ImportFailed]. A no-op while any OPML
     * operation is running. The whole run is [OpmlTransferController.importDocument], which the
     * SwiftUI app's `OpmlTransferObservable.importDocument(xml:)` calls too. (The file-picker path,
     * [importOpml], is shared at the level of [OpmlTransferController.tryBegin] /
     * [OpmlTransferController.importResult] / [OpmlTransferController.finish] instead, because the
     * operation must be held before the picker opens — the SwiftUI panel path does the same.)
     */
    fun importDocument(xml: String?) {
        viewModelScope.launch { opmlController.importDocument(xml) }
    }

    /** [PickedFile.readText], with a read failure (other than cancellation) logged and mapped to `null`. */
    private suspend fun readOrNull(source: PickedFile): String? = try {
        source.readText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.warn(TAG, "Failed to read the picked OPML file", e)
        null
    }

    /** Clears the latest OPML import or export result once it has been shown. */
    fun clearOpmlResult() = opmlController.clearResult()

    private companion object {
        const val TAG = "SettingsVM"
    }
}

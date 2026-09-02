package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSystemExtras
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.detectInstallLocation

private const val TAG = "UpdateRepository"

/** Free space an in-app update download requires as a multiple of the asset size — headroom for
 * the `.part` file, the final renamed copy briefly coexisting with it, and general safety margin. */
private const val REQUIRED_FREE_SPACE_MULTIPLE = 3

/**
 * Orchestrates an in-app update end to end — checking, downloading, verifying, and handing off to
 * [installer] — behind a single [state] every UI surface (tray, notification center, the Updates
 * settings tab) reads from. A Koin `single`, so [state] and any in-flight download outlive whatever
 * UI happened to start it (a closed settings dialog does not cancel a download).
 *
 * Also owns the update-related notification-center row: it posts "an update is available" when
 * [check] finds one and "ready to install" once a download finishes, replacing the former with the
 * latter rather than leaving both — see [postNotification]. This applies equally whether the
 * update was found by [works.merc.keryx.app.domain.checkForUpdateAndNotify]'s background schedule
 * or a manual "check for update" in Settings; unlike [LocalSettings.lastUpdateCheckAt] (which only
 * the background path touches), there's no established asymmetry here to preserve — either trigger
 * finding an update is equally worth telling the user about, and [NotificationCenter.addCoalescing]
 * keeps repeated finds of the same version from piling up.
 *
 * [scope] should be an app-lifetime scope (see [works.merc.keryx.app.di.AppModule]'s shared
 * `CoroutineScope`), not a UI-scoped one — that is precisely what lets the download survive the
 * settings dialog closing.
 */
class UpdateRepository(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val notificationCenter: NotificationCenter,
    private val notificationMessages: NotificationMessages,
    private val scope: CoroutineScope,
    private val location: InstallLocation = detectInstallLocation(),
    // Lets a test point at a temp directory, the same way LocalSettingsStore's dirOverride does.
    private val cacheDirOverride: String? = null,
) {
    private val cacheDir: String get() = cacheDirOverride ?: AppDirs.cacheDir()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // replay = 0 so a later subscriber can never be handed a past "quit now" and act on it out of
    // nowhere; extraBufferCapacity = 1 so emit() never suspends when nothing is collecting at all
    // (Android has no subscriber — a rendezvous SharedFlow would hang install() there forever).
    private val _installLaunched = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    /**
     * Fires once the OS-level install has actually been handed off ([InstallLaunchResult.Launched]),
     * and is the **only** signal desktop may exit the app on — see [install].
     *
     * [UpdateState.Installing] deliberately does not mean that: it is set the moment the install
     * *starts*, while [installer] is still extracting/staging, purely so the UI can show its
     * "restarting…" feedback. Exiting on the state value instead is what made an install quit the
     * app before the self-replace script had even been written, let alone launched.
     */
    val installLaunched: SharedFlow<Unit> = _installLaunched.asSharedFlow()

    // Guards only the *decision* to start/transition, never the download itself — holding it across
    // the whole download would make cancelDownload() (a plain, non-suspend call) unable to safely
    // inspect/replace downloadJob while a check() is mid-flight. See this class's own methods below.
    private val mutex = Mutex()
    private var downloadJob: Job? = null

    /** The notification-center row this repository most recently posted, so a later state (e.g.
     * "ready to install") can replace it instead of leaving both rows behind — see this class's own
     * KDoc. */
    private var lastNotificationId: String? = null

    /**
     * Checks for an update, updating [state] via [nextStateAfterCheck] and posting/replacing the
     * "update available" notification when one is found. Also sweeps `<cacheDir>/updates/` of
     * every version directory except the one — if any — [state] is currently using, so a version
     * this repository stops referencing (a completed install, or a newer version superseding a
     * [UpdateState.Ready] one) doesn't accumulate on disk forever.
     *
     * Suspends until the check completes and returns its [UpdateStatus] directly — unlike
     * [startDownload]/[cancelDownload]/[install], which self-launch and return immediately — because
     * [checkForUpdateAndNotify] needs the result to decide whether to update the automatic-check
     * timestamp the same way it always has.
     */
    suspend fun check(): UpdateStatus = mutex.withLock {
        val before = _state.value
        _state.update { current ->
            when (current) {
                UpdateState.Idle, UpdateState.UpToDate,
                is UpdateState.Available, is UpdateState.Failed,
                -> UpdateState.Checking
                // Downloading/Verifying/Ready/Installing/Checking: a check must never visibly
                // interrupt these, even momentarily.
                else -> current
            }
        }
        sweepStaleUpdateDownloads(before)

        val status = checker.check()
        val after = nextStateAfterCheck(before, status, location)
        _state.value = after

        // A Ready version this check just superseded (replaced by a newer Available) would
        // otherwise sit on disk, unprotected, until whenever the *next* check happens to run.
        if (before is UpdateState.Ready && (after !is UpdateState.Ready || after.update.version != before.update.version)) {
            FileSystemExtras.deleteRecursively(updateDownloadDir(before.update.version))
        }

        if (status is UpdateStatus.Available && after is UpdateState.Available) {
            val message = notificationMessages.updateAvailable(status.version)
            val action = if (canInstall(after.update.plan)) {
                AppNotificationAction.ShowSettingsTab("updates")
            } else {
                AppNotificationAction.OpenUrl(status.url)
            }
            postNotification(message, action)
        }
        status
    }

    /**
     * Starts downloading the currently [UpdateState.Available] update, if [installer] reports it
     * can actually be installed here and no download is already running. A no-op otherwise — in
     * particular, calling this twice in a row starts exactly one download, not two.
     */
    fun startDownload() {
        scope.launch {
            mutex.withLock {
                if (downloadJob?.isActive == true) return@withLock
                val update = (_state.value as? UpdateState.Available)?.update ?: return@withLock
                val asset = update.asset ?: return@withLock
                if (!canInstall(update.plan)) return@withLock
                downloadJob = scope.launch { runDownload(update, asset) }
            }
        }
    }

    /** Cancels an in-progress download, if any. Not `suspend` — callable directly from a UI click
     * handler without a coroutine scope of its own. The cancelled download reverts [state] to
     * [UpdateState.Available] (see [runDownload]'s own handling) rather than [UpdateState.Failed] —
     * a user-requested cancellation is not a failure. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    private suspend fun runDownload(update: AvailableUpdate, asset: UpdateAsset) {
        val destDir = updateDownloadDir(update.version)
        val destPath = FileIO.join(destDir, asset.name)
        _state.value = UpdateState.Downloading(update, 0L, asset.sizeBytes)
        try {
            val requiredBytes = asset.sizeBytes * REQUIRED_FREE_SPACE_MULTIPLE
            if (FileSystemExtras.usableSpaceBytes(cacheDir) < requiredBytes) {
                _state.value = UpdateState.Failed(update, UpdateException(UpdateStage.DOWNLOAD, "Not enough free disk space"))
                return
            }
            SystemFileSystem.createDirectories(Path(destDir))

            val result = downloader.download(
                url = asset.downloadUrl,
                destPath = destPath,
                expectedSizeBytes = asset.sizeBytes,
                expectedSha256 = asset.sha256,
                // UpdateDownloader has no separate "now hashing" hook, but shouldEmitProgress
                // guarantees the final call always has done == total, and that call lands right
                // as the streaming loop finishes and hashing is about to start — so it doubles as
                // the Downloading -> Verifying transition.
                onProgress = { done, total ->
                    _state.value = if (done >= total) UpdateState.Verifying(update) else UpdateState.Downloading(update, done, total)
                },
            )
            when (result) {
                is Result.Ok -> {
                    _state.value = UpdateState.Ready(update, destPath)
                    postNotification(
                        notificationMessages.updateReadyToInstall(update.version),
                        AppNotificationAction.ShowSettingsTab("updates"),
                    )
                }
                is Result.Err -> {
                    Log.warn(TAG, "Update download failed: ${result.exception.messageText}")
                    val exception = result.exception as? UpdateException
                        ?: UpdateException(UpdateStage.DOWNLOAD, result.exception.messageText)
                    _state.value = UpdateState.Failed(update, exception)
                }
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(update)
            throw e
        } catch (e: Exception) {
            // Anything downloader.download() itself doesn't already turn into a Result.Err (e.g.
            // SystemFileSystem.createDirectories failing) would otherwise leave state stuck at
            // Downloading/Verifying forever with no error shown and nothing logged — the same
            // failure mode a stray IllegalArgumentException in the install path caused (see
            // DetachedProcess.kt's own KDoc).
            Log.warn(TAG, "Update download failed unexpectedly", e)
            _state.value = UpdateState.Failed(update, UpdateException(UpdateStage.DOWNLOAD, e.message ?: "Download failed"))
        }
    }

    /**
     * Hands the current [UpdateState.Ready] file off to [installer]. Not `suspend` — like
     * [startDownload]/[cancelDownload], self-launches so a UI click handler can call it directly. A
     * no-op when [state] isn't [UpdateState.Ready].
     */
    fun install() {
        scope.launch {
            val ready = mutex.withLock {
                (_state.value as? UpdateState.Ready)?.also { _state.value = UpdateState.Installing(it.update) }
            } ?: return@launch

            try {
                when (val result = installer.install(ready.filePath, ready.update)) {
                    // Launched: the installer/OS takes over from here (see InstallLaunchResult's
                    // own KDoc). State stays at Installing rather than being guessed forward; the
                    // app-exit signal is emitted *here*, after the hand-off actually happened —
                    // never from the state transition above, which is set before
                    // installer.install() has done a thing.
                    InstallLaunchResult.Launched -> _installLaunched.emit(Unit)
                    InstallLaunchResult.AwaitingUserConsent -> _state.value = ready
                    is InstallLaunchResult.Failed ->
                        _state.value = UpdateState.Failed(ready.update, UpdateException(UpdateStage.INSTALL, result.reason))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // installer.install() extracts/stages/launches a real external process — any of
                // those steps throwing (a bad zip, a disk error, a launcher misuse like the
                // IllegalArgumentException DetachedProcess.kt's own KDoc describes) must never
                // leave state stuck at Installing forever with no error and nothing logged.
                Log.warn(TAG, "Update install failed unexpectedly", e)
                _state.value = UpdateState.Failed(ready.update, UpdateException(UpdateStage.INSTALL, e.message ?: "Install failed"))
            }
        }
    }

    /** Whether [installer] can actually carry out [plan] right now — not just what [plan] itself
     * says should happen (Android's permission/consent state, for instance, is something [plan]
     * knows nothing about). */
    private fun canInstall(plan: UpdatePlan): Boolean = installer.canInstall(plan)

    /**
     * Dispatches to whichever single action the tray's one update menu item (or the notification
     * center's "updates" row) currently represents — see `tray/KeryxTray.kt`'s `trayUpdateEntry`
     * for the label/enabled state this corresponds to. A no-op for every state with no action of
     * its own ([UpdateState.Idle]/[UpdateState.Checking]/[UpdateState.UpToDate]/
     * [UpdateState.Downloading]/[UpdateState.Verifying]/[UpdateState.Installing]).
     */
    fun performPrimaryAction() {
        when (val current = _state.value) {
            is UpdateState.Available -> startDownload()
            is UpdateState.Ready -> install()
            is UpdateState.Failed -> if (current.update != null) startDownload() else scope.launch { check() }
            UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate,
            is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing,
            -> Unit
        }
    }

    /** Replaces [lastNotificationId] (if any) with a fresh row for [message]/[action], so an update
     * moving from "available" to "ready to install" reads as one evolving row rather than two. */
    private fun postNotification(message: String, action: AppNotificationAction) {
        lastNotificationId?.let { notificationCenter.dismiss(it) }
        val id = IdGenerator.newId()
        notificationCenter.addCoalescing(
            AppNotification(
                id = id,
                level = AppNotificationLevel.INFO,
                message = message,
                timestampMillis = SystemClock.nowMillis(),
                action = action,
            ),
        )
        lastNotificationId = id
    }

    private fun sweepStaleUpdateDownloads(currentBeforeCheck: UpdateState) {
        val keep = updateVersionInUse(currentBeforeCheck)
        val updatesDir = Path(FileIO.join(cacheDir, "updates"))
        if (SystemFileSystem.metadataOrNull(updatesDir)?.isDirectory != true) return
        val entries = runCatching { SystemFileSystem.list(updatesDir) }.getOrElse { emptyList() }
        for (entry in entries) {
            if (entry.name == keep) continue
            FileSystemExtras.deleteRecursively(entry.toString())
        }
    }

    private fun updateDownloadDir(version: String): String = FileIO.join(cacheDir, "updates", version)
}

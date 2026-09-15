package works.merc.keryx.app.domain

import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.core.UPDATE_RELEASE_WATCH_INTERVAL_MS
import works.merc.keryx.app.core.UPDATE_RELEASE_WATCH_MAX_ATTEMPTS
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.core.untrustedText
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSystemExtras
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.detectInstallLocation

private const val TAG = "UpdateRepository"

/** What [UpdateRepository.check] concluded, beyond the [UpdateState] it already published. */
enum class UpdateCheckOutcome {
    /** The check resolved to a durable answer — up to date, a genuinely installable update, a
     * release page fallback, or a failure — and nothing further is pending. */
    CONCLUSIVE,

    /**
     * The release GitHub returned is newer, but carries no asset for this install form yet
     * ([awaitsReleaseAsset]) — the release workflow publishes the GitHub release before attaching
     * the built packages, so this is expected to resolve itself within minutes. [UpdateRepository]
     * has started (or continued) polling every [UPDATE_RELEASE_WATCH_INTERVAL_MS] on its own; no
     * caller action is required, and none of this is shown to the user (see [UpdateState] — this
     * check leaves it at [UpdateState.UpToDate], not a new [UpdateState.Available]).
     */
    AWAITING_RELEASE,
}

/** How much of a failure reason to keep. Long enough for any message this pipeline composes,
 * short enough that a crafted archive cannot consume a meaningful share of the rotating log. */
private const val MAX_FAILURE_REASON_LENGTH = 300

/** Free space an in-app update download requires as a multiple of the asset size — headroom for
 * the `.part` file, the final renamed copy briefly coexisting with it, and general safety margin. */
private const val REQUIRED_FREE_SPACE_MULTIPLE = 3

/**
 * Whether [usableBytes] of free space is enough to safely download an asset of [assetSizeBytes] —
 * [REQUIRED_FREE_SPACE_MULTIPLE] times over, for the headroom that constant's own KDoc describes.
 * Compares via division rather than `usableBytes < assetSizeBytes * REQUIRED_FREE_SPACE_MULTIPLE`,
 * which silently overflows into a negative `Long` for a large enough [assetSizeBytes] and would then
 * wrongly report "enough space" no matter how little is actually free — a real, if narrow, concern
 * given [assetSizeBytes] ultimately comes from the release JSON `UpdateChecker` parses.
 * [selectUpdateAsset] already rejects implausibly large assets before one ever reaches here, but this
 * stays overflow-safe on its own rather than relying solely on that earlier gate.
 */
internal fun hasEnoughFreeSpaceForUpdate(usableBytes: Long, assetSizeBytes: Long): Boolean =
    assetSizeBytes >= 0 && usableBytes / REQUIRED_FREE_SPACE_MULTIPLE >= assetSizeBytes

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
    // Sweeping <cacheDir>/updates/ and deleting a superseded version directory in check() are
    // blocking filesystem calls; SettingsViewModel's manual "check for update" launches check()
    // directly on its own dispatcher (Main/EDT by default there), so this must move that work off
    // whatever dispatcher happens to call check() rather than assume every caller already does —
    // the startup task and Android's WorkManager worker already run in the background on their own,
    // but a UI-triggered check should not have to rely on that being true forever.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Lets a test point at a temp directory, the same way LocalSettingsStore's dirOverride does.
    private val cacheDirOverride: String? = null,
    // Lets UpdateRepositoryTest run the release watch (see startReleaseWatch) on a short interval
    // instead of the real UPDATE_RELEASE_WATCH_INTERVAL_MS — that test polls on the real wall clock
    // rather than runTest's virtual scheduler (see its own KDoc for why), so this needs a genuine
    // seam rather than a fake clock.
    private val releaseWatchIntervalMs: Long = UPDATE_RELEASE_WATCH_INTERVAL_MS,
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

    // Serializes one whole runCheck transaction — the "start" snapshot, checker.check() itself, and
    // the "apply"/"watch bookkeeping" steps after it — against every other runCheck. [mutex] cannot
    // do this: it is deliberately released across checker.check(), so two checks could fetch
    // concurrently and the one that started *first* could apply *last*, overwriting a freshly found
    // Available with its own older UpToDate and re-arming a watch the newer check had just ended.
    // Holding a lock across the network call is safe here in a way holding [mutex] there was not —
    // this one blocks only other checks, never startDownload/install/cancelDownload — and it also
    // means a check beginning while another is in flight issues its own request only once that one
    // is done, so a later start always sees later data. It equally serializes two *public* checks,
    // whose overlap would otherwise hand the second one a `before` of UpdateState.Checking, which
    // updateVersionInUse() reports as "no version in use" — letting sweepStaleUpdateDownloads delete
    // the very directory the first check's Ready state is still pointing at.
    private val checkMutex = Mutex()

    // Written only under mutex (startDownloadOf), but read from cancelDownload() — a plain,
    // non-suspend call from a UI click handler that cannot take a suspend lock — without it. @Volatile
    // is what makes that read see the write at all: a plain var gives the JVM/other backends no
    // happens-before edge between a mutex.withLock write on one thread and an unsynchronized read on
    // another, so cancelDownload() could otherwise observe a stale null/previous Job forever.
    @Volatile
    private var downloadJob: Job? = null

    // Both read and written only under mutex, inside runCheck/recordWatchOutcome/startReleaseWatch —
    // unlike downloadJob above, nothing outside a suspend context ever touches these, so neither
    // needs @Volatile. 0 means "not currently watching for a release"; see recordWatchOutcome.
    private var releaseWatchAttempts: Int = 0
    private var releaseWatchJob: Job? = null

    // Read-modified-written by postNotification(), called from both check() (after its own mutex is
    // released — see check()'s own KDoc for why that lock is scoped narrowly) and runDownload() (never
    // mutex-guarded at all). A plain var here would let a background check() and a download finishing
    // race to dismiss/replace this id, losing one of the two updates — this repository's own KDoc
    // promises "one evolving row", not "usually one row". Guarded by its own, narrower mutex rather
    // than folded into [mutex] so a slow notificationMessages.* call here never blocks the decision
    // points [mutex] exists for.
    private val notificationMutex = Mutex()

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
     * Suspends until the check completes — unlike [startDownload]/[cancelDownload]/[install], which
     * self-launch and return immediately — so a caller (e.g. [checkForUpdateAndNotify], stamping
     * the automatic-check timestamp afterward) can rely on [state] already reflecting this check's
     * result the moment this returns.
     *
     * The returned [UpdateCheckOutcome] is [UpdateCheckOutcome.AWAITING_RELEASE] exactly when this
     * check has started (or continued) [startReleaseWatch] instead of concluding anything visible —
     * see that function and [UpdateCheckOutcome]'s own KDoc. Every existing caller of this method
     * predates that outcome and calls it as a statement, so this is source-compatible.
     */
    suspend fun check(): UpdateCheckOutcome = runCheck(quiet = false)

    /**
     * [check]'s real body, and also what [startReleaseWatch]'s own polling loop calls directly
     * (with [quiet] set) rather than going through the public [check] again.
     *
     * [mutex] is only held for the brief, non-suspending-on-network steps — "start" (recording
     * [state] as it was and, where appropriate, marking it [UpdateState.Checking]), "apply" (folding
     * [checker]'s result back in), and "watch bookkeeping" (deciding [UpdateCheckOutcome] and
     * updating [releaseWatchAttempts]) — never across [checker.check] itself, which can take up to
     * [works.merc.keryx.app.core.REQUEST_TIMEOUT_MS]. Holding it for the whole call once blocked
     * [startDownload]/[install] from even beginning their own decision for as long as a slow check
     * was in flight. The "apply" step reads [state] fresh via [MutableStateFlow.update] rather than
     * reusing the "start" step's snapshot, so a download that ran to completion *while* this check's
     * network call was in flight is folded in correctly instead of being clobbered by a stale
     * pre-check value — seeing a state [nextStateAfterCheck] never interrupts (Downloading/
     * Verifying/Installing) turns "apply" into a no-op, exactly as if this check had run
     * instantaneously.
     *
     * What keeps that narrow [mutex] scope from being a race between two *checks* is [checkMutex],
     * which every call here is entered under: a quiet [startReleaseWatch] poll and a user-triggered
     * [check] can never have their network calls in flight at the same time, so neither can apply a
     * result the other has already superseded. The cost is that a check beginning while another is
     * in flight waits it out before showing anything (at worst one
     * [works.merc.keryx.app.core.REQUEST_TIMEOUT_MS], and only while a release watch is running);
     * marking [UpdateState.Checking] *before* that wait instead would make the "start" snapshot
     * itself [UpdateState.Checking], which is exactly the value [updateVersionInUse] reads as
     * "nothing in use" and [sweepStaleUpdateDownloads] then deletes a live download over.
     *
     * [quiet] is what keeps [startReleaseWatch]'s repeated polling from being visible: it skips the
     * momentary [UpdateState.Checking] flash a user-requested [check] shows (the tray/Updates tab
     * would otherwise flicker "Checking…" every [releaseWatchIntervalMs]), and a network failure
     * during a quiet poll is treated as "still waiting", not surfaced as [UpdateState.Failed] — a
     * background retry's own transient hiccup should not overwrite whatever the user is currently
     * looking at (still [UpdateState.UpToDate] from this repository's point of view — see
     * [awaitsReleaseAsset]/[nextStateAfterCheck]) with an error the user never asked about.
     */
    private suspend fun runCheck(quiet: Boolean): UpdateCheckOutcome = checkMutex.withLock { runCheckLocked(quiet) }

    /** [runCheck]'s body, always entered under [checkMutex] — see that field for what it guards. */
    private suspend fun runCheckLocked(quiet: Boolean): UpdateCheckOutcome {
        val before = mutex.withLock {
            _state.value.also { current ->
                if (!quiet) {
                    _state.value = when (current) {
                        UpdateState.Idle, UpdateState.UpToDate,
                        is UpdateState.Available, is UpdateState.Failed,
                        -> UpdateState.Checking
                        // Downloading/Verifying/Ready/Installing/Checking: a check must never
                        // visibly interrupt these, even momentarily.
                        else -> current
                    }
                }
            }
        }
        withContext(dispatcher) { sweepStaleUpdateDownloads(before) }

        val status = checker.check()

        if (quiet && status is UpdateStatus.Failed) {
            Log.info(TAG, "Quiet release-watch poll failed transiently, still watching: ${untrustedText(status.exception.messageText, MAX_FAILURE_REASON_LENGTH)}")
            // `releaseWatchAttempts > 0` rather than a flat `true`: a public check that concluded
            // while this poll was queued behind it has already ended the watch (reset the counter),
            // and a transient failure in the poll that follows must not resurrect one — see
            // recordWatchOutcome's own KDoc ("Not watching already ... is *not* reason to start").
            val outcome = mutex.withLock { recordWatchOutcome(stillWatching = releaseWatchAttempts > 0) }
            if (outcome == UpdateCheckOutcome.AWAITING_RELEASE) startReleaseWatch()
            return outcome
        }

        val after = mutex.withLock {
            _state.update { current -> nextStateAfterCheck(current, status, location, canInstall = ::canInstall) }
            _state.value
        }

        // A Ready version this check just superseded (replaced by a newer Available) would
        // otherwise sit on disk, unprotected, until whenever the *next* check happens to run.
        //
        // Asks updateVersionInUse the same question sweepStaleUpdateDownloads asks, rather than
        // testing `after !is Ready`: the state can also leave Ready by legitimately *advancing*.
        // A user who clicks Install while a check is in flight — which the Updates tab allows,
        // since a check leaves Ready untouched and disables nothing — ends at Installing on the
        // same version, and `!is Ready` would then delete the ZIP the extraction is reading and
        // the tree it is writing.
        if (before is UpdateState.Ready && updateVersionInUse(after) != before.update.version) {
            withContext(dispatcher) { FileSystemExtras.deleteRecursively(updateDownloadDir(before.update.version)) }
        }

        if (status is UpdateStatus.Available && after is UpdateState.Available) {
            val message = notificationMessages.updateAvailable(status.version)
            // Reuses after.update.installable (resolved moments ago by nextStateAfterCheck via the
            // same canInstall) rather than asking installer.canInstall(after.update.plan) again —
            // one live query, one fact, shared with whatever the Updates tab/tray display for it.
            val action = if (after.update.installable) {
                AppNotificationAction.ShowSettingsTab("updates")
            } else {
                AppNotificationAction.OpenUrl(status.url)
            }
            postNotification(message, action)
        }

        Log.info(
            TAG,
            "Update check resolved: version=${(status as? UpdateStatus.Available)?.version ?: "none"}, " +
                "asset=${(status as? UpdateStatus.Available)?.asset?.name ?: "none"}, " +
                "plan=${(after as? UpdateState.Available)?.update?.plan ?: "n/a"}, " +
                "installable=${(after as? UpdateState.Available)?.update?.installable ?: false}",
        )

        val outcome = mutex.withLock {
            // Available-with-no-asset-here is what starts/continues a watch; Available-with-asset
            // (a real, actionable find) always ends one, even one already in progress — see
            // recordWatchOutcome's own KDoc for why UpToDate/Failed while already watching also
            // continue it, and why a plain "nothing to see" check does not start one.
            val watchTriggering = status is UpdateStatus.Available && awaitsReleaseAsset(location, status.asset)
            val stillWatching = watchTriggering || (releaseWatchAttempts > 0 && status !is UpdateStatus.Available)
            recordWatchOutcome(stillWatching)
        }
        if (outcome == UpdateCheckOutcome.AWAITING_RELEASE) startReleaseWatch()
        return outcome
    }

    /**
     * Must be called under [mutex]. Updates [releaseWatchAttempts] and returns the [UpdateCheckOutcome]
     * that follows from it.
     *
     * [stillWatching] folds together every reason a caller might want to keep polling short-interval:
     * a release found with no asset for this install form yet, or — while already watching — a
     * check that came back [UpdateStatus.UpToDate] (the release was withdrawn/deleted, expected
     * during a hand-driven rebuild: unpublish, delete the tag, recreate it) or [UpdateStatus.Failed]
     * (a transient network hiccup). Not watching already and seeing one of those two is *not* reason
     * to start — an ordinary "nothing new" check must not begin polling every [releaseWatchIntervalMs].
     *
     * Resets [releaseWatchAttempts] to 0 whenever a watch ends, for whatever reason — including
     * running out of budget — so a *later*, unrelated [UpdateStatus.UpToDate] under the normal
     * schedule is never mistaken for "still watching".
     */
    private fun recordWatchOutcome(stillWatching: Boolean): UpdateCheckOutcome {
        if (!stillWatching) {
            releaseWatchAttempts = 0
            return UpdateCheckOutcome.CONCLUSIVE
        }
        releaseWatchAttempts += 1
        if (releaseWatchAttempts >= UPDATE_RELEASE_WATCH_MAX_ATTEMPTS) {
            // Budget exhausted: this environment gets no asset for a release that plainly wants
            // one (e.g. an MSI install offered a pre-release tag, which release.yml never attaches
            // an .msi to at all) — fall back to the ordinary updateCheckIntervalHours schedule
            // rather than polling forever. No notification either way: from this environment's
            // point of view there is still nothing installable to announce.
            releaseWatchAttempts = 0
            return UpdateCheckOutcome.CONCLUSIVE
        }
        return UpdateCheckOutcome.AWAITING_RELEASE
    }

    /**
     * Starts polling [runCheck] every [releaseWatchIntervalMs] (quietly — see its own KDoc) until it
     * stops returning [UpdateCheckOutcome.AWAITING_RELEASE]. A no-op while a watch is already running
     * ([releaseWatchJob]'s own `isActive`, checked under [mutex] the same way [downloadJob]'s
     * equivalent guard in [startDownloadOf] is) — including when [runCheck] calls this again from
     * inside that very loop, which is harmless but redundant rather than a bug: the guard sees its
     * own job still active and returns immediately.
     *
     * Launched on [scope] (the app-lifetime scope — see this class's own KDoc), so the watch
     * survives a closed settings dialog the same way an in-progress download does. It stops itself
     * once [runCheck] concludes: an asset finally appearing, the budget in [recordWatchOutcome]
     * running out (the "gone forever" case — [runCheck] on its own can never distinguish that from
     * "gone for now"), or a download/install starting on this version (which keeps [state] at
     * [UpdateState.Downloading]/[UpdateState.Verifying]/[UpdateState.Installing] rather than
     * [UpdateState.Available], so [runCheck] naturally stops returning
     * [UpdateCheckOutcome.AWAITING_RELEASE]).
     */
    private suspend fun startReleaseWatch(): Unit = mutex.withLock {
        if (releaseWatchJob?.isActive == true) return@withLock
        releaseWatchJob = scope.launch {
            while (isActive) {
                delay(releaseWatchIntervalMs)
                if (runCheck(quiet = true) != UpdateCheckOutcome.AWAITING_RELEASE) return@launch
            }
        }
    }

    /**
     * Starts downloading the currently [UpdateState.Available] update, if [installer] reports it
     * can actually be installed here and no download is already running. A no-op otherwise — in
     * particular, calling this twice in a row starts exactly one download, not two.
     *
     * Also the retry entry point for a [UpdateState.Failed]: which action "retry" means depends on
     * where the failure happened ([UpdateException.stage]) — see [retryFailed]. This is what both
     * the Updates tab's "Retry" button and the tray/notification-center row (via
     * [performPrimaryAction]) call, so a failure at any stage has exactly one way back.
     */
    fun startDownload() {
        scope.launch {
            when (val current = _state.value) {
                is UpdateState.Failed -> retryFailed(current)
                is UpdateState.Available -> startDownloadOf(current.update)
                else -> Unit
            }
        }
    }

    private suspend fun startDownloadOf(update: AvailableUpdate) {
        mutex.withLock {
            if (downloadJob?.isActive == true) return@withLock
            val asset = update.asset ?: return@withLock
            if (!canInstall(update.plan)) return@withLock
            downloadJob = scope.launch { runDownload(update, asset) }
        }
    }

    /**
     * Resumes a [failed] update per [UpdateException.stage] rather than treating every failure the
     * same way: [UpdateStage.CHECK] (or no [UpdateState.Failed.update] at all — the check itself is
     * what failed, so there's nothing else to act on) re-runs [check]; [UpdateStage.DOWNLOAD]/
     * [UpdateStage.VERIFY] re-downloads, since whatever bytes made it to disk can't be trusted;
     * [UpdateStage.INSTALL] re-installs the already-downloaded, already-verified file directly
     * without downloading it again — falling back to a fresh download only if that file is no
     * longer where it should be (e.g. swept as stale by an intervening [check]).
     */
    private suspend fun retryFailed(failed: UpdateState.Failed) {
        val update = failed.update
        when {
            update == null || failed.exception.stage == UpdateStage.CHECK -> check()
            failed.exception.stage == UpdateStage.INSTALL -> retryInstall(update)
            else -> startDownloadOf(update) // DOWNLOAD or VERIFY
        }
    }

    private suspend fun retryInstall(update: AvailableUpdate) {
        val filePath = update.asset?.let { FileIO.join(updateDownloadDir(update.version), it.name) }
        if (filePath == null || !FileIO.exists(filePath)) {
            startDownloadOf(update)
            return
        }
        // Rewinding to Ready happens under the lock, and only from the Failed this retry was
        // decided on. Two surfaces act on the same Failed state — the tray's own item and the
        // Updates tab / notification row — and both fan out through scope.launch, so without this
        // the loser of that race re-sets Ready after the winner already moved to Installing.
        // install()'s own Ready -> Installing transition would then let a second selfReplace start
        // against the same staging and swap directories: the second attempt's pre-clear deletes the
        // tree the first is still extracting, both write apply.sh at the same path, and two detached
        // scripts race the same install directory with only one .old between them. It also keeps
        // check()'s sweep correct for free, since a directory is only ever in use while state is one
        // of the four updateVersionInUse() protects.
        val claimed = mutex.withLock {
            (_state.value is UpdateState.Failed).also { if (it) _state.value = UpdateState.Ready(update, filePath) }
        }
        if (claimed) install()
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
            if (!hasEnoughFreeSpaceForUpdate(FileSystemExtras.usableSpaceBytes(cacheDir), asset.sizeBytes)) {
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
                    // download() already checks cancellation right after hashing (see its own
                    // comment), but this is the very last chance before committing to Ready — a
                    // cancellation landing in the narrow window between download() returning and
                    // here would otherwise still surface as "ready to install" for a download the
                    // user had already cancelled.
                    coroutineContext.ensureActive()
                    _state.value = UpdateState.Ready(update, destPath)
                    postNotification(
                        notificationMessages.updateReadyToInstall(update.version),
                        AppNotificationAction.ShowSettingsTab("updates"),
                    )
                }
                is Result.Err -> {
                    Log.warn(TAG, "Update download failed: ${untrustedText(result.exception.messageText, MAX_FAILURE_REASON_LENGTH)}")
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
                    is InstallLaunchResult.Failed -> {
                        // The reason never reaches the user — ui/i18n/ErrorMessages maps every
                        // UpdateException to one generic string — so without this line an install
                        // failure leaves no trace anywhere at all, in the app log included. That is
                        // exactly how a macOS bundle that failed its code-signature check went
                        // undiagnosed: the UI said "update failed" and nothing else existed.
                        //
                        // Sanitized here rather than by each producer: several reasons interpolate
                        // archive-derived text (a ZIP entry name may be 64 KB and contain newlines,
                        // an extracted filename likewise), and one rule at the sink covers every
                        // current and future one. See core/UntrustedText.kt.
                        val reason = untrustedText(result.reason, MAX_FAILURE_REASON_LENGTH)
                        Log.warn(TAG, "Update install failed: $reason")
                        _state.value = UpdateState.Failed(ready.update, UpdateException(UpdateStage.INSTALL, reason))
                    }
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
     * center's "updates" row) currently represents — see `tray/UpdateMenuEntry.kt`'s
     * `updateMenuEntry` for the label/enabled state this corresponds to. [UpdateState.Failed] goes through
     * [startDownload] too — see its own KDoc for how it resumes a failure per-stage. A no-op for
     * every state with no action of its own ([UpdateState.Idle]/[UpdateState.Checking]/
     * [UpdateState.UpToDate]/[UpdateState.Downloading]/[UpdateState.Verifying]/
     * [UpdateState.Installing]).
     */
    fun performPrimaryAction() {
        when (_state.value) {
            is UpdateState.Available, is UpdateState.Failed -> startDownload()
            is UpdateState.Ready -> install()
            UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate,
            is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing,
            -> Unit
        }
    }

    /** Replaces [lastNotificationId] (if any) with a fresh row for [message]/[action], so an update
     * moving from "available" to "ready to install" reads as one evolving row rather than two.
     * [notificationMutex]-guarded — see that field's own KDoc for why: [check] and [runDownload] can
     * both reach this, on different threads, with no other synchronization between them. */
    private suspend fun postNotification(message: String, action: AppNotificationAction) = notificationMutex.withLock {
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
        val updatesDir = Path(updatesRootDir)
        if (SystemFileSystem.metadataOrNull(updatesDir)?.isDirectory != true) return
        val entries = runCatching { SystemFileSystem.list(updatesDir) }.getOrElse { emptyList() }
        for (entry in entries) {
            if (entry.name == keep) continue
            FileSystemExtras.deleteRecursively(entry.toString())
        }
    }

    /** Where every in-progress or completed download's own version directory lives — the single
     * definition [updateDownloadDir] and [sweepStaleUpdateDownloads] both derive from, so the two
     * can never drift apart and have the sweep quietly stop seeing what downloads actually use. */
    private val updatesRootDir: String get() = FileIO.join(cacheDir, "updates")

    private fun updateDownloadDir(version: String): String = FileIO.join(updatesRootDir, version)
}

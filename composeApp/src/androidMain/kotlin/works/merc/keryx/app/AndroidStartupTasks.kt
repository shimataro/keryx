package works.merc.keryx.app

import kotlinx.coroutines.sync.Mutex
import org.koin.core.Koin
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.runStartupMaintenance
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes Android's two independent maintenance entry points — [runAndroidStartupTasks]
 * (Activity) and `FeedRefreshWorker` (`WorkManager`) — so a periodic wakeup landing while the
 * Activity's own startup sequence is still running does not duplicate
 * `refreshFeedsAndNotify`/`sync`/`checkForUpdateAndNotify`/`maybeRebuildFtsIndex` work. A
 * process-wide singleton (not a per-call `Mutex()`) since both entry points must contend on the
 * *same* lock instance; `internal` (not `private`) so `background/FeedRefreshWorker.kt` — a
 * different package in the same androidMain source set — can share it.
 */
internal val startupMaintenanceMutex = Mutex()

// Guards runAndroidStartupTasks to once per process: MainActivity.onCreate runs again on
// configuration changes (rotation) that recreate the Activity without restarting the process, and
// this must not re-run the full startup sequence each time. Set only once the maintenance lock is
// actually held and every step below has been attempted (see startupMaintenanceMutex.tryLock() and
// runMaintenanceStep below) — a call skipped because setup isn't finished yet, or because
// FeedRefreshWorker currently holds the lock, must not burn this process's only chance to run them
// (in particular cleanUpArticleCacheIfDue, which FeedRefreshWorker never runs itself). An
// AtomicBoolean (not a plain var) because MainActivity.onCreate launches onto a
// Dispatchers.Default-backed CoroutineScope (AppModule.kt) — a real thread pool — so two Activity
// recreations in quick succession (e.g. an early rotation) can race two coroutines through this
// guard on different threads.
private val startupTasksRan = AtomicBoolean(false)

/**
 * Runs the same maintenance sequence as desktop's `runStartupTasks` — `domain/
 * StartupMaintenanceTasks.kt`'s shared [runStartupMaintenance] (cache cleanup, initial cloud sync,
 * feed refresh, update check, FTS repair) — everything except the macOS-specific translocation
 * warning, which has no Android equivalent.
 *
 * Called from `MainActivity.onCreate`, not `KeryxApplication.onCreate`: the latter also runs when
 * `WorkManager` wakes the process to run `FeedRefreshWorker`, and running the full startup
 * sequence on every background wakeup would duplicate `refreshFeedsAndNotify`/etc. on top of what
 * the worker itself just did.
 *
 * Public rather than `internal`: `MainActivity` lives in the separate `:androidApp` Gradle module,
 * which `internal`'s module-scoped visibility would put out of reach.
 */
suspend fun runAndroidStartupTasks(koin: Koin) {
    if (startupTasksRan.get()) return
    // Checked here too (runStartupMaintenance re-checks it, and owns the full reason why) rather
    // than only inside runStartupMaintenance: this must happen *before* the tryLock below, so a
    // pre-setup call never holds the lock at all — see startupMaintenanceMutex's own KDoc for why
    // that would otherwise let it steal FeedRefreshWorker's periodic run for nothing.
    if (!koin.get<SettingsRepository>().isSetupComplete()) return
    // FeedRefreshWorker may already be running the same sequence (WorkManager woke the process
    // right as this Activity started) — skip rather than duplicate refresh/sync/update-check/FTS
    // work; leave the "already ran" guard unset so a later Activity recreation (e.g. rotation) can
    // retry once the worker releases the lock, rather than permanently skipping cache cleanup for
    // the rest of this process (FeedRefreshWorker never runs cleanUpArticleCacheIfDue itself).
    if (!startupMaintenanceMutex.tryLock()) return
    try {
        // Double execution is prevented by startupMaintenanceMutex.tryLock() above, not by this
        // flag — it is a plain `set`, not a `compareAndSet`, because only one caller can ever reach
        // this point at a time. Set unconditionally once every step has been attempted (matching
        // runStartupMaintenance's own per-step isolation) — a step that failed is logged and left
        // for FeedRefreshWorker's own periodic run to pick back up (refreshFeedsAndNotify / sync /
        // checkForUpdateAndNotify / maybeRebuildFtsIndex), except cleanUpArticleCacheIfDue, which
        // only runs here and simply waits for its own 24h gate on the next process start.
        runStartupMaintenance(koin)
        startupTasksRan.set(true)
    } finally {
        startupMaintenanceMutex.unlock()
    }
}

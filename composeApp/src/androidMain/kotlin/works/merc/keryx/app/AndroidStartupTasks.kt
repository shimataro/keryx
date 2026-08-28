package works.merc.keryx.app

import kotlinx.coroutines.sync.Mutex
import org.koin.core.Koin
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.cleanUpArticleCacheIfDue
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import works.merc.keryx.app.domain.runMaintenanceStep
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
 * Runs the same maintenance sequence as desktop's `runStartupTasks` (cache cleanup, initial cloud
 * sync, feed refresh, update check, FTS repair) — everything except the macOS-specific
 * translocation warning, which has no Android equivalent.
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
    // Every step below eventually calls SettingsRepository.mutateLocalSettings (to record its own
    // "last ran at" timestamp), which persists local_settings.json in the background — the same
    // file whose mere *existence* is isSetupComplete()'s signal that setup finished
    // (SetupViewModel calls flush() at that point deliberately). Running any of this before setup
    // completes could race that check on a fresh install and make it skip the Setup screen
    // entirely. None of it is useful pre-setup anyway (no feeds to refresh, no sync configured).
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
        // this point at a time. Each step runs through runMaintenanceStep so that one step's
        // exception (e.g. maybeRebuildFtsIndex hitting FtsManager's busy_timeout) does not skip the
        // rest of the sequence the way a single shared try/catch would. The flag is then set
        // unconditionally once every step has been attempted — a step that failed is logged and
        // left for FeedRefreshWorker's own periodic run to pick back up (refreshFeedsAndNotify /
        // sync / checkForUpdateAndNotify / maybeRebuildFtsIndex), except cleanUpArticleCacheIfDue,
        // which only runs here and simply waits for its own 24h gate on the next process start.
        // sync()'s own Result (as opposed to a thrown exception) is deliberately not inspected here
        // — Activity recreation is not meant to be a retry mechanism for the expected failure
        // categories error-design.md documents as Result, only for genuinely unexpected exceptions.
        runMaintenanceStep("cacheCleanup") { cleanUpArticleCacheIfDue(koin) }
        runMaintenanceStep("sync") {
            if (koin.get<CloudSession>().isConnected()) {
                koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
            }
        }
        runMaintenanceStep("feedRefresh") { refreshFeedsAndNotify(koin) }
        runMaintenanceStep("updateCheck") { checkForUpdateAndNotify(koin) }
        runMaintenanceStep("ftsRebuild") { maybeRebuildFtsIndex(koin) }
        startupTasksRan.set(true)
    } finally {
        startupMaintenanceMutex.unlock()
    }
}

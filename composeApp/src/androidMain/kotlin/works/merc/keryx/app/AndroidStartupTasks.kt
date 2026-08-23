package works.merc.keryx.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.koin.core.Koin
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.cleanUpArticleCacheIfDue
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "AndroidStartupTasks"

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
// this must not re-run the full startup sequence each time. Set only once the tasks actually run
// (see the isSetupComplete check below) — a call skipped because setup isn't finished yet must not
// burn this process's only chance to run them. An AtomicBoolean (not a plain var) because
// MainActivity.onCreate launches onto a Dispatchers.Default-backed CoroutineScope (AppModule.kt) —
// a real thread pool — so two Activity recreations in quick succession (e.g. an early rotation)
// can race two coroutines through this guard on different threads.
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
    // compareAndSet, not a plain assignment: two concurrent callers could otherwise both pass the
    // check above and both run the tasks below.
    if (!startupTasksRan.compareAndSet(false, true)) return
    // FeedRefreshWorker may already be running the same sequence (WorkManager woke the process
    // right as this Activity started) — skip rather than duplicate refresh/sync/update-check/FTS
    // work; this only ever runs once per process anyway (the guard above), so the worker's next
    // periodic run will acquire the lock normally.
    if (!startupMaintenanceMutex.tryLock()) return
    try {
        runCatching {
            cleanUpArticleCacheIfDue(koin)
            if (koin.get<CloudSession>().isConnected()) {
                koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
            }
            refreshFeedsAndNotify(koin)
            checkForUpdateAndNotify(koin)
            maybeRebuildFtsIndex(koin)
        }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Startup tasks failed", it) }
    } finally {
        startupMaintenanceMutex.unlock()
    }
}

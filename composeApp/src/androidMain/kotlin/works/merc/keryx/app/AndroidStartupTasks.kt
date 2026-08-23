package works.merc.keryx.app

import kotlinx.coroutines.CancellationException
import org.koin.core.Koin
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.cleanUpArticleCacheIfDue
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "AndroidStartupTasks"

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
 * Runs the same maintenance sequence as desktop's `runStartupTasks` (cache cleanup, feed refresh,
 * update check, FTS repair) — everything except the macOS-specific translocation warning and the
 * initial cloud sync, which is Phase 4 work here (`CloudSession(providers = emptyMap())` makes
 * `SyncRepository.sync()` a no-op on this platform regardless).
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
    runCatching {
        cleanUpArticleCacheIfDue(koin)
        refreshFeedsAndNotify(koin)
        checkForUpdateAndNotify(koin)
        maybeRebuildFtsIndex(koin)
    }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Startup tasks failed", it) }
}

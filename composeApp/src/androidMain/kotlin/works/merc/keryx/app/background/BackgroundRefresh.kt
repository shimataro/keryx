package works.merc.keryx.app.background

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.core.Koin
import works.merc.keryx.app.domain.BackgroundRefreshSchedule
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.backgroundRefreshSchedule
import works.merc.keryx.app.platform.AndroidAppContext
import java.util.concurrent.TimeUnit

private const val FEED_REFRESH_WORK_NAME = "feed_refresh"

/**
 * Keeps `WorkManager`'s periodic feed-refresh job in sync with the user's refresh-interval
 * setting for the lifetime of the process: observes [SettingsRepository.localSettings] on the
 * shared app-scope [org.koin.core.Koin]-registered `CoroutineScope` and re-enqueues (or cancels)
 * `FeedRefreshWorker` whenever the setting changes, without needing a restart.
 *
 * Called once from `KeryxApplication.onCreate` — not from `MainActivity`, since a schedule change
 * must take effect even if the app is only ever opened through this one setting change and never
 * revisits the Activity that shows the setting (unlikely, but this scope has no reason to depend
 * on an Activity existing at all). This function only maintains the `WorkManager` schedule itself;
 * cloud sync runs inside `FeedRefreshWorker.doWork()` (once per periodic wakeup, gated on
 * `CloudSession.isConnected()`), not here.
 */
fun startBackgroundRefresh(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val workManager = WorkManager.getInstance(AndroidAppContext.application)

    settingsRepository.localSettings
        .map { it.refreshIntervalMinutes }
        .distinctUntilChanged()
        .onEach { minutes ->
            // Passed explicitly rather than relying on backgroundRefreshSchedule's own default:
            // that default exists only so commonTest can exercise the mapping without an
            // androidx.work dependency, and letting a commonMain function's default value be the
            // single source of truth for an Android-specific WorkManager constant would silently
            // drift from WorkManager's own value if it ever changed.
            val minimumMinutes = TimeUnit.MILLISECONDS.toMinutes(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
            when (val schedule = backgroundRefreshSchedule(minutes, minimumMinutes)) {
                BackgroundRefreshSchedule.Disabled -> workManager.cancelUniqueWork(FEED_REFRESH_WORK_NAME)
                is BackgroundRefreshSchedule.Periodic -> {
                    val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(schedule.minutes, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    workManager.enqueueUniquePeriodicWork(
                        FEED_REFRESH_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request,
                    )
                }
            }
        }
        .launchIn(koin.get())
}

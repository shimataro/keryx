package works.merc.keryx.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify

private const val LOG_TAG = "FeedRefreshWorker"

/**
 * `WorkManager`'s periodic entry point for background feed refresh — the Android equivalent of
 * one iteration of desktop's `backgroundUpdateLoop` (`refreshFeedsAndNotify` /
 * `checkForUpdateAndNotify` / `maybeRebuildFtsIndex`, the same three `internal` commonMain
 * functions desktop's `StartupTasks.kt` calls). `WorkManager` instantiates this itself via its
 * default `WorkerFactory` (reflection over the `(Context, WorkerParameters)` constructor), so
 * dependencies are resolved from [KoinPlatform.getKoin] inside [doWork] instead of being
 * constructor-injected — mirroring how `KeryxApplication.onCreate` already resolves Koin.
 *
 * Sync (`SyncRepository.sync()`) is deliberately not called here — see `background/BackgroundRefresh.kt`'s
 * own KDoc for why (Phase 4 scope).
 */
class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = KoinPlatform.getKoin()
        // Guards against the same local_settings.json-existence race AndroidStartupTasks.kt's
        // runAndroidStartupTasks documents — WorkManager's own 15-minute floor makes this
        // exceedingly unlikely to actually fire pre-setup, but the guard costs nothing to keep.
        if (!koin.get<SettingsRepository>().isSetupComplete()) return Result.success()
        return try {
            refreshFeedsAndNotify(koin)
            checkForUpdateAndNotify(koin)
            maybeRebuildFtsIndex(koin)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error(LOG_TAG, "Background feed refresh failed", e)
            Result.retry()
        }
    }
}

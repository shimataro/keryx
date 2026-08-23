package works.merc.keryx.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import works.merc.keryx.app.startupMaintenanceMutex

private const val LOG_TAG = "FeedRefreshWorker"

/**
 * `WorkManager`'s periodic entry point for background feed refresh — the Android equivalent of
 * one iteration of desktop's `backgroundUpdateLoop` (`refreshFeedsAndNotify` / `sync` /
 * `checkForUpdateAndNotify` / `maybeRebuildFtsIndex`, the same three commonMain maintenance
 * functions plus `SyncRepository.sync()` desktop's `StartupTasks.kt` calls). `WorkManager`
 * instantiates this itself via its default `WorkerFactory` (reflection over the
 * `(Context, WorkerParameters)` constructor), so dependencies are resolved from
 * [KoinPlatform.getKoin] inside [doWork] instead of being constructor-injected — mirroring how
 * `KeryxApplication.onCreate` already resolves Koin.
 */
class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = KoinPlatform.getKoin()
        // Guards against the same local_settings.json-existence race AndroidStartupTasks.kt's
        // runAndroidStartupTasks documents — WorkManager's own 15-minute floor makes this
        // exceedingly unlikely to actually fire pre-setup, but the guard costs nothing to keep.
        if (!koin.get<SettingsRepository>().isSetupComplete()) return Result.success()
        // runAndroidStartupTasks may already be running the same sequence (the Activity started
        // right as this periodic wakeup fired) — skip rather than duplicate the work; the next
        // periodic run will acquire the lock normally.
        if (!startupMaintenanceMutex.tryLock()) return Result.success()
        return try {
            refreshFeedsAndNotify(koin)
            if (koin.get<CloudSession>().isConnected()) {
                koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
            }
            checkForUpdateAndNotify(koin)
            maybeRebuildFtsIndex(koin)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error(LOG_TAG, "Background feed refresh failed", e)
            Result.retry()
        } finally {
            startupMaintenanceMutex.unlock()
        }
    }
}

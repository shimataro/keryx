package works.merc.keryx.app.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.core.Koin
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.errorOrNull
import works.merc.keryx.app.domain.NetworkUsagePolicy
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.platform.AndroidAppContext

private const val DEFERRED_SYNC_WORK_NAME = "deferred_sync"
private const val LOG_TAG = "DeferredSync"

/**
 * Hands a sync deferred for the network ([SyncRepository.syncDeferredForNetwork]) to `WorkManager`,
 * so it runs once the device reaches a network that allows it even if the app has been closed by
 * then — `SyncRepository`'s own in-process waiter only covers the time the process stays alive.
 *
 * Each time the flag goes up, a unique one-time [DeferredSyncWorker] is enqueued, constrained to
 * [NetworkType.UNMETERED] unless the user allows syncing on a metered network, in which case
 * [NetworkType.CONNECTED] ([NetworkUsagePolicy.deferredSyncNeedsUnmeteredNetwork]).
 * [ExistingWorkPolicy.KEEP] leaves one job for a burst of deferrals; the job is replaced only when
 * the opt-in changes while a deferral is pending, so the constraint follows it. When the flag drops in this process (a sync succeeded, or the connection was removed), the
 * job is cancelled; a job left by an earlier process is never cancelled at startup, since its
 * deferred changes have not been synced yet.
 *
 * Called once from `KeryxApplication.onCreate`, like [startBackgroundRefresh].
 */
fun startDeferredSyncHandoff(koin: Koin) {
    val workManager = WorkManager.getInstance(AndroidAppContext.application)
    var wasDeferred = false
    var enqueuedNetworkType: NetworkType? = null
    val syncOnMetered = koin.get<SettingsRepository>().localSettings.map { it.syncOnMeteredNetwork }
    combine(koin.get<SyncRepository>().syncDeferredForNetwork, syncOnMetered) { deferred, onMetered ->
        deferred to onMetered
    }
        .distinctUntilChanged()
        .onEach { (deferred, onMetered) ->
            if (deferred) {
                val networkType = if (NetworkUsagePolicy.deferredSyncNeedsUnmeteredNetwork(onMetered)) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val request = OneTimeWorkRequestBuilder<DeferredSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                    .build()
                // A job left by an earlier process is kept; only a constraint change this process
                // knows about replaces the one it enqueued.
                val policy = if (enqueuedNetworkType != null && enqueuedNetworkType != networkType) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    ExistingWorkPolicy.KEEP
                }
                workManager.enqueueUniqueWork(DEFERRED_SYNC_WORK_NAME, policy, request)
                enqueuedNetworkType = networkType
            } else if (wasDeferred) {
                workManager.cancelUniqueWork(DEFERRED_SYNC_WORK_NAME)
                enqueuedNetworkType = null
            }
            wasDeferred = deferred
        }
        .launchIn(koin.get())
}

/**
 * Runs the sync [startDeferredSyncHandoff] deferred, once `WorkManager` sees a network that allows it.
 * An ordinary [SyncTrigger.AUTOMATIC] sync: on such a network it passes the network gate, and
 * it stays subject to the unusable-cloud-DB gate like every other automatic sync. Dependencies come
 * from [KoinPlatform.getKoin], as in [FeedRefreshWorker].
 */
class DeferredSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = KoinPlatform.getKoin()
        if (!koin.get<SettingsRepository>().isSetupComplete()) return Result.success()
        return try {
            val sync = koin.get<SyncRepository>()
            val syncResult = sync.sync(SyncTrigger.AUTOMATIC)
            when {
                // The network turned restrictive again before the gate was evaluated: the sync was
                // deferred (reported as Ok) and this job is the one unique job, so finishing would
                // leave the deferral with nothing to run it. The retry keeps the job's network constraint.
                sync.syncDeferredForNetwork.value -> Result.retry()
                // Same retry rule as FeedRefreshWorker: only a storage failure is worth repeating.
                syncResult.errorOrNull is CloudStorageException -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error(LOG_TAG, "Deferred sync failed", e)
            Result.retry()
        }
    }
}

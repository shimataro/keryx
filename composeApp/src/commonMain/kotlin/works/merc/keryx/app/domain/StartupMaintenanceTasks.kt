package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import org.koin.core.Koin
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.platform.SelfUpdateCheckSupport

private const val LOG_TAG = "StartupMaintenanceTasks"

/**
 * Runs one startup/background maintenance step, isolating its failure from the other steps in the
 * same sequence: an exception here is logged and swallowed so the caller can move on to the next
 * step regardless. [CancellationException] is rethrown rather than swallowed — catch order matters
 * here, since it is itself an [Exception] — because it signals the calling scope was cancelled
 * (e.g. the hosting Activity was destroyed), not that this particular step failed.
 *
 * Kept as a plain commonMain function rather than inlined at each call site — desktop's
 * `StartupTasks.kt` and Android's `AndroidStartupTasks.kt` both call this for every step of their
 * own maintenance sequence — partly so the two platforms share one isolation behavior instead of
 * two hand-written copies, and partly so it is unit-testable without an `androidUnitTest` source
 * set (this module has none) — the same reason [BackgroundRefreshSchedule] is a pure commonMain
 * mapping.
 */
internal suspend fun runMaintenanceStep(name: String, step: suspend () -> Unit) {
    try {
        step()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.error(LOG_TAG, "Startup task '$name' failed", e)
    }
}

/**
 * Soft-deletes expired cached articles once per day (gated on [works.merc.keryx.app.data.local.db.LocalSettings.lastCacheCleanupAt],
 * mirroring [maybeRebuildFtsIndex]'s own 24h gate).
 */
internal suspend fun cleanUpArticleCacheIfDue(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val now = koin.get<Clock>().nowMillis()
    val last = settingsRepository.getLocalSettings().lastCacheCleanupAt
    if (last != null && now - last < MILLIS_PER_DAY) return
    val days = settingsRepository.getCacheRetentionDays()
    koin.get<ArticleRepository>().deleteExpiredArticles(days)
    settingsRepository.mutateLocalSettings { it.copy(lastCacheCleanupAt = now) }
}

/**
 * Refreshes all feeds and processes notifications for newly fetched articles according to the local notification setting.
 */
internal suspend fun refreshFeedsAndNotify(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val results = koin.get<ActivityCenter>().trackFeedRefresh { koin.get<FeedRepository>().refreshAll() }
    koin.get<NewArticleNotifier>().notifyIfEnabled(
        results, settingsRepository.getLocalSettings().notificationEnabled, koin.get<NotificationMessages>(),
    )
}

/**
 * Checks for an available application update on the automatic/background schedule.
 *
 * The check itself, and posting/replacing the update-related notification-center row, are entirely
 * [UpdateRepository]'s responsibility now — this only adds the one thing specific to the
 * *automatic* schedule: stamping [works.merc.keryx.app.data.local.db.LocalSettings.lastUpdateCheckAt]
 * (a manual "check for update" in Settings deliberately never touches it — see
 * [works.merc.keryx.app.ui.settings.SettingsViewModel.checkForUpdate]'s own KDoc).
 *
 * @param koin The dependency injection container used to resolve [SelfUpdateCheckSupport],
 *   [UpdateRepository], and [SettingsRepository].
 */
internal suspend fun checkForUpdateAndNotify(koin: Koin) {
    if (!koin.get<SelfUpdateCheckSupport>().isSupported()) return
    koin.get<UpdateRepository>().check()
    koin.get<SettingsRepository>().mutateLocalSettings { it.copy(lastUpdateCheckAt = SystemClock.nowMillis()) }
}

/**
 * Rebuilds the full FTS index when the application is idle and at least 24 hours have passed since the previous rebuild.
 */
internal suspend fun maybeRebuildFtsIndex(koin: Koin) {
    val activityCenter = koin.get<ActivityCenter>()
    if (activityCenter.syncing.value || activityCenter.feedRefreshing.value) return
    val settingsRepository = koin.get<SettingsRepository>()
    val now = SystemClock.nowMillis()
    val last = settingsRepository.getLocalSettings().lastFtsRebuiltAt
    if (last != null && now - last < MILLIS_PER_DAY) return
    koin.get<FtsManager>().rebuildIndex()
    settingsRepository.mutateLocalSettings { it.copy(lastFtsRebuiltAt = now) }
}

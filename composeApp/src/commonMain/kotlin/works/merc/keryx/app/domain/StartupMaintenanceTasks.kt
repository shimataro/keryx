package works.merc.keryx.app.domain

import org.jetbrains.compose.resources.getString
import org.koin.core.Koin
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.platform.selfUpdateCheckSupported
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.update_available_notification

/**
 * Soft-deletes expired cached articles once per day (gated on [works.merc.keryx.app.data.local.db.LocalSettings.lastCacheCleanupAt],
 * mirroring [maybeRebuildFtsIndex]'s own 24h gate).
 */
internal suspend fun cleanUpArticleCacheIfDue(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val now = SystemClock.nowMillis()
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
 * Checks for an available application update and notifies the user when one is found.
 *
 * @param koin The dependency injection container used to resolve update and notification services.
 */
internal suspend fun checkForUpdateAndNotify(koin: Koin) {
    if (!selfUpdateCheckSupported) return
    val settingsRepository = koin.get<SettingsRepository>()
    val status = koin.get<UpdateChecker>().check()
    settingsRepository.mutateLocalSettings { it.copy(lastUpdateCheckAt = SystemClock.nowMillis()) }
    if (status is UpdateStatus.Available) {
        val message = getString(Res.string.update_available_notification, status.version)
        koin.get<NotificationCenter>().add(
            AppNotification(
                id = IdGenerator.newId(),
                level = AppNotificationLevel.INFO,
                message = message,
                timestampMillis = SystemClock.nowMillis(),
                // Acting on the notification goes straight to the release page — the only useful
                // next step for "a new version exists".
                action = AppNotificationAction.OpenUrl(status.url),
            ),
        )
    }
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

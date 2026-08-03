package works.merc.keryx.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.koin.core.Koin
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.MILLIS_PER_MINUTE
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.domain.shouldCheckForUpdate
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_app_translocated_detail
import works.merc.keryx.app.resources.update_available_notification

private const val LOG_TAG = "StartupTasks"

internal suspend fun runStartupTasks(koin: Koin) {
    runCatching {
        warnIfAppTranslocated(koin)
        val settingsRepository = koin.get<SettingsRepository>()
        val settings = settingsRepository.getLocalSettings()
        val now = SystemClock.nowMillis()
        val last = settings.lastCacheCleanupAt
        if (last == null || now - last >= MILLIS_PER_DAY) {
            val days = settingsRepository.getCacheRetentionDays()
            koin.get<ArticleRepository>().deleteExpiredArticles(days)
            settingsRepository.mutateLocalSettings { it.copy(lastCacheCleanupAt = now) }
        }
        if (koin.get<CloudSession>().isConnected()) {
            koin.get<SyncRepository>().sync()
        }
        refreshFeedsAndNotify(koin)
        checkForUpdateAndNotify(koin)
        maybeRebuildFtsIndex(koin)
    }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Startup tasks failed", it) }
}

/**
 * Rebuilds the full FTS index when the application is idle and at least 24 hours have passed since the previous rebuild.
 */
private suspend fun maybeRebuildFtsIndex(koin: Koin) {
    val activityCenter = koin.get<ActivityCenter>()
    if (activityCenter.syncing.value || activityCenter.feedRefreshing.value) return
    val settingsRepository = koin.get<SettingsRepository>()
    val now = SystemClock.nowMillis()
    val last = settingsRepository.getLocalSettings().lastFtsRebuiltAt
    if (last != null && now - last < MILLIS_PER_DAY) return
    koin.get<FtsManager>().rebuildIndex()
    settingsRepository.mutateLocalSettings { it.copy(lastFtsRebuiltAt = now) }
}

/**
 * Refreshes all feeds and, if new articles were fetched and notifications are enabled, notifies
 * via [NewArticleNotifier]. Shared by [runStartupTasks] and [backgroundUpdateLoop].
 */
private suspend fun refreshFeedsAndNotify(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val results = koin.get<ActivityCenter>().trackFeedRefresh { koin.get<FeedRepository>().refreshAll() }
    koin.get<NewArticleNotifier>().notifyIfEnabled(
        results, settingsRepository.getLocalSettings().notificationEnabled, koin.get<NotificationMessages>(),
    )
}

/**
 * Desktop background refresh loop (coroutine equivalent of a periodic timer). Also drives the
 * periodic (non-startup) update check on its own, independent cadence — see
 * [shouldCheckForUpdate] — so setting feed refresh to "manual only" (minutes <= 0) doesn't starve
 * update checking of everything but the once-per-launch startup check. Feed refresh is similarly
 * skipped here when "manual only" is set, but still gets one unconditional check at startup via
 * [runStartupTasks].
 */
internal suspend fun backgroundUpdateLoop(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    while (true) {
        val minutes = settingsRepository.getLocalSettings().refreshIntervalMinutes
        delay(if (minutes <= 0) MILLIS_PER_MINUTE else minutes * MILLIS_PER_MINUTE)
        runCatching {
            if (minutes > 0) {
                refreshFeedsAndNotify(koin)
                koin.get<SyncRepository>().sync()
            }
            val settings = settingsRepository.getLocalSettings()
            if (shouldCheckForUpdate(SystemClock.nowMillis(), settings.lastUpdateCheckAt, settings.updateCheckIntervalHours)) {
                checkForUpdateAndNotify(koin)
            }
            maybeRebuildFtsIndex(koin)
        }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Background update cycle failed", it) }
    }
}

/**
 * Checks for an available application update and notifies the user when one is found.
 *
 * @param koin The dependency injection container used to resolve update and notification services.
 */
private suspend fun checkForUpdateAndNotify(koin: Koin) {
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
 * Reads an OPML file opened via a file association (double-click / "Open With" on an `.opml`
 * file), subscribes to every feed it lists, and surfaces the result via the notification center.
 * No dialog is shown for this — [activationRequests] brings the window to front and the new feeds
 * appear live in the (already-visible) feed list, matching the "restrained notification" treatment
 * error-design.md already prescribes for background-originated events.
 */
internal suspend fun handleOpenedOpmlFile(koin: Koin, path: String) {
    val xml = FileIO.readText(path) ?: run {
        Log.warn(LOG_TAG, "Could not read the opened OPML file")
        return
    }
    val outcome = runCatching { koin.get<OpmlImporter>().import(xml) }
        .getOrElse {
            Log.warn(LOG_TAG, "Failed to import the opened OPML file", it)
            return
        }
    val message = koin.get<NotificationMessages>().opmlImported(outcome.added, outcome.failed)
    koin.get<NotificationCenter>().add(
        AppNotification(
            id = IdGenerator.newId(),
            level = AppNotificationLevel.INFO,
            message = message,
            timestampMillis = SystemClock.nowMillis(),
        ),
    )
    activationRequests.tryEmit(Unit)
}

/**
 * Warns the user when the application is running from a translocated path that may prevent
 * `keryx://` OAuth callbacks from reaching the application.
 */
private suspend fun warnIfAppTranslocated(koin: Koin) {
    val exePath = currentExecutablePath()
    if (!isTranslocatedPath(exePath)) return
    Log.warn(LOG_TAG, "App is running from a translocated path ($exePath); keryx:// OAuth linking may fail")
    koin.get<NotificationCenter>().add(
        AppNotification(
            id = IdGenerator.newId(),
            level = AppNotificationLevel.WARNING,
            message = getString(Res.string.notification_app_translocated),
            timestampMillis = SystemClock.nowMillis(),
            // Nothing to navigate to — the useful next step is understanding the cause and the fix,
            // so acting on it opens an explanatory dialog in place.
            action = AppNotificationAction.ShowInfoDialog(
                getString(Res.string.notification_app_translocated_detail),
            ),
        ),
    )
}

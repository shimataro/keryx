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
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import works.merc.keryx.app.domain.shouldCheckForUpdate
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_app_translocated_detail

private const val LOG_TAG = "StartupTasks"

/**
 * Executes startup maintenance, synchronization, feed refresh, update checks, and search-index maintenance.
 */
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
            koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
        }
        refreshFeedsAndNotify(koin)
        checkForUpdateAndNotify(koin)
        maybeRebuildFtsIndex(koin)
    }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Startup tasks failed", it) }
}

/**
 * Runs periodic background maintenance tasks.
 *
 * Feed refreshing and synchronization occur when the configured refresh interval is positive.
 * Update checks and full-text index maintenance run independently of feed refresh settings.
 */
internal suspend fun backgroundUpdateLoop(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    while (true) {
        val minutes = settingsRepository.getLocalSettings().refreshIntervalMinutes
        delay(if (minutes <= 0) MILLIS_PER_MINUTE else minutes * MILLIS_PER_MINUTE)
        runCatching {
            if (minutes > 0) {
                refreshFeedsAndNotify(koin)
                koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
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
 * Imports feeds from an OPML file opened through a file association and notifies the user of the result.
 *
 * @param path The path to the OPML file.
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

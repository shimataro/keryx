package works.merc.keryx.app

import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.koin.core.Koin
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MILLIS_PER_MINUTE
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.checkForUpdateAndNotify
import works.merc.keryx.app.domain.cleanUpArticleCacheIfDue
import works.merc.keryx.app.domain.importOpmlAndNotify
import works.merc.keryx.app.domain.maybeRebuildFtsIndex
import works.merc.keryx.app.domain.refreshFeedsAndNotify
import works.merc.keryx.app.domain.runMaintenanceStep
import works.merc.keryx.app.domain.shouldCheckForUpdate
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_app_translocated_detail

private const val LOG_TAG = "StartupTasks"

/**
 * Executes startup maintenance, synchronization, feed refresh, update checks, and search-index
 * maintenance. Each step runs through [runMaintenanceStep] (the same isolation
 * `AndroidStartupTasks.kt`'s `runAndroidStartupTasks` uses, sharing its step names) so that one
 * step's failure — e.g. `cleanUpArticleCacheIfDue` hitting `FtsManager`'s `busy_timeout` — does not
 * skip the rest of the sequence the way a single shared `runCatching` used to.
 */
internal suspend fun runStartupTasks(koin: Koin) {
    runMaintenanceStep("translocationWarning") { warnIfAppTranslocated(koin) }
    // Every step below eventually calls SettingsRepository.mutateLocalSettings (to record its own
    // "last ran at" timestamp), which persists local_settings.json in the background — the same
    // file whose mere *existence* is isSetupComplete()'s signal that setup finished (SetupViewModel
    // calls flush() at that point deliberately). Running any of this before setup completes could
    // race that check and make a fresh install skip the Setup screen entirely. None of it is useful
    // pre-setup anyway (no feeds to refresh, no sync configured). This gate is deliberately not
    // itself a runMaintenanceStep — it's control flow for the whole sequence, not a step that can
    // independently fail.
    if (!koin.get<SettingsRepository>().isSetupComplete()) return
    runMaintenanceStep("cacheCleanup") { cleanUpArticleCacheIfDue(koin) }
    runMaintenanceStep("sync") {
        if (koin.get<CloudSession>().isConnected()) {
            koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC)
        }
    }
    runMaintenanceStep("feedRefresh") { refreshFeedsAndNotify(koin) }
    runMaintenanceStep("updateCheck") { checkForUpdateAndNotify(koin) }
    runMaintenanceStep("ftsRebuild") { maybeRebuildFtsIndex(koin) }
}

/**
 * Runs periodic background maintenance tasks.
 *
 * Feed refreshing and synchronization occur when the configured refresh interval is positive.
 * Update checks and full-text index maintenance run independently of feed refresh settings. Each
 * step runs through [runMaintenanceStep] for the same reason [runStartupTasks] does — a failure in
 * one (e.g. a feed fetch timing out) must not skip `sync`/`checkForUpdateAndNotify`/
 * `maybeRebuildFtsIndex` for the rest of this cycle.
 */
internal suspend fun backgroundUpdateLoop(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    while (true) {
        val minutes = settingsRepository.getLocalSettings().refreshIntervalMinutes
        delay(if (minutes <= 0) MILLIS_PER_MINUTE else minutes * MILLIS_PER_MINUTE)
        // See runStartupTasks's own comment: nothing here should touch local_settings.json before
        // setup completes.
        if (!settingsRepository.isSetupComplete()) continue
        if (minutes > 0) {
            runMaintenanceStep("feedRefresh") { refreshFeedsAndNotify(koin) }
            runMaintenanceStep("sync") { koin.get<SyncRepository>().sync(SyncTrigger.AUTOMATIC) }
        }
        val settings = settingsRepository.getLocalSettings()
        if (shouldCheckForUpdate(SystemClock.nowMillis(), settings.lastUpdateCheckAt, settings.updateCheckIntervalHours)) {
            runMaintenanceStep("updateCheck") { checkForUpdateAndNotify(koin) }
        }
        runMaintenanceStep("ftsRebuild") { maybeRebuildFtsIndex(koin) }
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
    importOpmlAndNotify(koin, xml)
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

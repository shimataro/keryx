package works.merc.keryx.app

import org.koin.core.Koin
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.backgroundUpdateLoop
import works.merc.keryx.app.domain.runMaintenanceStep
import works.merc.keryx.app.domain.runStartupMaintenance
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.update.cleanUpStaleSelfReplaceArtifacts
import works.merc.keryx.app.presentation.settings.requestOpenedOpmlImport

private const val LOG_TAG = "StartupTasks"

/**
 * Executes startup maintenance, synchronization, feed refresh, update checks, and search-index
 * maintenance. The shared five-step sequence itself is `domain/StartupMaintenanceTasks.kt`'s
 * [runStartupMaintenance] (also used by Android's `AndroidStartupTasks.kt`); this only adds the
 * two desktop-specific steps that have no Android equivalent, run before it.
 */
internal suspend fun runStartupTasks(koin: Koin) {
    runMaintenanceStep("translocationWarning") { warnIfAppTranslocated(koin) }
    runMaintenanceStep("staleSelfReplaceCleanup") { cleanUpStaleSelfReplaceArtifacts(koin.get<InstallLocation>()) }
    runStartupMaintenance(koin)
}

/**
 * Reads an OPML file opened through a file association and asks Settings ▸ Data to import it (see
 * [requestOpenedOpmlImport]) — `null` when it can't be read, so the failure is shown there too —
 * then brings the window to front so the import is visible.
 *
 * @param path The path to the OPML file.
 */
internal suspend fun handleOpenedOpmlFile(koin: Koin, path: String) {
    val xml = FileIO.readText(path)
    if (xml == null) Log.warn(LOG_TAG, "Could not read the opened OPML file")
    requestOpenedOpmlImport(koin, xml)
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
            text = NotificationText.AppTranslocated,
            timestampMillis = SystemClock.nowMillis(),
            // Nothing to navigate to — the useful next step is understanding the cause and the fix,
            // so acting on it opens an explanatory dialog in place.
            action = AppNotificationAction.ShowInfoDialog(InfoDialogText.APP_TRANSLOCATED),
        ),
    )
}

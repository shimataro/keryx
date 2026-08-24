package works.merc.keryx.app.domain

import org.koin.core.Koin
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SystemClock

private const val LOG_TAG = "OpmlOpenHandler"

/**
 * Imports an already-read OPML document (opened via the OS file association — desktop's
 * `.opml` double-click, or Android's `ACTION_VIEW` intent) and records the outcome in the
 * notification center, the platform-independent half of the flow.
 *
 * Reading the file itself stays platform-specific — desktop's `handleOpenedOpmlFile` reads a
 * filesystem path via `FileIO`, Android's `handleOpmlOpenIfPresent` reads a `content://` `Uri` via
 * `ContentResolver` — and desktop additionally re-activates the window afterward
 * (`activationRequests.tryEmit`), which has no Android equivalent (the incoming intent already
 * brings the Activity to the foreground), so that step stays in the desktop caller too.
 *
 * @param xml The OPML document contents, already read from wherever it came from.
 */
internal suspend fun importOpmlAndNotify(koin: Koin, xml: String) {
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
}

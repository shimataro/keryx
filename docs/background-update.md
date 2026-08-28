# Background Update

[日本語](background-update.ja.md)

## Platform Strategy

| Platform | Update | Implementation |
| --- | --- | --- |
| Windows / macOS / Linux | ✅ Runs reliably at the specified interval | Coroutine-based periodic loop (current) |
| Android | ✅ Roughly at the specified interval (subject to Doze/App Standby) | `WorkManager` `PeriodicWorkRequest` (current) |
| iOS | ⚠️ OS decides execution timing | BGTaskScheduler (planned) |

## Desktop Implementation (`desktopMain/main.kt` + `StartupTasks.kt`)

`main()` launches an app-scope coroutine that runs `backgroundUpdateLoop` (`StartupTasks.kt`), looping
at `refreshIntervalMinutes` intervals. The sketch below is abridged — per-cycle error handling and the
separately-scheduled update check are omitted. `backgroundUpdateLoop` itself is desktop-only (a plain
coroutine loop; Android's equivalent is a `WorkManager` `PeriodicWorkRequest`, see the Platform
Strategy table above and "Android Implementation" below), but the three functions it calls each cycle
— `refreshFeedsAndNotify`, `checkForUpdateAndNotify`, `maybeRebuildFtsIndex` — are platform-independent
and live in commonMain's `domain/StartupMaintenanceTasks.kt`, so the Android worker calls the same
implementations instead of duplicating them.

```kotlin
while (true) {
    val minutes = settings.refreshIntervalMinutes
    delay(if (minutes <= 0) 60_000L else minutes * 60_000L)  // "Manual only" (minutes <= 0) wakes every minute
    if (minutes > 0) {
        refreshFeedsAndNotify()   // Refresh all feeds (ETag / Last-Modified differential fetch), then
                                  // NewArticleNotifier.notifyBackground(newArticles(newCount)) when
                                  // new articles arrived and notifications are enabled
        sync()                    // Cloud sync
    }
    maybeRebuildFtsIndex()        // Daily idle FTS rebuild heal (see below)
}
```

- The interval setting is re-read every loop, so changes take effect from the next cycle (no explicit rescheduling needed).
- Errors during update do not crash the app; they are recorded in the notification center (handled inside `FeedRepository.refreshFeed`).
- New-article notifications reach the OS through one of three platform paths, all fed by the same
  `NewArticleNotifier.trayEvents` flow (`TrayState` can only be created inside Compose's `application {}`
  scope, so a `MutableSharedFlow` bridges it): macOS uses `TrayIcon.displayMessage`, Linux with a
  StatusNotifierItem host uses `org.freedesktop.Notifications.Notify`, and Windows (plus Linux without
  an SNI host) uses `TrayState.sendNotification`. See "Desktop Tray" in [app-architecture.md](app-architecture.md).

## Android Implementation (`androidMain/background/` + `AndroidStartupTasks.kt`)

`KeryxApplication.onCreate` calls `startBackgroundRefresh` (`background/BackgroundRefresh.kt`),
which observes `SettingsRepository.localSettings`' `refreshIntervalMinutes` for the rest of the
process's life and keeps a `WorkManager` unique periodic job (`"feed_refresh"`) in sync with it —
so a setting change takes effect immediately, with no restart needed. The mapping from the setting
to a schedule is a pure function, `domain/BackgroundRefreshSchedule.kt`'s `backgroundRefreshSchedule`
(commonMain, unit-tested — this module has no `androidUnitTest` source set to test Android-specific
classes in): "Manual only" (`<= 0`) cancels the job outright; a positive value below `WorkManager`'s
own 15-minute floor (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`) is coerced up to it rather
than silently disabled. The app's own UI never offers a value below 15 minutes, so this only
matters for a hand-edited or migrated `local_settings.json`.

`background/FeedRefreshWorker.kt` (a `CoroutineWorker`, instantiated by `WorkManager`'s own
`WorkerFactory` via reflection — dependencies are resolved from `KoinPlatform.getKoin()` inside
`doWork()` rather than constructor-injected) runs exactly the same sequence desktop's
`backgroundUpdateLoop` runs each cycle: `refreshFeedsAndNotify`, then (if `CloudSession.isConnected()`)
`SyncRepository.sync(SyncTrigger.AUTOMATIC)`, then `checkForUpdateAndNotify` if `shouldCheckForUpdate` says it is due, then `maybeRebuildFtsIndex`.
On Android, `CloudSession` currently has Dropbox/OneDrive providers only (no Google Drive — see
[sync-architecture.md](sync-architecture.md)'s "Google Drive on Android"), so `sync()` is a genuine
no-op when the user hasn't connected either of those, or — even when connected — while
`autoSyncSuspended` is true (a prior `CloudDataIncompatibleException` gates further
`SyncTrigger.AUTOMATIC` attempts until a reset or a successful manual sync; see
`SyncRepository.sync`'s own KDoc). A caught exception (an unexpected failure, not `sync()`'s own
`Result` type) returns `Result.retry()`, deferring to `WorkManager`'s own backoff policy; `sync()`
returning a `Result.Err` instead is handled separately — `Result.retry()` only for the category
`error-design.md`'s auto-retry table marks retryable (`CloudStorageException`), leaving the
permanent failures it marks non-retryable (`CloudAuthException`/`SchemaVersionException`/
`CloudDataIncompatibleException`) for the next regularly-scheduled run instead.

`MainActivity.onCreate` calls `runAndroidStartupTasks` (`AndroidStartupTasks.kt`) — the Android
counterpart to desktop's `runStartupTasks`, minus the macOS-specific translocation warning (which
has no Android equivalent). It runs `cleanUpArticleCacheIfDue` (see below), then — same gate and
position as desktop's `runStartupTasks` — the initial cloud sync, then the same three maintenance
functions `FeedRefreshWorker` runs. This deliberately lives in the *Activity*, not
`Application.onCreate`: the latter also runs when `WorkManager` wakes the process to run
`FeedRefreshWorker`, and running the full startup sequence on every background wakeup would
duplicate the refresh/sync/update-check/FTS work the worker itself just did. A process-local guard
(`startupTasksRan`) keeps it to once per process even though `onCreate` re-runs on configuration
changes (e.g. rotation) that recreate the Activity without restarting the process. Each of the five
steps runs in isolation (`runMaintenanceStep`), so one step throwing — e.g. `maybeRebuildFtsIndex`
hitting `FtsManager`'s `busy_timeout` — does not skip the rest of the sequence. The guard is set
only once every step has been attempted, not before: a call that returns early because setup isn't
finished yet, or because `FeedRefreshWorker` currently holds the maintenance lock, does not consume
this process's only chance to run `cleanUpArticleCacheIfDue`, which `FeedRefreshWorker` never runs
itself.

New-article notifications reach the OS through `domain/OsNotificationSink.kt`, a `fun interface`
(`post(message: String, count: Int)`) Android binds (in `platformModule`) to
`platform/AndroidNotificationSink.kt`, a `NotificationManagerCompat` poster — a different path from
desktop's `NewArticleNotifier.trayEvents` collection (see that class's own KDoc for why: a
`WorkManager`-woken process has no guarantee a `trayEvents` collector is already attached by the
time a refresh finishes, since `trayEvents` has replay 0 and silently drops anything emitted before
a collector exists; desktop's own binding is a no-op for the same reason). `AndroidNotificationSink`
guards every post on `NotificationManagerCompat.areNotificationsEnabled()`, which alone covers both
the Android 13+ `POST_NOTIFICATIONS` runtime permission and a user-level app/channel block — the
permission itself is requested via `platform/NotificationPermission.kt`'s
`rememberNotificationPermissionRequester`, called once at startup (`App.kt`, if the user's own
"notifications enabled" setting is already on) and again whenever `NotificationsTab` flips that
setting on. Once a user denies the system dialog a second time ("don't ask again"), Android itself
stops showing it for subsequent programmatic requests — the setting can still be left on, it just
won't produce a notification until the user grants it from OS settings directly; this app does not
build a "please open your device settings" flow for that case.

The posted notification's small icon is `composeApp/src/androidMain/res/drawable/ic_stat_keryx.xml`
— a monochrome, alpha-only VectorDrawable silhouette of the Keryx logo mark (converted by hand from
`design/icons/svg/app_icon_foreground.svg`, since VectorDrawable has no `<rect>`/`<circle>`
primitives), living in `:composeApp`'s own `androidMain/res/` (a conventional AGP resource
directory generating `works.merc.keryx.app.R`, distinct from Compose Multiplatform's own
`composeResources/`) — `:composeApp` cannot depend on `:androidApp`'s resources, so this could not
live alongside the launcher icon in `androidApp/src/main/res/`. The `count` parameter passed to
`OsNotificationSink.post` is forwarded to `NotificationCompat.Builder.setNumber`, which only affects
the count shown in the launcher icon's long-press menu — **not** any digit drawn on the icon itself.
There is no Android API to set an app-icon badge count independent of an active notification (unlike
iOS's `setApplicationIconBadgeNumber`), so unlike desktop's `IconBadge.kt` (`drawUnreadBadge`, which
composites the total unread count directly onto the Dock/taskbar/window icon), Android relies
entirely on the OS's own notification dot — tied to whether a notification is currently active, not
to the unread count — plus the long-press count above. This is a deliberate asymmetry, not a gap to
close: posting one persistent, undismissable notification just to keep an icon-level badge alive
would fight Android's own notification model. See `external-spec.md` §7 for the user-facing summary.

The in-app "check for update" (`checkForUpdateAndNotify`, and the Updates settings tab) is gated on
`platform/SelfUpdateCheck.kt`'s `selfUpdateCheckSupported`, backed by
`core/UpdateDistribution.kt`'s `isSelfUpdateCheckSupported` fed this app's own installer package
name (`com.android.vending` / the legacy `com.google.android.feedback` → disabled; anything else,
including `null`, → enabled, matching desktop's always-on behavior). This is a UX call, not a
Google Play policy requirement — Play only disallows an app replacing *itself* outside Play's own
mechanism or downloading executable code from elsewhere, neither of which this feature does — the
reason is that Play already auto-updates the app, so a second, GitHub-flavored update path next to
it would only confuse the user about which one to use.

## Feed Update Efficiency

`FeedFetcher` sends `If-None-Match` (ETag) / `If-Modified-Since` (Last-Modified), and returns empty on 304 (no new articles). Updated ETag / Last-Modified values are saved in the `feeds` table.

A 304 answer is flagged as `FetchedFeed.notModified`, and `FeedRepository` then leaves the stored
validators alone. Without that flag a 304's empty result is indistinguishable from a feed that
stopped sending validators, and writing it back NULLed `etag` / `last_modified` — so the next poll
sent no conditional headers and the server had to return the whole feed, defeating the mechanism on
every other refresh.

Every `feeds` write on the refresh path is also guarded on the value actually changing. The
article-list query joins `feeds`, so SQLDelight re-runs it on any `feeds` write; a refresh where
nothing changed now writes nothing and triggers no re-query.

`FeedRepository.refreshAll` fetches feeds' network data **concurrently** (bounded to `REFRESH_FETCH_CONCURRENCY` simultaneous fetches), then applies each feed's DB writes **serially** in feed order. A large subscription list therefore refreshes in roughly the time of its slowest fetches rather than the sum of every fetch. DB writes stay single-threaded — the JVM SQLite driver opens a fresh connection per statement, so concurrent writes could contend — and each feed's articles are still committed one feed at a time, so they appear incrementally in the list as the refresh progresses.

## Startup Tasks (`runStartupTasks` / `runAndroidStartupTasks`)

`runStartupTasks` itself is desktop-only orchestration (`desktopMain/StartupTasks.kt`) — it also warns
about a macOS-translocated app install, a desktop-specific concern — but cache cleanup, feed refresh
notification, update notification, and FTS rebuilding (steps 1 and 3 below) delegate to the
platform-independent functions in commonMain's `domain/StartupMaintenanceTasks.kt`. Android's
`runAndroidStartupTasks` (see above) calls the same step 1 and step 3 functions directly and runs
step 2 itself too, the same way desktop does — both call `SyncRepository.sync()` inline, guarded on
`CloudSession.isConnected()`, rather than through a `StartupMaintenanceTasks` function:

1. Cache cleanup (`cleanUpArticleCacheIfDue`, if 24+ hours since last run).
2. If a cloud provider is connected, initial sync (`SyncRepository.sync(SyncTrigger.AUTOMATIC)`) —
   Dropbox / Google Drive / OneDrive on desktop, Dropbox / OneDrive on Android.
3. FTS full rebuild (`maybeRebuildFtsIndex`, only if 24+ hours since last run **and** idle; see below).
4. FTS initial creation + unindexed row incremental insertion is done by `FtsManager.ensureIndexed()`: on desktop, blocked on with `runBlocking` before `application {}` (acceptable there, since it only delays showing the first window). `KeryxApplication.onCreate` instead launches it fire-and-forget on the shared app-scope `CoroutineScope` — blocking `Application.onCreate` would delay every Android cold start instead of just the first window, and a search performed in the brief window before it completes just returns fewer/no hits rather than failing.

## Daily FTS Rebuild Heal (`maybeRebuildFtsIndex`)

Hot paths (feed refresh, sync merge) use `FtsManager.indexMissing()` to incrementally index only new articles (no full rebuild). Therefore, to resolve the staleness of existing-article indexes when body text is updated, the full rebuild is demoted to **daily idle** execution. `runStartupTasks`, each iteration of `backgroundUpdateLoop`, `runAndroidStartupTasks`, and each run of `FeedRefreshWorker` all call `maybeRebuildFtsIndex`, and `rebuildIndex()` is executed only when the `local_settings.lastFtsRebuiltAt` 24h gate and `ActivityCenter` idle state (no sync / update running) are both satisfied, then `lastFtsRebuiltAt` is recorded. `'rebuild'` is atomic + `busy_timeout` wait, so running searches also do not regress to zero results. See [sync-architecture.md](sync-architecture.md) "FTS5 handling" for details.

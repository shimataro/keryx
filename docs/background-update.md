# Background Update

[日本語](background-update.ja.md)

## Platform Strategy

| Platform | Update | Implementation |
| --- | --- | --- |
| Windows / macOS / Linux | ✅ Runs reliably at the specified interval | Coroutine-based periodic loop (current) |
| Android | ✅ Roughly at the specified interval | WorkManager (planned) |
| iOS | ⚠️ OS decides execution timing | BGTaskScheduler (planned) |

## Desktop Implementation (`desktopMain/main.kt` + `StartupTasks.kt`)

`main()` launches an app-scope coroutine that runs `backgroundUpdateLoop` (`StartupTasks.kt`), looping
at `refreshIntervalMinutes` intervals. The sketch below is abridged — per-cycle error handling and the
separately-scheduled update check are omitted. `backgroundUpdateLoop` itself is desktop-only (a plain
coroutine loop; Android's future equivalent is a `WorkManager` `PeriodicWorkRequest`, see the Platform
Strategy table above), but the three functions it calls each cycle — `refreshFeedsAndNotify`,
`checkForUpdateAndNotify`, `maybeRebuildFtsIndex` — are platform-independent and live in commonMain's
`domain/StartupMaintenanceTasks.kt`, so a future Android worker can call the same implementations
instead of duplicating them.

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

## Startup Tasks (`runStartupTasks`)

`runStartupTasks` itself is desktop-only orchestration (`desktopMain/StartupTasks.kt`) — it also warns
about a macOS-translocated app install, a desktop-specific concern, and runs step 2 (initial sync)
directly through `SyncRepository` — but feed refresh notification, update notification, and FTS
rebuilding (step 3 below) delegate to the platform-independent functions in commonMain's
`domain/StartupMaintenanceTasks.kt`:

1. Cache cleanup (if 24+ hours since last run).
2. If Dropbox is connected, initial sync.
3. FTS full rebuild (`maybeRebuildFtsIndex`, only if 24+ hours since last run **and** idle; see below).
4. (FTS initial creation + unindexed row incremental insertion is already done by `FtsManager.ensureIndexed()` before `application {}`.)

## Daily FTS Rebuild Heal (`maybeRebuildFtsIndex`)

Hot paths (feed refresh, sync merge) use `FtsManager.indexMissing()` to incrementally index only new articles (no full rebuild). Therefore, to resolve the staleness of existing-article indexes when body text is updated, the full rebuild is demoted to **daily idle** execution. `runStartupTasks` and each iteration of `backgroundUpdateLoop` call `maybeRebuildFtsIndex`, and `rebuildIndex()` is executed only when the `local_settings.lastFtsRebuiltAt` 24h gate and `ActivityCenter` idle state (no sync / update running) are both satisfied, then `lastFtsRebuiltAt` is recorded. `'rebuild'` is atomic + `busy_timeout` wait, so running searches also do not regress to zero results. See [sync-architecture.md](sync-architecture.md) "FTS5 handling" for details.

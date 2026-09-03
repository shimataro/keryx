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
- Errors during update do not crash the app; they are recorded in the notification center (handled inside `FeedRepository.refreshFeed`). On Android they are additionally announced in a Snackbar — but only while the app's window actually has focus, so one raised by `FeedRefreshWorker` in the background waits and is announced once the user comes back, rather than timing out unseen. See "Notification Center" in [error-design.md](error-design.md).
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

## In-App Update

Where `selfUpdateCheckSupported` offers a check at all, `domain/UpdateRepository` (a Koin `single`,
so it and any in-flight download outlive a closed settings dialog) drives it past a plain
"here's a link" into an actual download-and-install, behind one `StateFlow<UpdateState>`:
`Idle → Checking → (UpToDate | Available) → Downloading → Verifying → Ready → Installing`, with
`Failed` reachable from `Checking`/`Downloading`/`Verifying`. Every surface — the Updates settings
tab, the desktop tray's one update menu item, the notification-center bell — reads this same state,
so they can never disagree about what's currently true. **No step ever runs unattended**: a check
never auto-downloads, and a downloaded file never auto-installs — download and install are each a
separate, explicit click (Updates tab button, or the tray item once one is offered).

- **Which file, and what to do with it.** `UpdateChecker` parses the GitHub release's `assets[]`;
  `domain/UpdateAsset.kt`'s `selectUpdateAsset` picks the one matching this build's install form
  (never an asset GitHub hasn't finished processing, and never one with no verifiable `sha256`
  digest — see "Integrity verification" below) — `.aab` is never a candidate at all, since no
  `UpdateAssetKind` suffix ends in it. `domain/UpdateInstallPolicy.kt`'s `updatePlan` then decides
  what an update should actually *do* with that asset, purely from the install location
  (`platform/InstallLocation.kt`'s `detectInstallLocation()` — a macOS `.app`, a Windows/Linux
  portable ZIP, a Windows MSI install, an Android sideload, …) and the already-selected asset (or
  its absence) — touching neither the network nor the filesystem itself: `SelfReplace` (replace
  files in place, then relaunch), `RunInstaller` (hand
  off to the OS's own installer), `OpenReleasePage` (this install form can't be updated in place —
  a Linux deb/rpm install, a Linux Snap install (`InstallKind.LINUX_SNAP`, detected via the `SNAP`
  environment variable snapd sets — its `/snap/keryx/<revision>/` mount is read-only, so self-replace
  is impossible there regardless of the distribution channel), macOS App Translocation, an unwritable
  install directory, no matching asset in this release), or `NotOffered` (a development run, or an
  Android build installed through Google Play). `UpdateInstaller.canInstall(plan)` is a separate, narrower question the platform
  `actual` answers at runtime — not just "what should happen" but "is this instance currently
  allowed to" (Android's install-unknown-apps consent, most notably) — and gates whether a download
  even starts. `check()` resolves this once per found update and folds it into
  `AvailableUpdate.installable`, so the Updates tab and the tray read that instead of `plan`'s own
  `isInstallable` — a plan can call for `SelfReplace`/`RunInstaller` while the platform still
  refuses it, and both surfaces need to agree with `startDownload()`'s own gate about whether
  "Download" does anything.
- **Downloading.** `data/remote/UpdateDownloader` manually follows redirects (the shared HTTP client
  has no redirect plugin at all) against a small host allowlist — exact-match `github.com` and
  `api.github.com` (where the Releases API itself answers), plus a leading-dot-required suffix
  match against `.githubusercontent.com` (the signed-asset redirect target, e.g.
  `release-assets.githubusercontent.com`; the leading dot means `evilgithubusercontent.com` must
  never match) — re-validated at every hop, `https` only, capped at `MAX_REDIRECTS`. The file streams to a `.part`
  path under `<cacheDir>/updates/<version>/`; only once its exact size and SHA-256 both match the
  release's own values does it get an atomic rename to its final name — "the final name exists" is
  the invariant the rest of the pipeline relies on for "this file is verified". A per-request
  timeout override replaces the shared client's ordinary (much shorter) request timeout, since an
  update asset can be 100MB+. Every request goes through `prepareGet(…).execute { … }` rather than
  a plain `client.get()`, and that is load-bearing rather than stylistic: Ktor installs its
  `SaveBody` plugin by default, which reads the *whole* response body into memory before a plain
  `get()` even returns — which froze the real progress bar at 0% for the entire transfer and then
  jumped it straight to done, besides holding 100MB+ in RAM (`skipSavingBody()` is a deprecated
  no-op in Ktor 3.5; the streaming form is the only way out). Progress is throttled to once per
  whole-percent change (`shouldEmitProgress` — gated on the percentage itself, not a fixed byte
  delta, since that's the finest resolution any consumer can show and scales correctly regardless
  of asset size), and cancelling reverts to `Available` rather than `Failed` — a
  user-requested stop is not a failure. There is no resume-from-partial: the redirect target is a
  signed URL that expires in about an hour, so a failed/cancelled download is simply restarted, not
  resumed. `check()` also sweeps `<cacheDir>/updates/` of every version except whichever one the
  current state is still using (protecting an in-progress `.part` as well as a `Ready` file), so an
  update this repository stops referencing doesn't accumulate on disk forever.
- **Installing.** The two platform `UpdateInstaller` actuals do not share an approach:
  - **Desktop** (`platform/update/DesktopUpdateInstaller.kt`) extracts a self-replace ZIP into a
    staging directory through `platform/update/ArchiveExtractor.kt` (zip-slip rejection,
    entry-count and byte-size limits — see below for why this is a seam), health-checks it
    (the executable exists; on macOS, its `Info.plist` version and its own code signature also
    match — `codesign --verify --strict --deep`), moves the extraction
    onto the same volume as the current install (so the swap is a plain rename), and hands off to a
    detached helper script (`platform/update/UpdateScriptWriter.kt`) via `ProcessLauncher`. Only
    once that hand-off has actually happened — the installer returning `Launched`, which makes
    `UpdateRepository` emit its `installLaunched` signal — does `main.kt` exit the app. The app
    deliberately does **not** exit on `UpdateState.Installing`: that state is set the moment an
    install starts, while the extraction is still running, so exiting on it killed the process
    before the script had even been written. Every script follows the same shape regardless of OS:
    wait for this process's
    PID to exit, **retreat** the running install aside (`mv`, never delete first), **place** the new
    one, **verify** it, and **roll back** to the retreated copy on any failure along the way — so a
    crash mid-swap never leaves the install directory empty. Because that retreat is always a plain
    `mv` rather than delete-then-move, `DesktopUpdateInstaller` clears both the `.new` staging
    directory and the `.old` retreat directory immediately before staging a fresh attempt (a stale
    one from a past attempt that failed before the script could run would otherwise make the `mv`
    nest into it instead of overwriting it). The `extracted/` staging directory is cleared first for
    a different reason: an attempt killed *mid-extraction* leaves a partial tree, and both extractors
    merge into an existing destination rather than replacing it, so without the clear a retried
    install would blend two versions into one bundle. `cleanUpStaleSelfReplaceArtifacts` sweeps the
    other two on every startup — safe unconditionally, since reaching that line at all means the current
    `appRoot` is the live install this process is running from, which a script mid-swap never
    leaves behind. A Windows MSI-installed build instead
    launches a script that waits out the PID and then runs `msiexec /i ... /passive /norestart`
    (WiX's fixed `upgradeUuid` makes this a MajorUpgrade, not a fresh install), relaunching whatever
    ends up at the exe path either way — a declined UAC prompt or a failed upgrade relaunches the
    previous, still-working install rather than leaving nothing running. A Linux deb/rpm install is
    never self-replaced at all (`updatePlan` above already routes it to `OpenReleasePage`) — running
    `pkexec`/`sudo` from a GUI with no recovery path if it fails was judged not worth the risk. A
    Linux Snap install is routed the same way, for the more basic reason that its `/snap/keryx/…`
    mount is a read-only squashfs image — there is nothing an in-app update could write to even if
    it wanted to. This also means a Snap install currently gets no benefit from snapd's own
    background auto-refresh, since the app is only distributed via a GitHub Release attachment (not
    published to the Snap Store) for now — `OpenReleasePage` is what still tells the user a new
    version exists in that gap.

    Extraction sits behind a seam because a **signed** macOS bundle cannot be unpacked in process at
    all: its `CodeResources` seals the 43 symbolic links in the bundled JDK's legal-notices directory
    *as links*, and `java.util.zip` exposes no way to tell a stored link from a regular file — it
    writes each one out as a file holding the link target, which fails the `codesign` check above
    every single time. macOS therefore extracts with `ditto -x -k` (`DittoArchiveExtractor`),
    preceded by `ZipExtractor.validate` so the zip-slip, entry-count and uncompressed-size guards
    still apply to an extraction `ditto` performs with no limits of its own — and are the only limits
    in play, since `ditto` itself is bounded only by a 300-second ceiling, past which the child is
    force-destroyed and the install fails (rather than hanging) with the exit status in its reason.
    `validate` establishes the size bound by inflating every entry and discarding it, so a macOS
    install decompresses the archive twice (order of +1-2 s for a ~190MB bundle, on a path the user
    triggered by hand). That is deliberate: a local header's declared size can be absent, so reading
    it from the central directory instead would mean trusting the archive's own metadata for a bound
    whose whole purpose is to survive a crafted one, and overlapping the pass with `ditto` would give
    up the property that a rejected archive leaves nothing on disk at all.
    What `validate` cannot check is a stored link's *target* (same `java.util.zip` blind spot), so
    `DittoArchiveExtractor.verifyExtractedTree` walks the extracted tree afterwards and rejects any
    symlink that, resolved **through the filesystem**, lands outside the destination — textual
    resolution is not enough, since a `..` following another symlink collapses against the link
    rather than its target. `ditto` is not that guard (it declines to *traverse* links, not to
    *create* an escaping one, and exits 0 having done so); what it does contribute is normalizing a
    `..` entry *name* into the destination, which is the only defense against something written
    *outside* the destination, where a walk that starts there cannot look. The `codesign` check is
    deliberately not counted as a defense against an *escape* at all — it inspects the bundle
    directory only, so an entry written beside the bundle is never looked at (see
    [SECURITY.md](../SECURITY.md)). All of this sits on top of the SHA-256 digest the archive
    already had to match.
    Windows and Linux, whose app images carry no signature for a flattened link to invalidate, stay
    on the in-process path (`InProcessArchiveExtractor` → `platform/ZipExtractor.kt`, which
    restores the executable bit only on the entries the caller names). Their `legal/` links do come
    out of an in-app update flattened into files holding a relative path, which is accepted rather
    than overlooked: they are license text, never a path the app executes. The staging move has the same requirement and the same
    trap: `FileSystemExtras`'s cross-volume fallback copies with `NOFOLLOW_LINKS`, since both
    `Files.copy` and `Files.isDirectory` follow links by default and would otherwise flatten them
    right back on any install whose cache and install directory sit on different volumes.
  - **Android** (`platform/update/AndroidUpdateInstaller.kt`) streams the downloaded APK into a
    `PackageInstaller` session and commits it; the OS takes over from there (showing its own install
    confirmation, and — on success — killing this process itself, so nothing here has to). If
    `canRequestPackageInstalls()` is false when Install is clicked (declared but not yet granted, or
    revoked since the download started), the app instead opens the "install unknown apps" system
    settings screen and leaves state at `Ready` rather than failing outright, so a later click
    retries once consent is granted. `REQUEST_INSTALL_PACKAGES` is declared only in the `github`
    distribution flavor's manifest (`androidApp/build.gradle.kts`'s `flavorDimensions` — see
    `build.md`) — not the one submitted to Google Play, both because Play policy restricts that
    permission to apps whose primary purpose is installing other apps and because Play already
    updates the app itself. The gate this drives, `canInstallAndroidApkUpdate`
    (`domain/UpdateInstallPolicy.kt`), reads the *merged manifest's* permission via
    `canRequestPackageInstalls()` rather than branching on which flavor built the running APK, since
    a `play`-flavored APK can still reach this code sideloaded outside Play (a Play Console test
    track, `bundletool`, internal distribution) and must still correctly refuse there. A terminal
    failure after the session is committed (most notably `STATUS_FAILURE_INCOMPATIBLE` — a
    signing-key mismatch between this install and the one being installed over, see `build.md`'s
    Play App Signing note) is logged but not fed back into `UpdateRepository.state`, which already
    moved to `Installing` synchronously on commit and has no channel for a later async result to
    revise it — a known, narrow UX gap this installer accepts for now rather than adding a
    cross-layer callback for a case a successful install never even reaches (the OS kills the
    process first).
- **Presentation.** Surfaced from the moment `check()` finds something, not only once it's ready:
  the desktop tray's single update menu item cycles through "Download update %1$s", "Downloading…
  N%" (rounded to 5% to avoid flooding the Linux SNI D-Bus menu with layout-change signals),
  "Verifying…", "Restart to update to %1$s", and "Update failed" as `state` moves (`%1$s` is the
  target version — see `tray_update_download`/`tray_update_restart` in `strings.xml`) — absent
  entirely for a `NotOffered`/non-installable plan. The Updates tab's own headline row follows the
  same rule — no "Download" button renders there at all for that plan, rather than a disabled one
  (see `ui-guidelines`'s "prefer disabled over hidden" carve-out, which this is: there is nothing
  *temporarily* inactive about a form the in-app installer can never handle) — replaced by the
  `settings_update_manual_only` caption underneath explaining why, plus a link to the release page
  (`LinkRow`, always present once a release is known, whether or not it's installable) as the
  manual alternative. The tab's own "Check for updates" button is a separate element entirely,
  underneath the check-interval control at the bottom of the tab — always present regardless of
  `plan`, not something the headline row's own button "falls back" to. The notification-center bell
  gets one row per meaningful moment —
  "a new version is available" when `check()` finds one, then that same row is dismissed and
  replaced (never left to accumulate alongside it) by "ready to install" once a download finishes —
  reusing the existing `ShowSettingsTab("updates")`/`OpenUrl(releaseUrl)` actions rather than adding
  a dedicated one (see "Notification Center" in [error-design.md](error-design.md)). A download
  failure itself is deliberately **not** posted to the bell — the tray item and the Updates tab
  (with its own "Retry" button) already surface it, and a `postNotification` call for every
  transient network hiccup would be noisier than useful.

**Integrity verification** relies entirely on the GitHub Releases API's own `assets[].digest`
(`"sha256:…"`) matching the downloaded file's own SHA-256 — no `release.yml` change was needed for
this, since the API already returns it. This detects transport corruption and a tampered download,
but **not** publisher compromise: if the GitHub account/token that produces a release were itself
compromised, the attacker-substituted asset and its digest would still match each other. See
[SECURITY.md](../SECURITY.md) for this limitation and what a stronger guarantee (e.g. a
minisign/cosign detached signature) would require.

**Conditional requests.** `ReleaseFeedSource` caches each endpoint's `ETag` in memory (one slot for
`releases/latest`, one for the `releases` list — an `UpdateChecker` instance only ever calls the one
its `currentVersion` picks, per its own KDoc) and sends it back as `If-None-Match` on the next call;
a 304 replays the cached parsed result rather than a fresh, empty-bodied response being treated as
"nothing found". This is intentionally **not** persisted to `local_settings.json` the way
`FeedFetcher`'s validators are saved in the `feeds` table (see "Feed Update Efficiency" below) —
`ReleaseFeedSource` already lives for the app's whole process lifetime as part of the
`UpdateChecker` Koin `single`, so an in-memory cache already covers what actually matters (skipping
a re-fetch/re-parse on every periodic background re-check); persisting release payloads across
restarts as well would add real complexity for the comparatively rare case of a restart landing
between two checks.

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
4. FTS initial creation + unindexed row incremental insertion is done by `FtsManager.ensureIndexed()` on desktop, blocked on with `runBlocking` before `application {}` (acceptable there, since it only delays showing the first window, and `main.kt` runs exactly once per process). `KeryxApplication.onCreate` instead launches `FtsManager.ensureIndexedIfTableAbsent()` fire-and-forget on the shared app-scope `CoroutineScope` — blocking `Application.onCreate` would delay every Android cold start instead of just the first window, and a search performed in the brief window before it completes just returns fewer/no hits rather than failing. It calls the cheaper `ensureIndexedIfTableAbsent()`, not `ensureIndexed()`, because `Application.onCreate` also runs on every `WorkManager` wakeup that starts the process to run `FeedRefreshWorker` (up to ~96 times/day at the platform's 15-minute minimum interval, see "Android Implementation" below) — `ensureIndexed()`'s `indexMissing()` call is an `O(articles)` scan, which `ensureIndexedIfTableAbsent()` skips entirely (a single `sqlite_master` lookup) once the table has already been created and backfilled once. New articles keep getting indexed as normal through the hot-path `indexMissing()` calls in `refreshFeedsAndNotify`/sync and the daily rebuild heal below.

## Daily FTS Rebuild Heal (`maybeRebuildFtsIndex`)

Hot paths (feed refresh, sync merge) use `FtsManager.indexMissing()` to incrementally index only new articles (no full rebuild). Therefore, to resolve the staleness of existing-article indexes when body text is updated, the full rebuild is demoted to **daily idle** execution. `runStartupTasks`, each iteration of `backgroundUpdateLoop`, `runAndroidStartupTasks`, and each run of `FeedRefreshWorker` all call `maybeRebuildFtsIndex`, and `rebuildIndex()` is executed only when the `local_settings.lastFtsRebuiltAt` 24h gate and `ActivityCenter` idle state (no sync / update running) are both satisfied, then `lastFtsRebuiltAt` is recorded. `'rebuild'` is atomic + `busy_timeout` wait, so running searches also do not regress to zero results. See [sync-architecture.md](sync-architecture.md) "FTS5 handling" for details.

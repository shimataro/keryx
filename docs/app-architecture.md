# Keryx App Architecture

[日本語](app-architecture.ja.md)

## Design Philosophy

- Layered architecture (UI → ViewModel → Repository → DataSource)
- Dependency injection with Koin, state management with androidx.lifecycle ViewModel
- Type-safe local DB management with SQLDelight
- Sync processing is confined to the Repository layer; the UI layer is unaware of sync
- Shared platform abstractions are declared in `commonMain` and implemented in
  `jvmCommonMain` when possible, or in target-specific source sets
  (`desktopMain` / `androidMain`) otherwise.

## Directory Structure

```text
composeApp/src/
  commonMain/kotlin/works/merc/keryx/app/
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect)
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels, UpdateDownloader, ReleaseFeedSource (in-app update — see "In-App Update" below)
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, OpmlOpenHandler (importOpmlAndNotify, shared by desktop's and Android's ".opml file association"), CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport (interface + CustomUri), OAuthCallbackParams, StartupMaintenanceTasks (refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex), UpdateChecker/UpdateRepository/UpdateAsset/UpdateInstallPolicy/UpdateInstaller(expect-like interface)/AvailableUpdate/UpdateState (in-app update — see "In-App Update" below)
    di/           AppModule (+ expect platformModule)
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile, InstallLocation, FileSystemExtras, ZipExtractor (all expect)
    ui/           theme/, navigation/, setup/, home/ (3-pane + search + notification center), article/, settings/, i18n/
    LaunchArg.kt  Classifies a raw launch argument (`keryx://` URI vs `.opml` path) — platform-independent, package root
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq (7 tables)
  commonMain/composeResources/  values/strings.xml, drawable/ (icons are Android Vector Drawable XML,
    not SVG — Compose Multiplatform's SVG decoder is desktop/iOS-only and crashes on Android at
    runtime; VectorDrawable XML is the one image format `painterResource` renders on every target)
  jvmCommonMain/kotlin/…/  actuals shared by desktop and Android, needing no platform API either
    target lacks: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage, AppInfo,
    CloudStorageAvailability (the last two just read the shared generated BuildConfig),
    FileSystemExtras, ZipExtractor (in-app update — see "In-App Update" below)
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt (runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile — the desktop-only orchestration, delegating the actual maintenance work to commonMain's StartupMaintenanceTasks) + actual implementations of each expect not covered by jvmCommonMain (DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, PlatformModule, InstallLocation) + LoopbackRedirectTransport, OAuthUriParser, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage implementation (Keyring/File/SecurityCliTokenStorage/LibSecretTokenStorage, sharing outcome-composition logic via SecretStoreTokenStorage), DesktopOs (isMacOs/isWindows/isLinux/isSnap/isTouchPrimary=false/hasNativeAppMenu=true/hasSystemTray=true), DesktopLookAndFeel (Swing L&F: FlatLaf on Linux, plus text-antialiasing hint normalization — missing hint, VALUE_TEXT_ANTIALIAS_DEFAULT, and VALUE_TEXT_ANTIALIAS_OFF are resolved to greyscale antialiasing so Swing surfaces do not look jagged next to the Compose-rendered UI)
    tray/      KeryxTray (platform branch), MacTray, LinuxTray + the StatusNotifierItem/dbusmenu D-Bus objects
    platform/update/  DesktopUpdateInstaller, UpdateScriptWriter (pure self-replace/msiexec script templates), ProcessLauncher/RealProcessLauncher (the detached-launch seam a test fakes), ArchiveExtractor (DittoArchiveExtractor on macOS, where the signed bundle seals its own symlinks; InProcessArchiveExtractor in process elsewhere), CodeSigningVerifier/RealCodeSigningVerifier (the `codesign --verify` seam)
  androidMain/kotlin/…/  actual implementations not covered by jvmCommonMain: DatabaseDriverFactory
    (bundled SQLite, see below), DatabaseFile (`databaseFilePath()` — `Context.getDatabasePath`,
    a different directory than AppDirs.appDataDir()/`Context.filesDir`; see db-schema.md),
    InstallLocation (always ANDROID_SIDELOADED or ANDROID_STORE — see "In-App Update" below),
    AppDirs/BrowserOpener/ClipboardEntries (via AndroidAppContext, a
    static Context holder set once from KeryxApplication.onCreate), PlatformModule (Ktor OkHttp
    engine, CloudSession with Dropbox/OneDrive providers — see Provider/DI below — plus
    AndroidNotificationSink, see "Background Update" below), CloudStorageAvailability (Dropbox/
    OneDrive real, Google Drive fixed `false` — see sync-architecture.md's "Google Drive on
    Android" for why), KeryxTextField/KeryxAlertDialog/
    KeryxIcons/FlatButtons/FlatToggles/SegmentedControl (plain M3 — the last four are
    `expect`/`actual` split the same way, with Material Symbols (icons) or M3's own
    `Button`/`FilledTonalButton`/`TextButton`/`Switch`/`Checkbox`/
    `SingleChoiceSegmentedButtonRow`+`SegmentedButton`/`FilterChip` (components) as the Android side —
    see "Icon set" below), KeryxTabDialog (a modal, near-fullscreen `Dialog`, safe-drawing-padded
    for edge-to-edge, topped by a real M3 `TopAppBar` (back arrow + the screen's own name) above a
    genuine `PrimaryScrollableTabRow`/`Tab` — unlike desktop's own hand-rolled tab bar, see the
    `ui-guidelines` skill), PlatformTheme
    (`platformShapes` = M3's own default `Shapes()`,
    `ProvidePlatformInteraction` a no-op — leaving `LocalIndication`/`LocalRippleConfiguration` at
    their M3 defaults is what gives every `clickable` and M3 component a real ripple; see "UI
    Direction" in external-spec.md), `ListRowChrome.android.kt`'s `listRowSurface` (a 12dp inset
    for both `ListRowKind`s — Android's own `listRowHorizontalMargin()`, M3's
    `NavigationDrawerItemDefaults.ItemPadding` — clipped to `listRowShape(kind)`: `ListItem` rows
    always a large rounded rectangle, `NavItem` rows the same shape too while rendered as
    `PaneLayout.Triple`'s permanent sidebar pane (beside the article list, so the two read as one
    design) or a `NavigationDrawerItem`-style pill while actually rendered as feed-list
    navigation-drawer content instead (`LocalFeedListInDrawer`, provided by `FeedListPane`'s own
    `onSelectionAdvance` nullness) — with selection colors from `rowSelectionColors()`'s
    `secondaryContainer`/`onSecondaryContainer` pair, unchanged by which pane holds keyboard
    focus (pane focus is instead shown as a `secondary` outline via
    `RowSelectionColors.paneFocus`'s `PaneFocusIndication.Ring`, drawn by `HomeCommon.kt`'s
    `listRowOutline` — see that file's own KDoc), TooltipIconButton/ToolbarIconGroup/
    FlatTooltipContent (M3's own icon-button family inside a `TooltipBox` with its own native
    long-press trigger — `IconButtonKind` picks the member: `IconButton` (`Standard`),
    `FilledIconButton` (`Primary`), `OutlinedIconButton` (`Secondary`) and `FilledTonalIconButton`
    recolored to `errorContainer` (`Destructive`) — an unadorned `Row` instead of desktop's
    macOS-toolbar-style capsule, and M3's own `PlainTooltip`, respectively), KeryxRaisedSurface (a distinctly-tinted
    `colorScheme.surfaceContainerHigh` tonal container instead of desktop's hairline-bordered flat
    card), KeryxBadgedIcon (M3's own `BadgedBox`/`Badge` instead of desktop's hand-rolled pill —
    used by `NotificationsBell`), KeryxSettingRow (a real M3 `ListItem`, whose own tap target covers
    the whole row — backs `SettingsComponents.kt`'s `LinkRow`/`ActionLinkRow`/`SwitchRow`),
    KeryxAnchoredPanel (a real M3 `ModalBottomSheet` — backs `NotificationsBell`'s notification
    popover and `TagColorPickerPopup`; necessary, not just idiomatic, since a bare `Popup` there
    would composite behind the article reader's `WebView` the same way a bare Compose overlay does
    on desktop, see "Article Reader" below), KeryxPaneTopBar (a real M3 `TopAppBar` — backs each of
    the 3 panes' own header row, not a shared app-wide bar), DatabaseMerger/DatabaseSnapshot
    (real implementations against a dedicated `io.requery.android.database.sqlite.SQLiteDatabase`
    connection — the Android equivalent of the desktop actual's dedicated JDBC connection; see
    "DatabaseMerger" below), AndroidSqliteSupport.kt (`NoOpDatabaseErrorHandler` — the bundled
    SQLite's default handler deletes a database file it judges corrupt, confirmed by disassembling
    the AAR — plus `setBusyTimeout()`/`userVersion()` shared by both), FilePicker (Storage Access
    Framework `OpenDocument`/`CreateDocument`, routed through AndroidFilePickerHost since this
    `expect object` cannot itself hold an `ActivityResultLauncher`), KeystoreTokenStorage
    (AES-256/GCM key held in the Android Keystore per cloud provider; see sync-architecture.md's
    "Token Storage"), AndroidOAuthCallback.kt (`dispatchOAuthCallbackIfPresent`, called from
    `:androidApp`'s `MainActivity` for the `keryx://` OAuth redirect — the Android counterpart of
    desktop's `main.kt` URI routing), AndroidOpmlOpen.kt (`handleOpmlOpenIfPresent`, called from the
    same `MainActivity` for an `.opml` "open with Keryx" `ACTION_VIEW` intent — the Android
    counterpart of desktop's `.opml` file association; reads the `content://` `Uri` via
    `platform/FilePicker.android.kt`'s `readTextFromUri`, then delegates to commonMain's
    `domain/OpmlOpenHandler.kt`), nativeContextMenu (a real long-press `DropdownMenu`, added in
    the adaptive-layout phase — see its
    KDoc for the tap-vs-long-press disambiguation), BackHandler (delegates to
    `androidx.activity.compose.BackHandler`), PlatformOs (isTouchPrimary = true, hasNativeAppMenu = false, hasSystemTray = false — Android has no menu bar or system tray,
    so `FeedListToolbarRow`/`GeneralTab` grow their own Settings/
    About entry points instead), SelfUpdateCheck (installer-package-based, see "Background Update"),
    NotificationPermission (wraps `rememberLauncherForActivityResult` for `POST_NOTIFICATIONS`) +
    AndroidStartupTasks.kt (`runAndroidStartupTasks`, called from `:androidApp`'s `MainActivity`) +
    background/ (`FeedRefreshWorker` + `BackgroundRefresh.kt`'s `startBackgroundRefresh`,
    `WorkManager`-based — see [background-update.md](background-update.md) for the whole Android
    background/notification story), platform/update/AndroidUpdateInstaller (a `PackageInstaller`
    session + a dynamically-registered `BroadcastReceiver` for its result — see "In-App Update"
    below)
  androidMain/res/  a conventional AGP resource directory (`values/`, `drawable/`, …) generating
    `works.merc.keryx.app.R` — distinct from `commonMain/composeResources/` above (Compose
    Multiplatform's own mechanism, generating typed `Res.drawable.*` accessors instead of resource
    IDs). `:composeApp` cannot depend on `:androidApp`'s own `res/`, so any Android resource a raw
    `@DrawableRes Int` is needed for (e.g. `NotificationCompat.Builder.setSmallIcon`) has to live
    here instead — currently just `drawable/ic_stat_keryx.xml`, the status-bar/notification-dot
    icon `AndroidNotificationSink.kt` posts with (see background-update.md)
  commonTest/ + desktopTest/ + androidDeviceTest/ (instrumented tests for DatabaseMerger/
    DatabaseSnapshot's Android actuals — needs a real device/emulator to load the bundled SQLite
    native library; see testing.md)
```

The package root is `works.merc.keryx.app` (reverse DNS of `keryx.merc.works`).

A separate root-level module, `androidApp` (`com.android.application`, not part of the Kotlin
Multiplatform source-set layout above), holds only `AndroidManifest.xml`, `KeryxApplication`
(process-wide setup: `AndroidAppContext.init`, `startKoin`, `configureImageLoader`, an
`ensureIndexed()` FTS backfill, `startBackgroundRefresh`), and `MainActivity`
(`setContent { App() }`, then `runAndroidStartupTasks`). It exists because AGP
9's `com.android.application` plugin cannot be applied to the same module as the Kotlin Multiplatform
plugin — `composeApp` is instead an Android library via `com.android.kotlin.multiplatform.library`,
and `androidApp` depends on it to produce the installable APK.

## Layer Responsibilities

| Layer | Responsibility | Main Technology |
| --- | --- | --- |
| UI | Screen rendering, input reception | Compose |
| ViewModel | UI state retention, delegates events to Repository | androidx.lifecycle + Koin |
| Repository | Business logic, sync, conflict resolution | Kotlin classes |
| DataSource | DB / HTTP / file IO | SQLDelight / Ktor / java.io equivalent |

## Key Classes

### DatabaseDriverFactory (expect / actual)

`expect class DatabaseDriverFactory { fun create(): SqlDriver }` in `commonMain`. The desktop `actual` creates a `JdbcSqliteDriver`, checks `PRAGMA user_version`, and manually drives `KeryxDatabase.Schema` create / migrate (because SQLDelight's JVM driver does not auto-track schema version).

The Android `actual` creates an `AndroidSqliteDriver`, which drives `Schema.create`/`migrate`
automatically via its own `onCreate`/`onUpgrade` callbacks — no manual `PRAGMA user_version`
handling needed, unlike desktop. It uses `com.github.requery:sqlite-android`'s bundled SQLite
(`RequerySQLiteOpenHelperFactory`, an `androidx.sqlite.db.SupportSQLiteOpenHelper.Factory`) rather
than the device's own SQLite: AOSP's SQLite build omits FTS5 entirely, so `articles_fts`'s
`tokenize='trigram'` cannot work against the system SQLite at any API level. See
`.claude/rules/android-sqlite-bundling.md` for the full rationale and exit criteria.
`busy_timeout`/`foreign_keys` are set from `AndroidSqliteDriver.Callback.onConfigure`; note that
`PRAGMA busy_timeout=N` returns the new value as a result row, so it must go through
`SupportSQLiteDatabase.query`, not `execSQL` (which requery rejects for statements returning rows).

### FtsManager / FtsSearch

Manages `articles_fts` (FTS5 trigram, `content='articles'`) via raw SQL. Not included in the SQLDelight schema. `FtsSearch.search()` splits terms by length: 3+-character terms run through `articles_fts MATCH` (rank-ordered), while the trigram tokenizer can't index anything shorter, so a 2-character term is applied as a `LIKE` filter instead — an extra AND clause on the MATCH-narrowed rows when at least one long term is present, or (when every term is that short) a standalone `LIKE` scan over `articles` ordered by `published_at DESC` and capped at `SEARCH_FALLBACK_RESULT_LIMIT` (no FTS rank exists to sort by). Highlight markup for both paths is produced in Kotlin (`markTerms`), not via FTS5's `highlight()`, so short LIKE-matched terms get marked consistently with FTS-matched ones. See the `articles_fts` section in [db-schema.md](db-schema.md) for the exact term-length thresholds.
**Never DROP the live DB's `articles_fts`** (excluded from upload via `VACUUM INTO` snapshot copy (`DatabaseSnapshot`), dropping it on the copy side, so concurrent searches never hit `no such table`). Hot paths (feed refresh, sync merge) incrementally index new rows via `FtsManager.indexMissing()` — never a full `'rebuild'`, which is O(all indexed text) and would block/zero-out concurrent searches. The whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass (`maybeRebuildFtsIndex` in commonMain's `domain/StartupMaintenanceTasks.kt`, gated on `lastFtsRebuiltAt` + `ActivityCenter` idle, called from desktop's `StartupTasks.kt`), which re-indexes content that incremental indexing left stale. On startup, `FtsManager.ensureIndexed()` creates the table on first run and backfills any missing rows. `busy_timeout` (set in `DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief write lock of an incremental insert or a rebuild.

### DatabaseMerger (expect / actual) — Key to Sync Merge

ATTACH DATABASE merge runs through `platform/DatabaseMerger`, NOT the SQLDelight driver. SQLDelight's JVM `JdbcSqliteDriver` opens a fresh connection per statement for file DBs, so an `ATTACH` on one call is invisible to the merge statements on the next. `DatabaseMerger` does the whole attach → version check → merge → detach on a single dedicated JDBC connection.

Only the SQLite-driver conversation is platform-specific. The decision *policy* lives in
`commonMain`: `domain/MergeFailureClassifier` (a pure function — failure category + error-code name
+ a lazy schema-validation callback → `CloudDataIncompatibleException?`) and `domain/MergeSchema`
(the expected tables/columns per schema version, plain data). The desktop `actual` only walks the
cause chain for an `org.sqlite.SQLiteException`, reduces `resultCode.code and 0xFF` to a
`SqliteFailureCategory`, logs the verdict, and runs `PRAGMA table_info` for `validateSchema` against
`MergeSchema.EXPECTED_SCHEMAS`. The Android `actual` supplies the same category by a different
route — its `android.database.sqlite.SQLiteException` exposes no numeric result code (unlike the
JDBC driver desktop reads), so it switches on the thrown exception's own subclass instead
(`SQLiteConstraintException`/`SQLiteDatabaseCorruptException` → `CORRUPT_OR_CONSTRAINT`, a handful
of other named subclasses → `OTHER`, a plain `SQLiteException` → `STATEMENT_ERROR`, matching the
ambiguity `validateSchema` exists to resolve) — and otherwise does the same attach → version check
→ merge → detach sequence on a dedicated `io.requery.android.database.sqlite.SQLiteDatabase`
connection, opened with `NoOpDatabaseErrorHandler` rather than the library's default (which deletes
a database file it judges corrupt — confirmed by disassembling the bundled AAR; see
`platform/AndroidSqliteSupport.kt`).

### CloudSession / SyncRepository

`CloudSession` provides the current `CloudStorage` (Dropbox / Google Drive / OneDrive on desktop;
Dropbox / OneDrive on Android — see sync-architecture.md's "Google Drive on Android") and handles
automatic access-token refresh. `SyncRepository` implements the download → merge (`DatabaseMerger`)
→ incremental index of new articles (`indexMissing`) → `VACUUM INTO` snapshot generation
(`DatabaseSnapshot`, excludes `articles_fts` on the copy side) → upload (rev check) flow, along
with debouncing (`SyncScheduler`). The live DB's FTS is untouched. `SyncRepository`'s `localDbPath`
defaults to `platform/DatabaseFile.kt`'s `databaseFilePath()`, the single `expect` function that
resolves the live DB's real path per platform (see `db-schema.md`).

### In-App Update

`domain/UpdateRepository` is the same kind of app-lifetime, Koin-`single` orchestrator as
`SyncRepository` above — a `StateFlow<UpdateState>` every UI surface (Updates tab, tray, bell)
reads, so a closed settings dialog doesn't cancel an in-flight download. It composes three seams:
`domain/UpdateChecker` (candidate selection and version-comparison policy only — the GitHub
Releases HTTP request and JSON parsing live in `data/remote/ReleaseFeedSource`, which
`UpdateChecker` builds internally from the same constructor params rather than taking one as a
dependency, so its own constructor — used directly by a large number of tests — didn't have to
change shape for what's an internal layering detail), `data/remote/UpdateDownloader`
(manual redirect-following + host allowlist + digest verification, the same "no shared-client
plugin, roll it by hand" shape `FeedFetcher` already uses for its own redirect handling), and
`domain/UpdateInstaller` (new `expect`-like interface — its own implementations live in
`platform/update/`, not alongside it — bound via `platformModule` exactly like
`OsNotificationSink` — a `single<UpdateInstaller>` per platform, fakeable in tests). Two pure
`domain/` functions decide *what* to do without touching the network or filesystem, so both are
plain `commonTest` targets: `UpdateAsset.kt`'s `selectUpdateAsset` (which release asset, given
`platform/InstallLocation.kt`'s `detectInstallLocation()`) and `UpdateInstallPolicy.kt`'s
`updatePlan` (what to do with it — self-replace, hand off to the OS installer, or fall back to the
release page). `UpdateInstaller.canInstall(plan)` is deliberately *not* pure — it's the one place a
platform `actual` gets to say "not right now" for a reason `updatePlan` itself has no way to know
(Android's runtime install-consent state, most notably); `UpdateInstallPolicy.kt`'s
`canInstallAndroidApkUpdate` still pulls the *decision* itself out as a pure function of one
boolean, so it's covered by `commonTest` despite `androidMain` having no JVM-testable unit-test
source set (see `testing.md`).

A third pure function sits beside those two but deliberately outside `domain/`:
`ui/settings/ReleaseNotesText.kt`'s `plainTextReleaseNotes` (Markdown-to-plain-text for the Updates
tab's read-only summary) is UI-layer presentation formatting, not update policy — the same
reasoning that keeps `ui/home/HomeCommon.kt`'s `formatTimestamp` and `ui/i18n/ErrorMessages.kt` out
of `domain/` too, and its sole caller (`ui/settings/UpdatesTab.kt`).

The desktop and Android `UpdateInstaller` actuals share no code at all — desktop
(`platform/update/DesktopUpdateInstaller.kt`) extracts a ZIP via
`platform/update/ArchiveExtractor.kt` (`ditto` on macOS, whose signed bundle seals its own symlinks;
`platform/ZipExtractor.kt` — `jvmCommonMain`, shared with Android exactly like `FileIO`/`Gzip` —
everywhere else, see [background-update.md](background-update.md)), stages it next to the current
install, and hands off to a detached helper script (`platform/update/UpdateScriptWriter.kt`, pure
string templates — tested by asserting their text directly, never by running one) via
`platform/update/DetachedProcess.kt`'s `ProcessLauncher` seam (mirroring
`data/cloud/SecurityCliTokenStorage.kt`'s `CommandRunner`/`RealCommandRunner` split). `main.kt`
exits the whole app only on `UpdateRepository.installLaunched`, the signal emitted once that
hand-off has actually returned `Launched` — never on `UpdateState.Installing`, which is set while
the installer is still extracting; Android (`platform/update/AndroidUpdateInstaller.kt`) streams the
downloaded APK into a `PackageInstaller` session instead. See "In-App Update" in
`background-update.md` for the full behavioral flow (state machine, per-platform install steps,
presentation) and `SECURITY.md` for the integrity-verification trust model.

### Provider / DI (Koin)

`appModule` (`commonMain`) registers repositories, services, and ViewModels. `platformModule` (`desktop`) registers HttpClient, TokenStorage, CloudSession, and CloudConnectFlow. ViewModels are registered as app-scope `single` for a single-window desktop app and obtained via `koinInject()`.

### Article Reader (native WebView)

`ui/home/ArticleDetailPane.kt`'s reader renders article HTML through a native WebView
(`io.github.kdroidfilter.webview`, a heavyweight AWT `SwingPanel` wrapping a real OS browser view —
Edge WebView2 on Windows, WebKit on macOS, WebKitGTK on Linux), not a Compose-drawn texture. It is
composed unconditionally for the pane's lifetime — never behind an `if` — because Compose Desktop's
`SwingInteropContainer` revalidates and repaints the *whole window* whenever a heavyweight
component is added, removed, or moved, not just this pane (see [known-issues.md](known-issues.md)
for the investigation). Consequently, states that have no article to render — "no article
selected" and "no content" — are rendered as HTML *inside* the same WebView rather than as Compose
`Text`, via `ui/article/ArticleWebViewHtml.kt`'s `articlePlaceholderHtml`/`articleNoContentHtml`
(sharing one `<style>` block with the real-article `wrapArticleHtml` builder, so every state paints
the same theme colors). The toolbar above the reader is likewise always present, with actions
disabled rather than hidden when nothing is selected, keeping its Compose structure — and
therefore the reader's measured bounds — identical across states.

`ArticleWebView` also sets `webSettings.desktopWebSettings.dataDirectory` explicitly, to
`AppDirs.cacheDir()` plus a `webview` subdirectory, applied identically on all three desktop
platforms (no OS branch). Left at its `null` default, WebView2 tries to create its data folder next
to the host executable, which fails with Access Denied whenever that location isn't user-writable —
see [known-issues.md](known-issues.md) for the investigation (an uncaught exception from the failed
creation also left the library's creation-retry timer running forever, which was the cause of an
app-wide freeze on click).

**Android's reader (`ui/home/ArticleDetailPane.kt`, shared `commonMain` composable) also grows
swipe-to-navigate at a narrow layout** (`ui/home/ArticleSwipeNav.kt`) — a horizontal drag on the
reader moves to the next/previous article, gated on `isTouchPrimary && onNavigateUp != null &&
article != null` (the same nullable-callback narrow-layout signal `HomePaneLayout.kt` already uses
elsewhere, see "Home's adaptive pane layout" below), so it is inert at `PaneLayout.Triple` and on
desktop. Android's `WebView` (embedded via `AndroidView`) is an ordinary in-tree view, but it still
consumes touch input on its own terms, so the gesture is arbitrated the same way
`platform/NativeMenu.android.kt`'s long-press and `ui/home/FeedListDragGestures.kt`'s reorder drag
already are: a `pointerInput` loop watches `PointerEventPass.Initial` (which reaches this ancestor
before the WebView's own interop handling) and leaves every event unconsumed until the drag is
confirmed horizontal (past touch slop, and more horizontal than vertical travel), so the WebView's
own scroll and link taps are never interrupted for an ordinary vertical gesture; only once confirmed
does it start consuming, which cancels the WebView's own gesture. A `HorizontalPager` was considered
and rejected: it would need one `WebView` mounted per page, and preloading the adjacent pages' HTML
would load their bodies (and, per the "selection marks read instantly" rule in `error-design.md`,
mark them read) before the user ever swipes to them — the opposite of what `HomeViewModel.selectArticle`
is designed to do. Instead, the gesture only drives `HomeViewModel.selectNext`/`selectPrevious` (the
same calls desktop's J/K keyboard shortcut uses) once the drag commits, and the reader's own content
slides via a plain `Modifier.offset` on the existing single WebView instance rather than swapping in
a second one.

### Desktop Tray (platform branch)

`tray/KeryxTray.kt` picks one of four implementations:

| Platform | Implementation | Why |
| --- | --- | --- |
| macOS | `MacTray` (raw AWT `TrayIcon`) | Compose's `Tray()` wires the menu through `TrayIcon.setPopupMenu()`, which opens it on *any* click on macOS. |
| Linux (SNI host present) | `LinuxTray` (D-Bus StatusNotifierItem) | AWT cannot draw a transparent tray icon on X11 — see below. |
| Windows | `WindowsTray` (raw AWT `TrayIcon` + `JPopupMenu`) | `Tray()`'s menu is a `java.awt.PopupMenu`, which the JDK's Windows peer paints with overlapping labels above 100% display scaling — the same defect that moved the context menus off AWT (see "Native context menus" below). |
| Linux (no SNI host) | Compose `Tray()` | Works as-is. |

`MacTray` and `WindowsTray` both bypass `Tray()` by driving a raw `TrayIcon`, but for unrelated
reasons and with two deliberate differences. `MacTray`'s invoker frame is permanently shown and
non-focusable, because an AWT `PopupMenu` runs its own native modal loop, whereas `WindowsTray`'s is
focusable and hidden between uses, because a `JPopupMenu` only closes on an outside click if its
owning window can hold — and lose — focus. And `MacTray` positions the menu from the event's own
`xOnScreen`/`yOnScreen`, while `WindowsTray` goes through `trayMenuAnchor` and `MouseInfo` instead:
a `TrayIcon` MouseEvent carries *device* pixels on Windows but *points* on macOS, and
`Window.setLocation` wants user space on both. Both consume `newArticleNotifications` themselves,
since only Compose's `Tray()` turns a queued `TrayState` notification into a real OS one.

**Why Linux needs SNI.** `sun.awt.X11.XTrayIconPeer.IconCanvas.paint()` fills the whole 24x24 canvas
with the component background *before* drawing the icon, and `sun.awt.X11.XSystemTrayPeer` never reads
the tray manager's `_NET_SYSTEM_TRAY_VISUAL`, so the XEmbed window has no alpha channel at all. An AWT
tray icon therefore always appears inside an opaque (white) box, whatever the PNG contains. SNI hands
the panel raw ARGB pixels instead.

Two objects are exported on a dedicated session-bus connection (`SniConnection`, `withShared(false)`,
well-known name `org.kde.StatusNotifierItem-<pid>-1`):

- `/StatusNotifierItem` — `SniStatusNotifierItem`, serving `org.kde.StatusNotifierItem`. `IconPixmap`
  carries the badged glyph as big-endian ARGB32 (`TrayPixmap.kt`) at several sizes; `ItemIsMenu = false`
  so a primary click reaches `Activate` instead of opening the menu.
- `/StatusNotifierItem/menu` — `SniDBusMenu`, serving `com.canonical.dbusmenu` (Show/Hide + Quit).
  A label/enabled change bumps a revision and emits `ItemsPropertiesUpdated` naming just the item(s)
  that changed (`changedItemProperties` in `TrayMenuModel.kt`) — **not** `LayoutUpdated`, since the
  menu's shape never changes and some clients (GNOME Shell's AppIndicator extension) never re-request
  `label`/`enabled` via `GetLayout` on their own, so a `LayoutUpdated`-only host update would leave an
  already-open menu stuck on stale labels forever. `AboutToShow` still compares the desired labels
  against what `GetLayout` last served, so a dropped signal still heals. GNOME parks the signal until
  its menu opens and then repaints without blocking the first frame, so a changed label flashes its
  previous value there for an instant — a known, unfixable-from-here artifact (`known-issues.md`),
  not a reason to reach for `LayoutUpdated` again.

The icon asset follows the same split as the branch: the outlined glyph (`tray_icon_outlined.png`) on the two
paths that composite it with real alpha at 22px or more, and the full-colour one (`tray_icon.png`) on the Windows
notification area and the Linux AWT fallback, where the icon is small, never tinted, or drawn over an opaque box.

Neither exported object holds the connection — signal emission is injected as a callback — so both are
unit-testable without a bus. Desktop notifications go through `org.freedesktop.Notifications` on the same
connection (`LinuxNotifier`), replacing the AWT balloon; note its `image-data` hint wants **RGBA**, not
the SNI pixmaps' big-endian ARGB32.

Detection happens in `main.kt` before `application {}` (bounded by a timeout so an unresponsive session
bus cannot stop startup) and yields `null` when there is no session bus or no `StatusNotifierWatcher`,
which is what selects the AWT fallback. If the watcher only appears *after* launch, Keryx stays on the
AWT path until restart; a watcher that restarts later is handled via `NameOwnerChanged`.

All of this lives in `desktopMain` rather than behind an `expect`/`actual` pair: it is reachable only
from `main.kt`, no ViewModel or Repository touches it, and a Linux panel protocol has no mobile
counterpart.

### Native context menus (platform branch)

`platform/NativeMenu.android.kt`'s `nativeContextMenu` backs the same call sites (article rows,
feed / folder / tag rows) with a long-press-triggered Material 3 `DropdownMenu` instead: a
self-contained `awaitEachGesture` loop that never consumes the initial *down* — only once the
press survives `viewConfiguration.longPressTimeoutMillis` with no up and no consumption elsewhere
(e.g. a `LazyColumn` scroll claiming the gesture) does it treat this as a long press and start
consuming the rest of the gesture, so `ui/home/ListRowChrome.kt`'s `listRowClickable` (chained
right before it, and therefore the *more outer* node — Compose's pointer-input `Main` pass resumes
nested nodes before their ancestors for the same event) never also fires `onClick` for the same
press. `NativeSubMenu` drills into its own items in place (a leading "back" row swaps the top level
for the submenu's own items) rather than opening a nested popup. Two behaviours are Android-specific,
unlike the desktop backends below: a confirmed long-press never invokes `onOpen` (desktop's
right-click-selects-the-row hook — an Android long-press only opens the menu, never selects the
row), and the same `awaitEachGesture` loop cancels the long-press once the pointer moves past
`viewConfiguration.touchSlop`, so a `LazyColumn` scroll that starts as a slow drag on a row cannot
be mistaken for a long-press-and-hold.

`platform/NativeMenu.desktop.kt`'s `defaultPopupHandle` backs the same call sites on desktop with
one of two implementations, triggered by a right-click instead of a long-press:

| Platform | Implementation | Why |
| --- | --- | --- |
| macOS | `AwtPopupHandle` (`java.awt.PopupMenu`) | AWT maps it onto a genuine `NSMenu`, and AppKit is point-based, so the Dp-space coordinates the modifier computes need no device-pixel conversion. |
| Windows / Linux | `SwingPopupHandle` (`javax.swing.JPopupMenu`) | On Linux, AWT's `PopupMenu` is a heavyweight XAWT widget that ignores the Swing Look & Feel, keeping a Motif-era appearance. On Windows, the JDK's AWT menu peer never converts between Java user space and device pixels: the menu opens at `windowOrigin + clickOffset / scale`, and its rows measure `1 / scale` as tall as the glyphs drawn into them, so the labels overlap. Both are detailed in `known-issues.md`. |

`macOs` is a parameter of `defaultPopupHandle` (defaulting to the process constant) only so
`NativeMenuTest` can pin the mapping on any CI host. Two behaviours follow the chosen backend
rather than the app: separators are a `JPopupMenu.Separator` on the Swing path and a `"-"`-labelled
`MenuItem` on the AWT one, and modifier-less shortcuts (F2 / Delete) render in the accelerator
column only on the Swing path — `java.awt.MenuShortcut` always bakes in the platform's primary
modifier and structurally cannot express them. `forceHeavyweight`
(`isLightWeightPopupEnabled = false`) is what keeps a Swing popup from being drawn behind the
article reader's WebView; it is redundant under FlatLaf on Linux but load-bearing on Windows, which
takes `installLookAndFeel`'s system-L&F branch instead.

### Native file dialogs (platform branch)

`platform/FilePicker.desktop.kt`'s `defaultFilePickerBackend` picks one of two implementations, the
same Linux-Swing-vs-AWT split as `NativeMenu.desktop.kt`'s `defaultPopupHandle` — except that the
file dialog keeps Windows on the AWT side, because `java.awt.FileDialog` there is a real
`GetOpenFileName` panel with none of the menu peer's scaling problems:

| Platform | Implementation | Why |
| --- | --- | --- |
| macOS / Windows | `AwtFilePickerBackend` (`java.awt.FileDialog`) | AWT maps it onto the real native panel (`NSSavePanel` / `GetOpenFileName`), including native overwrite prompting. |
| Linux | `SwingFilePickerBackend` (`javax.swing.JFileChooser`) | `sun.awt.X11.XToolkit.createFileDialog()` selects `GtkFileDialogPeer`, whose native GTK callbacks dereference a NULL `JNU_GetEnv` result once the article reader's WebView makes WebKitGTK a second GTK consumer in the process — a JVM-crashing SIGSEGV (see `known-issues.md`). `JFileChooser` is pure Swing and never reaches that code, and it picks up FlatLaf like the app's other Linux Swing surfaces. |

The dialog's owner window is resolved *inside* the desktop `actual`
(`KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow`, falling back to any showing
`Frame`), not threaded in from the caller — `LocalNativeWindow` only ever resolves to the main
window, which would be the wrong owner for a dialog opened from the modeless Settings window. Since
`JFileChooser` has no native overwrite-confirmation of its own (unlike the AWT backend, which gets it
free from the OS), `SwingFilePickerBackend` restores it explicitly — see `known-issues.md` for why
that specific behavior was restored rather than left as a plain crash fix.

**Future work**: an `org.freedesktop.portal.FileChooser` (XDG Desktop Portal) backend could be
dropped into the same `FilePickerBackend` seam, spoken over the dbus-java connection this app
already uses for the SNI tray and AppMenu, giving a genuinely native KDE/GNOME dialog (and
sandbox-correct behavior) — the pre-crash Linux `FileDialog` already had native overwrite
confirmation via GTK, so a portal-backed dialog likely would too, needing none of
`SwingFilePickerBackend`'s explicit `resolveSavePath`/`JOptionPane` fallback. It would fall back to
`JFileChooser` when no portal backend is present, detected the same way `KeryxTray` picks SNI vs.
AWT: probing for `org.freedesktop.portal.Desktop` on the session bus at startup.

### Icon set

`ui/common/KeryxIcons.kt` is the sole indirection point for every UI call site (semantic name →
bundled Android Vector Drawable XML under `composeResources/drawable/`), and it is `expect`/`actual`
per platform since the two targets intentionally bundle different icon sets: the desktop `actual`
uses Tabler Icons (MIT) — chosen for a thin-stroke, rounded-terminal look closer to macOS's own
iconography than Material Design's (see the `ui-guidelines` skill for the full rationale) — while the
Android `actual` uses Material Symbols Outlined (Apache-2.0), matching Android's own native visual
language. `KeryxIcon(...)` (the `Icon` wrapper composable) stays a single `commonMain` definition;
only the `KeryxIcons` object's icon selection differs per platform. If iOS/iPadOS/macOS is ever
rewritten as native SwiftUI (per `external-spec.md` §2's plan), that becomes a separate codebase
unrelated to Kotlin's `KeryxIcons`, so it can use SF Symbols via `Image(systemName:)` directly with no
additional Kotlin-side switching mechanism needed.

## Domain Model Policy

SQLDelight generated classes (`Feeds` / `Articles` / …) are used as-is in all layers. Column names become properties in snake_case (e.g. `feed.site_url`). Booleans and timestamps are kept as `Long` (0/1, Unix millis) and converted with kotlinx-datetime at display time. No separate domain model classes are defined.

The one exception is `domain/ArticleRepository.kt`'s **`ArticleListRow`**: the eight columns the
article list renders (`id` / `feed_id` / `title` / `url` / `published_at` / `created_at` / `is_read` /
`is_starred`). It exists for cost, not for modelling — the full `Articles` row also carries
`content`, `summary` and `search_text`, i.e. the article body twice over, so selecting `*` for the
list made one emission proportional to the whole corpus's text, and the list query re-runs on every
write to `articles` or `feeds`. The list queries in `articles.sq` project exactly those eight columns
and map them with `::ArticleListRow`; the body is loaded per selected article via
`getArticleById`, and `_selectedArticle` therefore stays a full `Articles`. That load runs off the
UI thread and applies latest-wins — it pulls `content` on a connection the JVM driver opens per
statement, so under a merge's or refresh's write lock it could otherwise burn the whole
`busy_timeout` on the UI thread, ~30 times a second under a held arrow key. `HomeViewModel` keeps a
synchronous `selectionCursorId` alongside it, so keyboard navigation steps from where the user
actually is rather than from the last hydration to land. Because a narrowed
`SELECT` makes SQLDelight generate a distinct type per query, the shared hand-written row is what
lets `watchArticles`' five branches keep one return type — and its parameter order is positionally
bound to the SELECT column order (guarded by
`ArticleRepositoryTest.articleListRowMapsEveryProjectedColumnToItsOwnField`).

## Navigation

A simple stack navigator in `ui/navigation/Navigator.kt` switches between Setup / Home / Settings. Article view is a pane inside Home (not a root route).

### Home's adaptive pane layout

`ui/home/HomePaneLayout.kt` resolves how many of the three home panes (feed list / article list /
article detail) `HomeScreen` renders side by side, purely as a function of the available width:
`PaneLayout.Triple` (all three — desktop always resolves here, since `WINDOW_MIN_WIDTH` is
guaranteed `>= TRIPLE_PANE_MIN_WIDTH`, see that constant's KDoc in `core/Constants.kt`),
`PaneLayout.Dual` (article list + article detail), or `PaneLayout.Single` (one pane, phone width).
Both thresholds are summed from the pane-width constants rather than being independent
breakpoints, and `TRIPLE_PANE_MIN_WIDTH` specifically from the two resizable panes' *default*
widths, not their minimums: a width that fits three panes only by putting all three on their floors
at once — an Android tablet held in portrait — is treated as narrow instead. That matters most on a
touch-primary platform, where `ResizableDivider` has no drag affordance and the user cannot widen a
pane back out (see that constant's KDoc).
`feedListIsDrawer(layout)` (`layout != Triple`) is the single source of truth every layout decision
below branches on: at every layout but `Triple`, the feed list is a Gmail-style modal navigation
drawer (`ModalNavigationDrawer`) rather than an on-screen pane, opened by a hamburger button on
`ArticleListPane`'s own header (`onOpenDrawer`) and closed by selecting anything in it
(`onSelectionAdvance`). `HomePane.FeedList` itself still exists as an enum entry — it's what
`Triple` renders — but `visiblePanes` never returns it at a narrow layout, and `NarrowPaneRow`
enforces that invariant with its own `require()`.

The navigation stack itself is always three deep (`HomePane.FeedList` → `ArticleList` →
`ArticleDetail`), but at a narrow layout depth 1 (the feed list) is unreachable — the drawer isn't
part of the stack `focusedPane` ever points into; opening it doesn't advance `focusedPane`, and
`initialPaneFor`/`paneForFeedDetail` (below) never resolve to it there either. Because of this,
`focusedPane` alone can't answer "is the feed list what the user is keyboard-navigating right
now" at a narrow layout (a physical keyboard can be attached to an Android tablet) — every call
site that needs that answer (arrow-key routing, the F2/Delete feed-list shortcuts, and each pane's
own `focused` parameter that drives its selected row's keyboard-focus ring/dimming) instead reads
`HomePaneLayout.kt`'s **`keyboardPaneFor(focusedPane, feedDrawerOpen)`** — a single pure function
that resolves to `HomePane.FeedList` whenever the drawer is open (`feedDrawerOpen =
feedListIsDrawer(paneLayout) && drawerState.isOpen`, always the topmost thing on screen while
open, regardless of `focusedPane`) and to `focusedPane` itself otherwise. Reading `focusedPane` and
`feedDrawerOpen` separately at each call site used to require re-deriving that same drawer
precedence by hand every time, and disagreeing about it once was a real bug: `HomeScreen`'s
`Triple`/drawer `FeedListPane` and its `ArticleListPane` each computed their own `focused` flag,
which could both resolve `true` at once (`PaneLayout.Dual` with the drawer open) and paint a
keyboard-focus ring on two panes simultaneously — `keyboardPaneFor` makes that structurally
impossible, since every consumer now reads the one value it resolves to at most once.

**`focusedPane` itself only ever *advances* at `PaneLayout.Single`.** `HomePane.ordinal + 1`
doubles as the navigation stack's current depth, so `HomeScreen` needs no separate depth state —
`platform/BackHandler` (a real back-gesture/button interception on Android, a no-op on desktop)
pops it by one, gated on `homeBackAction(layout, depth, searchBarOpen)` (below), and
selecting an article advances it forward the same way — but only where `visiblePanes` actually
changes with depth: `ArticleListPane`'s `onSelectionAdvance` (and the drawer `FeedListPane`'s own,
which additionally always moves `focusedPane` to `HomePane.ArticleList` on a row selection, not
just closing the drawer) is a no-op at both `PaneLayout.Triple` and `PaneLayout.Dual`, where every
pane `visiblePanes` returns is already on screen and advancing `focusedPane` further would only
leave the article list pane reporting `focused = false` on the very next frame with nothing to
show for it — see `ArticleListPane`'s own KDoc on that parameter. **Real Compose keyboard focus,
independent of this `focusedPane` state, lives in exactly two places for the whole screen: the
root `Box` `homeKeyboardShortcuts` is attached to, and whichever text field (the sidebar search
field, a narrow layout's own search field, or a feed-list row's inline name editor) is currently
being typed into.** A list row itself never takes real focus (`ListRowChrome.kt`'s
`listRowClickable` disables it via `Modifier.focusProperties { canFocus = false }`) — row
reachability is entirely this app's own arrow-key/J-K model, not Tab order or click-to-focus, and
every pane's own `onActivated` routes through a small `HomeScreen` helper that returns real focus
to the root `Box` on top of whatever it does to `focusedPane`, which is what lets a tap on any row
or button also move focus off whichever text field it had been on.

`homeBackAction(layout, depth, searchBarOpen)`, which wraps the pane-only predicate
`canNavigateBack(layout, depth)` (`false` whenever stepping back wouldn't actually change what's on
screen: always at `Triple`; always at `Dual`, since `visiblePanes(Dual, *)` returns the same two
panes at every depth — a back press there used to be silently swallowed before `canNavigateBack`
existed) with the other half of "what does going back actually do": closing the expanded search bar
instead of popping a pane, when it's open (see "Search is orthogonal to `ArticleFilter`" below) —
this takes priority over `canNavigateBack` wherever the article list is actually visible (`Single`
depth 2, `Dual` at every depth), since closing the bar always changes what's on screen there.
**`canNavigateBack`/`homeBackAction` resolving to `false`/`None` for the article list's own depth
(with the bar closed) is deliberate, not an oversight** — `HomeScreen`'s `BackHandler` disables
itself for `None`, so a back press there falls through to the platform's own default (exiting the
app on Android) rather than this codebase swallowing it with nowhere to go.

Unlike before the drawer existed, `PaneLayout.Dual` is *not* a sliding window over the stack: the
feed list being a drawer rather than a pane means `visiblePanes(Dual, depth)` returns the same
`[ArticleList, ArticleDetail]` regardless of depth — the article detail pane is a permanent neighbor
of the article list, the same shape as Gmail's own tablet reading pane, with no back control of its
own (`ArticleDetailPane`'s `onNavigateUp` is `null` there; see "Search is orthogonal to
`ArticleFilter`" below for why `swipeNavigation` is a separate, still-non-null signal).

At a narrow layout the two remaining panes are hosted by `ui/home/NarrowPaneRow.kt`, which is what
keeps each one's scroll position across the stack's comings and goings. `Dual` never unmounts
either pane at all (`visiblePanes` never changes there), but `NarrowPaneRow` still emits each pane
from its own fixed source position rather than a `visible.forEach` loop — a loop gives every
iteration the same compose group key, so a future pane whose position in the list *can* change
would be torn down and rebuilt even though it never left the screen (a pane added here must
likewise get its own `if`, never a loop iteration). `Single` genuinely unmounts every pane but one
as the stack's depth changes between `ArticleList` and `ArticleDetail`, and there a
`rememberSaveableStateHolder` saves each pane's `rememberSaveable` state — in practice its
`LazyListState`, which `rememberLazyListState` stores that way — and restores it as the list
state's *initial* index/offset, so nothing scrolls and no new call lands in the
`scrollToIndexIfNeeded` code path `known-issues.md` implicates in an unfixed upstream Compose
crash. `ArticleListPane`'s `lastFilter` is a `rememberSaveable` holding `ArticleFilter.encode()`'s
string for the same reason: the filter can change while the pane is unmounted (a notification's
`ShowFeedDetail`, or deleting the feed being viewed), and a plain `remember` would re-initialize to
the new filter on remount, leaving the restored position pointing into the previous filter's list
with no reset to the top.

This is why the article reader's WebView being unconditionally composed (see "Article Reader"
below) is safe on desktop specifically: desktop can only ever resolve `Triple`, where all three
panes — including the one hosting the WebView — stay mounted for the app's whole lifetime. `Dual`
now never unmounts it either (the article detail pane is always one of the two shown), and only
`Single`'s depth 2↔3 transition unmounts it, which is fine on Android (no heavyweight AWT interop
concern there).

At a narrow layout, `initialPaneFor(layout, saved)` always resolves to `ArticleList` regardless of
`saved` — including a saved `HomePane.FeedList` left over from a version before the drawer existed,
which can no longer be honored (there's no pane to restore it as any more) — while `Triple` returns
`saved` unchanged (the article the user was last reading, same as ever): restoring straight into a
detail pane with no list around it and no context for how the user got there would be disorienting
on a phone-shaped session. This clamp is applied exactly once, on the first frame with a real
(post-layout) width, and never again — a later resize or rotation must not yank the user off
whatever they're reading. The same first-frame effect also opens the feed drawer automatically when
`shouldAutoOpenFeedDrawer` says so — a narrow layout with no feeds and no cloud account configured,
where the "+" button that would otherwise fix that lives inside a drawer closed by default (see
`HomeViewModel.hasAnyFeed()`, a one-shot DB query distinct from the already-collected `feeds`
`StateFlow`, whose `Eagerly`-shared initial value can't tell "empty" apart from "not loaded yet").

**Search is orthogonal to `ArticleFilter`, not a variant of it.** `core/ArticleFilter.kt` only has
`All`/`Starred`/`Feed`/`Tag`/`Folder` — there is no `Search` case. The query
(`HomeViewModel.searchQuery`) narrows whichever filter is already selected instead of displacing it:
`HomeViewModel.searchActive` (`_searchBarVisible && searchQuery.value.isNotEmpty()`) is the single
derived flag that says whether the article list is currently showing search results
(`HomeViewModel.searchResults`, itself scoped to the current filter — see `FtsSearch.articleScopeSql`
below) or the filter's own list (`HomeViewModel.articles`); `ArticleListPane` reads it to pick which
one to feed into the one `ArticleListPaneContent` call that renders either. Because the filter is
never displaced, there is nothing to snapshot and nothing to restore when search ends — the
`SearchScopeEntry`/`enterSearchScope`/`exitSearchScope` machinery an earlier design needed for
exactly that no longer exists.

**`_searchBarVisible` is the one piece of real state search still needs**, and it exists only for
narrow layouts: at `PaneLayout.Triple`, `FeedListPane`'s own query field is permanent, so
`HomeScreen`'s own `LaunchedEffect(layout)` keeps it `true` there the whole time; at a narrow
layout it starts `false` and is toggled by `ArticleListTopBar`'s search icon
(`onSearchClick` → `setSearchBarVisible(true)`) and the expanded bar's own back arrow
(`onExitSearch` → `homeBackAction`'s `ExitSearch` case → `setSearchBarVisible(false)`). Its purpose
is exactly the gap between "the query still has text" and "the bar is currently open": closing the
bar at a narrow layout must show the filter's own list again without erasing the query, so a later
tap on the search icon re-shows the same results — `searchActive` requiring both is what makes that
true. `HomeViewModel.pendingSearchFocus` is still a latched `StateFlow<Boolean>` rather than a
one-shot event, for the same reason as before: a request to focus the field is raised in the same
click that opens the bar, so the composable that will own the field hasn't composed yet, and a
`SharedFlow` with no subscriber yet would drop the request silently. `setSearchBarVisible(false)`
drops an unconsumed request the same way leaving the filter used to.

**The sidebar has no "Search" quick-filter row any more.** Typing directly into `FeedListPane`'s
permanent field (or into the narrow layout's expanded bar once open) is the whole entry point —
`searchActive` follows from the query alone, so a tap, a keyboard cursor landing on the field, and
arrow-key navigation all behave identically; there is no separate "enter Search" action to trigger
selectively, and therefore no asymmetry between how a tap and a keystroke start a search. This also
means `buildOrderedFeedListRows` needs no `includeSearchRow` parameter and `feedListRowIndex` no
longer special-cases a "Search" row — the row simply does not exist, at any layout.

**Search's own scope is the currently selected filter, not always every feed.** Selecting a
different feed/folder/tag while a query is active re-scopes the *same* search immediately
(`_rawSearchResults` combines `_filter` alongside the debounced query); switching to "All Feeds"
searches everywhere. `FtsSearch.articleScopeSql` builds the `WHERE`-clause fragment for each
`ArticleFilter` variant, matching `articles.sq`'s own `watchAll`/`watchStarred`/`watchByFeed`/
`watchByTag`/`watchByFolder` queries row-for-row — including their existing asymmetry (`Starred`
and `Feed` don't join `feeds` at all, so an unsubscribed feed's starred articles/own articles still
show under those scopes; `All`/`Tag`/`Folder` do). See "FTS5 handling" in `sync-architecture.md` and
`db-schema.md`'s own `articles_fts` section for the query mechanics this composes with.

**Nothing about ending a search restores anything.** Clearing the query (or, at a narrow layout,
closing the bar) simply switches `ArticleListPane`'s content back to the filter's own list —
`selectFilter` and `setSearchQuery` are independent of each other (switching filters never touches
the query; changing the query never touches the filter), and `_pinnedReadArticles` is deliberately
shared between the filter's own list and its search results rather than cleared on a query change,
so an article read from inside a search stays visible under unread-only exactly as if it had been
read from the plain list.

`ArticleListPane` therefore renders one continuous composable regardless of `searchActive` — no
early `return`, and no per-branch (un)mounting the way `PaneLayout.Single`'s `NarrowPaneRow` panes
get. Its `baseListState`/`searchListState` (two independent `LazyListState`s) and `lastFilter` are
all declared unconditionally at the top, so switching in and out of search never disposes either
list's scroll position — there is no snapshot/restore step needed to make a round trip through
search leave the filter's own list exactly where it was.

#### iOS

`external-spec.md` plans iOS/iPadOS as Compose first, then native SwiftUI later. This section's
model does not carry over unchanged:

- **The drawer is Android's idiom, not a universal narrow-layout one.** iOS/iPadOS collapse a
  `NavigationSplitView`'s sidebar into a pushed navigation stack at a compact width (Mail.app,
  NetNewsWire, Reeder) instead — which is what this app did before the drawer, and what `git log`
  still holds: `visiblePanes` returning `[FeedList]` at `Single` depth 1, `FeedListPane`'s own
  notification bell, `onEnterArticleList`, and the return ripple, all removed alongside it.
  `paneLayoutFor` and `visiblePanes`' `Triple`/`Dual` cases carry over to iPadOS unchanged (they map
  onto `NavigationSplitView`'s three- and two-column modes, and `Dual`'s permanent reading pane
  matches iPad's own split view); only `Single`'s presentation does not.
- **`hasNativeAppMenu` (see `platform/PlatformOs.kt`) is Android's own stand-in for "no native app
  menu bar", not iOS's** — iOS is `false` there too, so the header `app_name`/settings-footer
  decisions this flag now also drives need to be split out per-platform before iOS lands.
- **`HomeBackAction.None` falling through to the OS has no iOS equivalent** — there is no
  `OnBackPressedDispatcher`-like back gesture to fall through to, and no "exit the app" concept;
  iOS's own back navigation is a UI element this codebase draws itself.
- **The drawer's edge-swipe-to-open gesture (`gesturesEnabled`) would conflict with iOS's own
  `interactivePopGestureRecognizer`** (left-edge swipe = back) — not a concern as long as the
  drawer itself stays Android-only.
- The edge-to-edge layout (root `Box` applying only `WindowInsets.safeDrawing`'s horizontal side,
  each pane applying its own remaining side) is a clean carryover — it's the same shape iOS's own
  safe-area handling needs (notch / Dynamic Island / home indicator).

### Optimistic read/star pins

`HomeViewModel._pinnedReadArticles`/`_pinnedUnstarredArticles` are how the article list avoids
shifting under the user the instant they act on it: selecting an unread article marks it read in the
DB asynchronously (`dbWriteDispatcher`), but the row must show as read *now*, and — under
unread-only — must not simply vanish from the list before the next filter switch. The `articles`
combine resolves each row's `is_read`/`is_starred` from the pin when present, falling back to the
raw query's value otherwise, and (under unread-only) treats pinned-read membership itself as
"currently unread enough to show". These pins are therefore a deliberately optimistic cache that can
outrun the DB by design — but nothing about setting one re-checks that the DB actually caught up, so
without revalidation a pin could hide an external change (another device's sync propagating a "mark
unread"/restar, or a soft-delete tombstone) forever, not just for the brief window the write is in
flight for.

`HomeViewModel.reconcilePinnedArticles` closes that gap: it runs on every write to `articles` (via
an `articleChangeSignal` collector), revalidating every pinned id — and the current selection's own
cached flags — against `ArticleRepository.aliveArticleFlags` in one query, dropping (or, for the
selection, refreshing) anything whose article is gone or whose flags no longer match what was
pinned. The read it does this with is deliberately routed through `dbWriteDispatcher`, the same
serial (`limitedParallelism(1)`) dispatcher every pin-setting call site (`selectArticle`/
`toggleRead`/`toggleStar`/`markAllRead`/`markSelectedUnread`) dispatches its own DB write to — and
every one of those call sites dispatches that write *before* updating the pin/selection, never
after. Since the pin/selection fields are `MutableStateFlow`s, observing a given pin here implies
(by the flow's memory-visibility guarantee) that the write which justified it was already enqueued
onto `dbWriteDispatcher`; routing this read through the same FIFO dispatcher then guarantees it runs
*after* that write lands, so this function can never mistake a still-in-flight optimistic write for
an external change and drop a pin that is actually still correct. This is a real hazard only under
genuine multi-threaded dispatchers (`Dispatchers.Default`), not something the existing single-
scheduler test suite can reproduce directly — the invariant is enforced by code review and the
comments at each call site, not a dedicated race test.

A same-filter re-selection leaves both pins, the selection, and the cursor untouched
(`selectFilter`'s early return above) — reasonable now that the article list is always either an
on-screen pane (`Triple`/`Dual`) or the one pane a narrow layout keeps on screen except while
reading (`Single`), so re-selecting the active filter never has to distinguish a *return* to it
from an *entrance* into it the way it once did (see "iOS" in "Home's adaptive pane layout" above
for that removed mechanism). A genuine filter change still clears `_selectedArticle` along with
both pins on every path that reaches it, which matters for the pins' own sake — left set,
`HomeViewModel.pinnedReadArticlesKeepingSelected` would simply re-seed the read pin from it the
next time the user toggles unread-only back on, defeating the reset entirely.

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
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, OpmlOpenHandler (importOpmlAndNotify, shared by desktop's and Android's ".opml file association"), CloudSession, NotificationCenter, MergeSql, MergeFailureClassifier, MergeSchema, IdGenerator, CloudConnectFlow, OAuthConnectFlow, OAuthRedirectTransport (interface + CustomUri), OAuthCallbackParams, StartupMaintenanceTasks (refreshFeedsAndNotify/checkForUpdateAndNotify/maybeRebuildFtsIndex)
    di/           AppModule (+ expect platformModule)
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot, DatabaseFile (all expect)
    ui/           theme/, navigation/, setup/, home/ (3-pane + search + notification center), article/, settings/, i18n/
    LaunchArg.kt  Classifies a raw launch argument (`keryx://` URI vs `.opml` path) — platform-independent, package root
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq (7 tables)
  commonMain/composeResources/  values/strings.xml, drawable/ (icons are Android Vector Drawable XML,
    not SVG — Compose Multiplatform's SVG decoder is desktop/iOS-only and crashes on Android at
    runtime; VectorDrawable XML is the one image format `painterResource` renders on every target)
  jvmCommonMain/kotlin/…/  actuals shared by desktop and Android, needing no platform API either
    target lacks: FileIO, Gzip, Sha1, ContentDigest, Pkce, FileTokenStorage, AppInfo,
    CloudStorageAvailability (the last two just read the shared generated BuildConfig)
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt (runStartupTasks/backgroundUpdateLoop/handleOpenedOpmlFile — the desktop-only orchestration, delegating the actual maintenance work to commonMain's StartupMaintenanceTasks) + actual implementations of each expect not covered by jvmCommonMain (DatabaseDriverFactory, AppDirs, FilePicker, DatabaseMerger, PlatformModule) + LoopbackRedirectTransport, OAuthUriParser, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage implementation (Keyring/File/SecurityCliTokenStorage), DesktopOs (isMacOs/isWindows/isLinux/isTouchPrimary=false/hasNativeAppMenu=true/hasSystemTray=true), DesktopLookAndFeel (Swing L&F: FlatLaf on Linux)
    tray/      KeryxTray (platform branch), MacTray, LinuxTray + the StatusNotifierItem/dbusmenu D-Bus objects
  androidMain/kotlin/…/  actual implementations not covered by jvmCommonMain: DatabaseDriverFactory
    (bundled SQLite, see below), DatabaseFile (`databaseFilePath()` — `Context.getDatabasePath`,
    a different directory than AppDirs.appDataDir()/`Context.filesDir`; see db-schema.md),
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
    Direction" in external-spec.md), `ListRowChrome.android.kt`'s `listRowSurface` (a pill-shaped
    `NavigationDrawerItem`-style highlight for `ListRowKind.NavItem` rows, full-bleed for
    `ListRowKind.ListItem` rows — see that file's own KDoc), TooltipIconButton/ToolbarIconGroup/
    FlatTooltipContent (a plain M3 `IconButton` + `TooltipBox` with its own native long-press
    trigger, an unadorned `Row` instead of desktop's macOS-toolbar-style capsule, and M3's own
    `PlainTooltip`, respectively), KeryxRaisedSurface (a distinctly-tinted
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
    background/notification story)
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

Manages `articles_fts` (FTS5 trigram, `content='articles'`) via raw SQL. Not included in the SQLDelight schema.
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
  A label change bumps a revision and emits `LayoutUpdated`; `AboutToShow` compares the desired labels
  against what `GetLayout` last served, so a dropped signal still heals.

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
`PaneLayout.Dual` (article list + one neighbor), or `PaneLayout.Single` (one pane, phone width).
The navigation stack itself is always three deep (`HomePane.FeedList` → `ArticleList` →
`ArticleDetail`); a narrower layout just shows fewer of those three at once. `HomePane.ordinal + 1`
doubles as the stack's current depth, so `HomeScreen` needs no separate depth state — selecting a
filter or an article advances it (`FeedListPane`'s `onSelectionAdvance` / `ArticleListPane`'s
`onSelectionAdvance`, both `null` at `Triple`, where every pane is already visible and there is
nowhere to advance to), and `platform/BackHandler` (a real back-gesture/button interception on
Android, a no-op on desktop) pops it by one — gated on `homeBackAction(layout, depth,
searchScopeReturnPending)`, which wraps the pane-only predicate `canNavigateBack(layout, depth)`
(`false` whenever stepping back wouldn't actually change what's on screen: always at `Triple`;
also at `PaneLayout.Dual` depth 1→2, since the sliding window below shows the same two panes at
both — a back press there used to be silently swallowed before this existed) with the other half
of "what does going back actually do": exiting the Search scope instead of popping a pane, when a
snapshot is waiting to be restored (see "Search at a narrow layout" below) — this takes priority
even where `canNavigateBack` alone says there's nothing to do, since exiting Search always changes
what's on screen. `PaneLayout.Dual` is a two-pane *sliding window* over that stack, not a plain adjacent pair: the
article list stays one of the two panes shown at every depth, so drilling into an article swaps the
feed list out for the detail pane rather than sliding the list itself off-screen.

At a narrow layout the panes are hosted by `ui/home/NarrowPaneRow.kt`, which is what keeps each
one's scroll position across the stack's comings and goings — the two layouts lose it for different
reasons, so it addresses both. `Dual` never unmounts the article list, but the slide moves it from
index 1 to index 0 of `visiblePanes`' result, and a `visible.forEach` loop gives every iteration the
same compose group key, so a pane that changes position used to be torn down and rebuilt even though
it never left the screen. `NarrowPaneRow` emits each pane from its own fixed source position instead
(a pane added there must likewise get its own `if`, never a loop iteration), so it is simply never
disposed and keeps its `LazyListState` outright. `Single` genuinely unmounts every pane but one, and
there a `rememberSaveableStateHolder` saves each pane's `rememberSaveable` state — in practice its
`LazyListState`, which `rememberLazyListState` stores that way — and restores it as the list state's
*initial* index/offset, so nothing scrolls and no new call lands in the `scrollToIndexIfNeeded` code
path `known-issues.md` implicates in an unfixed upstream Compose crash. `ArticleListPane`'s
`lastFilter` is a `rememberSaveable` holding `ArticleFilter.encode()`'s string for the same reason:
the filter can change while the pane is unmounted (a notification's `ShowFeedDetail`, or deleting the
feed being viewed), and a plain `remember` would re-initialize to the new filter on remount, leaving
the restored position pointing into the previous filter's list with no reset to the top.

This is why the article reader's WebView being unconditionally composed (see "Article Reader"
below) is safe on desktop specifically: desktop can only ever resolve `Triple`, where all three
panes — including the one hosting the WebView — stay mounted for the app's whole lifetime.
`Single`/`Dual` do unmount it when its pane isn't among those currently shown, which is fine on
Android (no heavyweight AWT interop concern there).

At a narrow layout, `initialPaneFor(layout, saved)` also clamps the pane `HomeScreen` restores on
launch: `HomePane.ArticleDetail` is left alone at `Triple` (the article the user was last reading,
same as ever), but clamped down to `ArticleList` at `Single`/`Dual` — restoring straight into a
detail pane with no list around it and no context for how the user got there would be disorienting
on a phone-shaped session. This clamp is applied exactly once, on the first frame with a real
(post-layout) width, and never again — a later resize or rotation must not yank the user off
whatever they're reading.

**Search at a narrow layout** moves the field itself, not just its surrounding chrome — see the
`ui-guidelines` skill's "Adaptive pane layout & touch affordances" section for the full design
(`ui/common/KeryxSearchBar.kt`'s `KeryxCollapsedSearchBar`/`KeryxExpandedSearchBar`, and why the
narrow/`Triple` split is driven by `onSelectionAdvance`/`onNavigateUp` being `null` rather than a
`PaneLayout` or `isTouchPrimary` parameter). `HomeViewModel.pendingSearchFocus` is a latched
`StateFlow<Boolean>` rather than a one-shot event for the same reason as the depth cursor above: a
request to focus the field is raised in the same click that advances the stack, so the pane that
will own the field hasn't composed yet, and a `SharedFlow` with no subscriber yet would drop the
request silently.

Search has no `HomePane` of its own — every entry point just sets `ArticleFilter.Search` on
`HomePane.ArticleList` with its content swapped out, without necessarily advancing the stack (the
article list's own search icon doesn't; the collapsed bar does) — so a plain "pop
one pane" back action can't undo it correctly either way. `HomeViewModel.enterSearchScope(returnPane)`
snapshots the filter/row-selection active right before the switch, plus the pane a narrow-layout
back action should land on; `exitSearchScope()` restores both and hands back that pane, which
`homeBackAction`'s `ExitSearch` case (above) resolves to instead of `PopPane`. The search query
itself is never touched by any of this — it survives on the collapsed bar exactly as it was.

`enterSearchScope`'s snapshot also carries the browsing context active at that moment — the
pinned-read/pinned-unstarred maps, the selected article, and the keyboard-navigation cursor (see
"Optimistic read/star pins" below) — because `selectFilter` (which entering Search goes through
like any other filter change) clears all of that. `exitSearchScope` restores it, but not verbatim:
it re-resolves every snapshotted id against the DB's *current* flags (via the same
`ArticleRepository.aliveArticleFlags` the reactive reconciliation below uses), so a change made from
the search results themselves, or one that arrived via sync while Search was active, is not
overwritten by the frozen pre-Search snapshot. A pin set *from inside* Search is never part of this
at all — only what was pinned *before* Search was entered is — since restoring it would resurface a
possibly unrelated feed's article in the returned filter's list (see the `articles` combine's `extra`
handling below). Restoration is skipped entirely when the filter itself fell back to a different one
(`validateFilterTarget` found its target deleted meanwhile): the snapshot's pins/selection belong to
the *original* filter, not the fallback.

Because Search has no `HomePane` of its own, `ArticleListPane` renders `SearchListPane` from an
early `return` inside the same composable rather than through `NarrowPaneRow`'s pane-level
`SaveableStateHolder` — so the article list's own `listState`/`lastFilter` have to stay declared
*above* that `return` to remain part of composition (and therefore alive) while Search is active;
declaring them below it, next to the content that uses them, would dispose and recreate them every
time Search opens, resetting the list to the top on every return. `lastFilter` is also left
untouched while `filter is ArticleFilter.Search`, so returning to the same filter Search was
entered from reads as "unchanged" and skips the reset-to-top the same mechanism otherwise applies
on a genuine filter change. Restoring the selected article on return can re-trigger
`ArticleListPaneContent`'s own "keep the selection in view" scroll on remount — harmless when a
pane genuinely unmounted (the selection is always inside the just-restored viewport there, see
"Adaptive pane layout" above), but not guaranteed here, since the list's own scroll position and the
restored selection come from independent snapshots. `ArticleListPaneContent`'s
`preserveScrollPositionOnMount` parameter exists for exactly this: `ArticleListPane` sets it for the
one composition right after Search closes, suppressing that scroll for the mount's first evaluation
only — a later, genuine selection change still scrolls normally.

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

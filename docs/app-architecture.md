# Keryx App Architecture

[日本語](app-architecture.ja.md)

## Design Philosophy

- Layered architecture (UI → ViewModel → Repository → DataSource)
- Dependency injection with Koin, state management with androidx.lifecycle ViewModel
- Type-safe local DB management with SQLDelight
- Sync processing is confined to the Repository layer; the UI layer is unaware of sync
- Platform-specific code is consolidated behind `commonMain` `expect` + `desktopMain` `actual`

## Directory Structure

```text
composeApp/src/
  commonMain/kotlin/works/merc/keryx/app/
    core/      Constants, Result, KeryxException, ArticleFilter, AppNotification, Clock, DateTimeParser, CloudStorageAvailability(expect)
    data/local/   DatabaseDriverFactory(expect), FtsManager, FtsSearch, LocalSettings(Store)
    data/remote/  FeedFetcher, FeedParser, FeedDiscovery, FaviconResolver, UrlResolver, FeedModels
    data/cloud/   CloudStorage, CloudAuthManager, DropboxStorage, DropboxAuthManager, GoogleDriveStorage, GoogleDriveAuthManager, OneDriveStorage, OneDriveAuthManager, Pkce(expect), TokenStorage, OAuthTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, CloudSession, NotificationCenter, MergeSql, IdGenerator, CloudConnectFlow
    di/           AppModule (+ expect platformModule)
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot (all expect)
    ui/           theme/, navigation/, setup/, home/ (3-pane + search + notification center), article/, settings/, i18n/
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq (7 tables)
  commonMain/composeResources/  values/strings.xml, drawable/
  desktopMain/kotlin/…/  main.kt + actual implementations of each expect (DatabaseDriverFactory, AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, Pkce, PlatformModule) + OAuthConnectFlow, OAuthRedirectTransport (CustomUri/Loopback), OAuthUriParser, SingleInstanceCoordinator, TokenStorage implementation (Keyring/File/SecurityCliTokenStorage), HostOs
    tray/      KeryxTray (platform branch), MacTray, LinuxTray + the StatusNotifierItem/dbusmenu D-Bus objects
  commonTest/ + desktopTest/
```

The package root is `works.merc.keryx.app` (reverse DNS of `keryx.merc.works`).

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

### FtsManager / FtsSearch

Manages `articles_fts` (FTS5 trigram, `content='articles'`) via raw SQL. Not included in the SQLDelight schema.
**Never DROP the live DB's `articles_fts`** (excluded from upload via `VACUUM INTO` snapshot copy (`DatabaseSnapshot`), dropping it on the copy side, so concurrent searches never hit `no such table`). Hot paths (feed refresh, sync merge) incrementally index new rows via `FtsManager.indexMissing()` — never a full `'rebuild'`, which is O(all indexed text) and would block/zero-out concurrent searches. The whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass in `main.kt` (`maybeRebuildFtsIndex`, gated on `lastFtsRebuiltAt` + `ActivityCenter` idle), which re-indexes content that incremental indexing left stale. On startup, `FtsManager.ensureIndexed()` creates the table on first run and backfills any missing rows. `busy_timeout` (set in `DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief write lock of an incremental insert or a rebuild.

### DatabaseMerger (expect / actual) — Key to Sync Merge

ATTACH DATABASE merge runs through `platform/DatabaseMerger`, NOT the SQLDelight driver. SQLDelight's JVM `JdbcSqliteDriver` opens a fresh connection per statement for file DBs, so an `ATTACH` on one call is invisible to the merge statements on the next. `DatabaseMerger` does the whole attach → version check → merge → detach on a single dedicated JDBC connection.

### CloudSession / SyncRepository

`CloudSession` provides the current `CloudStorage` (Dropbox / Google Drive) and handles automatic access-token refresh. `SyncRepository` implements the download → merge (`DatabaseMerger`) → incremental index of new articles (`indexMissing`) → `VACUUM INTO` snapshot generation (`DatabaseSnapshot`, excludes `articles_fts` on the copy side) → upload (rev check) flow, along with debouncing (`SyncScheduler`). The live DB's FTS is untouched.

### Provider / DI (Koin)

`appModule` (`commonMain`) registers repositories, services, and ViewModels. `platformModule` (`desktop`) registers HttpClient, TokenStorage, CloudSession, and CloudConnectFlow. ViewModels are registered as app-scope `single` for a single-window desktop app and obtained via `koinInject()`.

### Desktop Tray (platform branch)

`tray/KeryxTray.kt` picks one of three implementations:

| Platform | Implementation | Why |
| --- | --- | --- |
| macOS | `MacTray` (raw AWT `TrayIcon`) | Compose's `Tray()` wires the menu through `TrayIcon.setPopupMenu()`, which opens it on *any* click on macOS. |
| Linux (SNI host present) | `LinuxTray` (D-Bus StatusNotifierItem) | AWT cannot draw a transparent tray icon on X11 — see below. |
| Windows / Linux (no SNI host) | Compose `Tray()` | Works as-is. |

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

## Domain Model Policy

SQLDelight generated classes (`Feeds` / `Articles` / …) are used as-is in all layers. Column names become properties in snake_case (e.g. `feed.site_url`). Booleans and timestamps are kept as `Long` (0/1, Unix millis) and converted with kotlinx-datetime at display time. No separate domain model classes are defined.

## Navigation

A simple stack navigator in `ui/navigation/Navigator.kt` switches between Setup / Home / Settings. Article view is a pane inside Home (not a root route).

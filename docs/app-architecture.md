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
    data/cloud/   CloudStorage, DropboxStorage, DropboxAuthManager, Pkce(expect), TokenStorage, DropboxTokens
    data/opml/    OpmlCodec
    domain/       Feed/Article/Tag/Settings/SyncRepository, CloudSession, NotificationCenter, MergeSql, IdGenerator, DropboxConnectFlow
    di/           AppModule (+ expect platformModule)
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot (all expect)
    ui/           theme/, navigation/, setup/, home/ (3-pane + search + notification center), article/, settings/, i18n/
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq (7 tables)
  commonMain/composeResources/  values/strings.xml, drawable/
  desktopMain/kotlin/…/  main.kt + actual implementations of each expect (DatabaseDriverFactory, AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, Pkce, PlatformModule) + CustomUriDropboxConnectFlow, SingleInstanceCoordinator, TokenStorage implementation (Keyring/File/SecurityCliTokenStorage)
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
**Never DROP the live DB's `articles_fts`** (excluded from upload via `VACUUM INTO` snapshot copy (`DatabaseSnapshot`), dropping it on the copy side, so concurrent searches never hit `no such table`). Hot paths (feed refresh, sync merge) incrementally index new rows via `FtsManager.indexMissing()` — never a full `'rebuild'`, which is O(all indexed text) and would block/zero-out concurrent searches. The whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass in `main.kt` (`maybeRebuildFtsIndex`, gated on `lastFtsRebuiltAt` + `ActivityCenter` idle), which also re-indexes content that incremental indexing left stale and sweeps entries left by cache-cleanup deletions. On startup, `FtsManager.ensureIndexed()` creates the table on first run and backfills any missing rows. `busy_timeout` (set in `DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief write lock of an incremental insert or a rebuild.

### DatabaseMerger (expect / actual) — Key to Sync Merge

ATTACH DATABASE merge runs through `platform/DatabaseMerger`, NOT the SQLDelight driver. SQLDelight's JVM `JdbcSqliteDriver` opens a fresh connection per statement for file DBs, so an `ATTACH` on one call is invisible to the merge statements on the next. `DatabaseMerger` does the whole attach → version check → merge → detach on a single dedicated JDBC connection.

### CloudSession / SyncRepository

`CloudSession` provides the current `CloudStorage` (Dropbox) and handles automatic access-token refresh. `SyncRepository` implements the download → merge (`DatabaseMerger`) → incremental index of new articles (`indexMissing`) → `VACUUM INTO` snapshot generation (`DatabaseSnapshot`, excludes `articles_fts` on the copy side) → upload (rev check) flow, along with debouncing (`SyncScheduler`). The live DB's FTS is untouched.

### Provider / DI (Koin)

`appModule` (`commonMain`) registers repositories, services, and ViewModels. `platformModule` (`desktop`) registers HttpClient, TokenStorage, CloudSession, and DropboxConnectFlow. ViewModels are registered as app-scope `single` for a single-window desktop app and obtained via `koinInject()`.

## Domain Model Policy

SQLDelight generated classes (`Feeds` / `Articles` / …) are used as-is in all layers. Column names become properties in snake_case (e.g. `feed.site_url`). Booleans and timestamps are kept as `Long` (0/1, Unix millis) and converted with kotlinx-datetime at display time. No separate domain model classes are defined.

## Navigation

A simple stack navigator in `ui/navigation/Navigator.kt` switches between Setup / Home / Settings. Article view is a pane inside Home (not a root route).

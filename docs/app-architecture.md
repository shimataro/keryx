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
    domain/       Feed/Article/Tag/Settings/SyncRepository, OpmlImporter, CloudSession, NotificationCenter, MergeSql, IdGenerator, CloudConnectFlow
    di/           AppModule (+ expect platformModule)
    platform/     AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, DatabaseSnapshot (all expect)
    ui/           theme/, navigation/, setup/, home/ (3-pane + search + notification center), article/, settings/, i18n/
  commonMain/sqldelight/works/merc/keryx/app/data/local/db/  *.sq (7 tables)
  commonMain/composeResources/  values/strings.xml, drawable/
  desktopMain/kotlin/…/  main.kt + StartupTasks.kt (startup/background-task functions) + actual implementations of each expect (DatabaseDriverFactory, AppDirs, FileIO, BrowserOpener, FilePicker, DatabaseMerger, Pkce, PlatformModule) + OAuthConnectFlow, OAuthRedirectTransport (CustomUri/Loopback), OAuthUriParser, LaunchArg, SingleInstanceCoordinator, UriSchemeRegistration + LinuxUriSchemeRegistrar + LinuxOpmlAssociationRegistrar, TokenStorage implementation (Keyring/File/SecurityCliTokenStorage), DesktopOs (isMacOs/isWindows/isLinux), DesktopLookAndFeel (Swing L&F: FlatLaf on Linux)
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
**Never DROP the live DB's `articles_fts`** (excluded from upload via `VACUUM INTO` snapshot copy (`DatabaseSnapshot`), dropping it on the copy side, so concurrent searches never hit `no such table`). Hot paths (feed refresh, sync merge) incrementally index new rows via `FtsManager.indexMissing()` — never a full `'rebuild'`, which is O(all indexed text) and would block/zero-out concurrent searches. The whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass in `StartupTasks.kt` (`maybeRebuildFtsIndex`, gated on `lastFtsRebuiltAt` + `ActivityCenter` idle), which re-indexes content that incremental indexing left stale. On startup, `FtsManager.ensureIndexed()` creates the table on first run and backfills any missing rows. `busy_timeout` (set in `DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief write lock of an incremental insert or a rebuild.

### DatabaseMerger (expect / actual) — Key to Sync Merge

ATTACH DATABASE merge runs through `platform/DatabaseMerger`, NOT the SQLDelight driver. SQLDelight's JVM `JdbcSqliteDriver` opens a fresh connection per statement for file DBs, so an `ATTACH` on one call is invisible to the merge statements on the next. `DatabaseMerger` does the whole attach → version check → merge → detach on a single dedicated JDBC connection.

### CloudSession / SyncRepository

`CloudSession` provides the current `CloudStorage` (Dropbox / Google Drive) and handles automatic access-token refresh. `SyncRepository` implements the download → merge (`DatabaseMerger`) → incremental index of new articles (`indexMissing`) → `VACUUM INTO` snapshot generation (`DatabaseSnapshot`, excludes `articles_fts` on the copy side) → upload (rev check) flow, along with debouncing (`SyncScheduler`). The live DB's FTS is untouched.

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

The reader treats feed-supplied HTML as fully trusted content by design: JavaScript is enabled
(the WebView's default) and `wrapArticleHtml` inserts the feed body unescaped (see its KDoc in
`ArticleWebViewHtml.kt` — "`[body]` stays raw"), which is what lets SNS-embed widgets (e.g. the
X/Twitter embed referenced in `ArticleDetailPane.kt`'s link-interception comment) render in
place. The only gate on outbound traffic is navigation: `RequestInterceptor` routes a genuine
`<a>` link click to the system browser and lets every other request (image/script/iframe/XHR)
load inside the WebView untouched. On macOS this additionally requires
`NSAppTransportSecurity`/`NSAllowsArbitraryLoadsInWebContent` in `Info.plist`
(`composeApp/build.gradle.kts`) for the WebView to load plain-HTTP resources at all — scoped to
WebView content only (this app's own networking, via Ktor, is unaffected by ATS either way), but
applying to every WebView request, not only `<img>` sources. This is accepted rather than
narrowed: it does not open a new class of exposure given the trust decision above already
predates it, and Windows (WebView2) / Linux (WebKitGTK) have no ATS-equivalent restriction in the
first place, so the exception only brings macOS to parity with how the reader already behaves on
the other two platforms.

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

### Native file dialogs (platform branch)

`platform/FilePicker.desktop.kt`'s `defaultFilePickerBackend` picks one of two implementations, the
same Linux-Swing-vs-AWT split as `NativeMenu.desktop.kt`'s `defaultPopupHandle`:

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

### アイコンセット

`ui/common/KeryxIcons.kt` が全 UI 呼び出し箇所の唯一の間接参照点になっており（意味的な名前 →
`composeResources/drawable/` 配下のバンドル SVG）、現在は Tabler Icons（MIT）を使用している（デスクトップ
3OS 共通で Mac 寄りの見た目に寄せるため。詳細は `ui-guidelines` skill）。将来 Android 対応に着手する際は、
Android のネイティブな視覚言語は Material Design であるため、Android ターゲットだけ Material 系アイコン
（Material Symbols）に差し替えることを検討する余地がある — `KeryxIcons` を `expect`/`actual` に分割すれば
プラットフォームごとに個別のアイコンセットを出し分けられる。ただし Android ターゲット自体がまだ存在しない
現時点では検証しようがないため、着手は Android 対応開始時まで先送りする。iOS/iPadOS/macOS がいずれ
ネイティブ SwiftUI 化された場合（`external-spec.md` §2 の想定どおり）、そちらは Kotlin の `KeryxIcons` とは
無関係の別コードベースになるため、SF Symbols を `Image(systemName:)` で直接使えばよく、Kotlin 側に追加の
差し替え機構は不要。つまり将来「SwiftUI = SF Symbols / Android = Material / Windows・Linux = 現行の Tabler」
という3分岐になっても、Kotlin 側で実質必要になるのは上記の Android 用 `expect`/`actual` 分割だけである。

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

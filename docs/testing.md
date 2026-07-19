# Testing Conventions

[日本語](testing.ja.md)

## Structure

- `commonTest/` — Pure logic and Ktor `MockEngine` tests (parsers, fetchers, URL resolvers, OPML, Dropbox storage/auth, local settings). Runs on the desktop target, so `expect` declarations resolve to desktop `actual`s (`FileIO` / `AppDirs` available with temp directories).
- `desktopTest/` — Tests requiring the actual SQLDelight driver (`JdbcSqliteDriver`) (schema, article upsert, ATTACH merge). Helpers are in `DbTestSupport.kt` (`inMemoryDb()`, `fileDb()`, `insertFeed()`). This directory also contains Compose UI tests that render actual Composables (`androidx.compose.ui.test.runDesktopComposeUiTest`, no JUnit4 rule needed) (e.g. `ArticleListPaneTest.kt`). Requires the actual Skia/AWT renderer, so placed in `desktopTest` rather than `commonTest`.

New tests are placed at the same relative path as the code under test.

## Conventions

- Framework: `kotlin.test` (`@Test`, `assertEquals`, `assertIs`, `assertTrue`, `assertFailsWith`).
  Coroutines: `kotlinx.coroutines.test.runTest`.
- HTTP: Ktor `MockEngine` + `respond(...)`. Clients are built with the same config as production DI
  (`followRedirects=false`, `expectSuccess=false`, fetcher installs `HttpTimeout`).
- Time is faked via `Clock { fixedMillis }`, scheduling via `SyncScheduler {}`.
- Merge is verified by calling `platform/DatabaseMerger.merge(...)` on two `fileDb()` instances
  (close the SQLDelight driver before merging on the raw connection).
- `androidx.lifecycle.ViewModel` tests (depending on `viewModelScope` using `Dispatchers.Main.immediate`) must call `Dispatchers.setMain(StandardTestDispatcher())` in `@BeforeTest` and `Dispatchers.resetMain()` in `@AfterTest` (`HomeViewModelTest.kt` is the first example). If the `StateFlow` uses `SharingStarted.WhileSubscribed(...)`, the test must explicitly `collect` to start subscription, or values will not update.
- Classes that directly use `CloudStorage` like `SyncRepository` are verified by swapping `CloudStorage` with a hand-rolled fake (in-memory Map + rev management) instead of mocking the HTTP layer (`SyncRepositoryTest.kt`). `DropboxStorage`/`DropboxAuthManager` tests themselves mock the HTTP layer via Ktor `MockEngine` as usual.
- Combining `runTest` (virtual time) with Ktor `MockEngine`'s `HttpTimeout` or real socket I/O can cause false timeout detection and flakiness. Affected tests switch to `kotlinx.coroutines.runBlocking` (or real-time polling) (`FeedFetcherTest.kt`, `FeedRepositoryTest.kt`, `OAuthLoopbackServerTest.kt`, etc.).

## `Result<T>` Testing Policy

Test both success (`Result.Ok`) and failure (`Result.Err`) branches; on failure, verify the specific `KeryxException` subtype (`FeedTimeoutException`, `FeedNotFoundException(isGone=…)`, `SyncConflictException`, `CloudAuthException`, `FeedDiscoveryException`, etc.).

## Execution

```bash
./gradlew :composeApp:desktopTest
```

Currently 527 tests. Coverage includes parser, fetcher redirect/304/404/410/timeout/discovery, OPML, Dropbox storage/auth, PKCE, OAuth loopback server, merge (last-write-wins / OR merge / collision guard / FK guard), schema, local settings, article upsert, URL resolver, datetime parser, Result, Repository layer (Article/Feed/Tag/Settings), CloudSession, NotificationCenter, IdGenerator, SyncRepository, ViewModel layer (Home/Settings/Setup/NotificationCenter), ArticleWebViewHtml (extractLinks/wrapArticleHtml), FTS (FtsManager/FtsSearch, including `indexMissing` incremental insert / non-destructive behavior, `rebuildIndex` requiring table existence, sync upload excluding `articles_fts` via `VACUUM INTO` snapshot while preserving `user_version`), etc. `SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` failures specifically indicate regression in DB schema / merge SQL / sync orchestration and require extra attention.

Known uncovered areas: `SettingsViewModel.exportOpml`/`importOpml` (no test seam for `FilePicker` native dialog), the browser-launch → callback-wait → code-exchange portion of `DesktopDropboxConnectFlow.connect()` (depends on actual `BrowserOpener`/`OAuthLoopbackServer` I/O and cannot be mocked without a seam; only the immediate-error branch for empty App Key is covered in `DesktopDropboxConnectFlowTest`), `DatabaseDriverFactory.desktop.kt` (directly references `AppDirs.appDataDir()` and cannot be substituted with a test directory), `FeedDragAndDrop.desktop.kt` (`DragAndDropTransferable` is a library internal type that cannot be extracted from test code, and `draggedFeedId()`/`draggedFolderId()`/`positionYInRoot()` cannot be called without an actual AWT `DropTargetDragEvent`/`DropTargetDropEvent`). For the same reason, actual feed/folder reordering/move gestures themselves (`FeedListPane.kt`'s `FeedRow`/`FolderGroupHeader`/`NoFolderHeader` `dragAndDropSource`/`dragAndDropTarget`) are also untestable. The reordering calculation logic itself (`ReorderUtil.reorderIds`) and its consumers `FeedRepository.moveFeed`/`FolderRepository.reorderFolders` are tested normally.

## Manual Confirmation (UI)

Launch with `./gradlew :composeApp:run` and visually verify 3-pane UI, theme switching, feed addition, and search. Feed/folder reordering cannot be auto-tested, so visually confirm the following:

- Drag folders to reorder; order persists after app restart.
- Drag feeds inside a folder to reorder; order persists after restart.
- Reorder feeds inside the "No Folder" group.
- Drop a feed onto another folder at any position (line position); folder move and positioning are applied simultaneously.
- "Move to folder" dialog moves the feed to the end of the destination group.

Dialog auto-sizing (`DialogWindow` OS window behavior) cannot be auto-tested, so visually confirm:

- In feed addition, entering a URL for an HTML page with multiple feed links → on confirmation, **the dialog expands to show candidate list (checkboxes)**. Editing the URL shrinks it again. No shaking or flickering during input→candidate transition.
- Candidate list "Select All / Deselect All" toggle and selection count display; "Subscribe" disabled at 0 selections, enabled after selection; subscribe button always visible below candidate list. Button label toggles between Confirm ↔ Subscribe; can submit with Enter.
- Single feed URL input shows title and "N articles" and is subscribable.
- macOS merged title bar / traffic light margins and Windows/Linux decoration height do not break in expanded state.

Dock/taskbar icons (`Taskbar` / Cocoa activation policy native path) cannot be auto-tested, so visually confirm the following. macOS: confirm with both `./gradlew :composeApp:createDistributable`'s `Keryx.app` and `./gradlew :composeApp:run`:

- On launch, the brand Keryx icon (with badge if unread) appears in the Dock.
- (macOS) Minimize to tray → restore via tray toggle; Dock icon remains the brand icon (does not change to terminal/JVM style). If the default icon flickers/remains briefly on restore, add a short `delay` before reapplying `applyBrandedDockIcon` in `main.kt`.
- (macOS) On restart-while-running (double-launch activation), Dock icon remains the brand icon.
- Repeated hide/restore with unread > 0 preserves the badge.
- No regression on Windows/Linux taskbar icon/unread overlay.

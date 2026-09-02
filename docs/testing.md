# Testing Conventions

[日本語](testing.ja.md)

## Structure

- `commonTest/` — Pure logic and Ktor `MockEngine` tests (parsers, fetchers, URL resolvers, OPML, Dropbox storage/auth, local settings). Runs on the desktop target, so `expect` declarations resolve to desktop `actual`s (`FileIO` / `AppDirs` available with temp directories).
- `desktopTest/` — Tests requiring the actual SQLDelight driver (`JdbcSqliteDriver`) (schema, article upsert, ATTACH merge). Helpers are in `DbTestSupport.kt` (`inMemoryDb()`, `fileDb()`, `insertFeed()`). This directory also contains Compose UI tests that render actual Composables (`androidx.compose.ui.test.runDesktopComposeUiTest`, no JUnit4 rule needed) (e.g. `ArticleListPaneTest.kt`). Requires the actual Skia/AWT renderer, so placed in `desktopTest` rather than `commonTest`.
- `androidDeviceTest/` — Instrumented tests for `DatabaseMerger`/`DatabaseSnapshot`'s Android actuals, which open the bundled `requery` SQLite (a native library) directly and therefore cannot run as a plain JVM unit test the way `desktopTest` does — see `.claude/rules/android-sqlite-bundling.md`. Needs a connected device or running emulator; there is no `androidUnitTest`/`androidHostTest` source set in this module, since none of `composeApp`'s Android-specific logic is JVM-testable without either a device or Robolectric (not currently a dependency). Helpers are in `AndroidDbTestSupport.kt` (`createSchemaDbFile()`, mirroring `DbTestSupport.kt`'s `fileDb()` but driven through a real `AndroidSqliteDriver` so the schema is installed the same way production creates it). Scoped narrowly to what is genuinely Android-specific — the schema-version guard, the migration path, exception-*class*-based failure classification (Android's `SQLiteException` carries no numeric result code, unlike the JDBC driver desktop's `DatabaseMerger` reads `resultCode` from), and the `NoOpDatabaseErrorHandler` regression (the bundled SQLite's default error handler deletes a database file it judges corrupt, confirmed by disassembling the AAR) — not a full port of `desktopTest`'s merge/snapshot suites, since the merge SQL itself (`MergeSql`) is pure and already covered there.

- `androidApp/src/androidTest/` — Instrumented Compose UI tests that need a real Android
  application module to host `androidx.compose.ui.test.junit4.v2.createComposeRule` (e.g.
  `NativeMenuAndroidGestureTest.kt`, covering the long-press gesture policy of `nativeContextMenu`'s
  Android `actual`; `KeryxSearchBarAndroidTest.kt`, covering `ui/common/KeryxSearchBar.kt`'s Android
  `actual`s — this is where the M3-specific risk `desktopTest` cannot exercise at all lives: the
  editable field's `SearchBarDefaults.InputField` must not clip once the font-size setting scales
  text past its 56dp minimum height, confirmed here at the largest (1.4×) setting). `composeApp`
  itself is an Android *library* module (`com.android.kotlin.multiplatform.library`), not an
  application — its own instrumented tests (`androidDeviceTest` above) are scoped to native-driver
  concerns that don't need a Compose UI tree, so a Compose-rendering test lives here instead, in
  the one module that is an actual Android application.

New tests are placed at the same relative path as the code under test.

## Conventions

- Framework: `kotlin.test` (`@Test`, `assertEquals`, `assertIs`, `assertTrue`, `assertFailsWith`).
  Coroutines: `kotlinx.coroutines.test.runTest`.
- HTTP: Ktor `MockEngine` + `respond(...)`. Clients are built with the same config as production DI
  (`followRedirects=false`, `expectSuccess=false`, fetcher installs `HttpTimeout`).
- Time is faked via `Clock { fixedMillis }`, scheduling via `SyncScheduler {}`.
- Merge is verified by calling `platform/DatabaseMerger.merge(...)` on two `fileDb()` instances
  (close the SQLDelight driver before merging on the raw connection). Merge-failure classification
  (a corrupt/incompatible cloud DB vs. a transient/app-side failure — see "Merge Failure
  Classification" in [sync-architecture.md](sync-architecture.md)) is verified the same way:
  `SyncMergerTest.kt`'s `mergeThrows*`/`mergeDoesNotClassify*`/`mergeRethrows*` tests call `merge`
  directly against hand-built cloud DB files (a foreign schema, a `feeds` table missing its
  `UNIQUE(url)`/`NOT NULL` constraints so it can hold data that violates the real schema, a byte-
  flipped corrupt file, a `PRAGMA user_version` newer than local), and assert the thrown exception
  type; `SyncRepositoryTest.kt` covers the same classification end-to-end through `sync()`.
- `androidx.lifecycle.ViewModel` tests (depending on `viewModelScope` using `Dispatchers.Main.immediate`) must call `Dispatchers.setMain(StandardTestDispatcher())` in `@BeforeTest` and `Dispatchers.resetMain()` in `@AfterTest` (`HomeViewModelTest.kt` is the first example). If the `StateFlow` uses `SharingStarted.WhileSubscribed(...)`, the test must explicitly `collect` to start subscription, or values will not update.
- A Compose UI test that needs a real `HomeViewModel` must go through `ui/home/HomeViewModelTestSupport.kt`'s `ComposeUiTest.useHomeViewModel(driver, db) { fixture -> … }`, never a hand-rolled `try`/`finally`. `HomeViewModel`'s DB collectors use `SharingStarted.Eagerly`, and closing the driver without first `cancelAndJoin`-ing `viewModelScope` lets an already-queued continuation on the EDT resume against the closed connection — an *uncaught* exception that surfaces flakily, on whichever *other* test runs next, as `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`. `ComposeUiTest.waitForIdle()` does not help here: it only advances Compose's own test clock, it never pumps the AWT event queue.
- Classes that directly use `CloudStorage` like `SyncRepository` are verified by swapping `CloudStorage` with a hand-rolled fake (in-memory Map + rev management) instead of mocking the HTTP layer (`SyncRepositoryTest.kt`). `DropboxStorage`/`DropboxAuthManager` tests themselves mock the HTTP layer via Ktor `MockEngine` as usual.
- Combining `runTest` (virtual time) with Ktor `MockEngine`'s `HttpTimeout` or real socket I/O can cause false timeout detection and flakiness. Affected tests switch to `kotlinx.coroutines.runBlocking` (or real-time polling) (`FeedFetcherTest.kt`, `FeedRepositoryTest.kt`, `OAuthConnectFlowTest.kt`, etc.).
- **Never assert that two collectors of the same `StateFlow` observed an identical sequence.** A
  `StateFlow` is conflating: it only guarantees the latest value arrives, and intermediate values are
  dropped *per collector, independently*. A comparison of two recorded sequences therefore only holds
  on a machine fast enough that nothing got conflated for one collector but not the other — it fails
  on a slow/low-core CI runner (observed: `windows-latest`, 2 cores, in
  `UpdateRepositoryTest.everyCollectorObservesTheSameSharedStateProgression`, back when it compared
  the two lists with `assertEquals`). Assert instead that each recorded sequence is a *subsequence*
  of the one canonical progression the run emits (`assertSubsequenceOf` in that file), payloads
  included, and that both converge on the same terminal value. Two further rules go with it: wait for
  the *collectors* to reach the terminal value, not just for `flow.value` — they run on their own
  threads and lag it; and record into a thread-safe list (`CopyOnWriteArrayList`), since a plain
  `ArrayList` appended from `Dispatchers.Default` and read from the test thread has no happens-before
  edge between the two.
- A test that drives concurrent DB writes with real `Thread`s needs `fileDb()`, not `inMemoryDb()` — the latter pins every caller to one shared JDBC connection with no synchronization of its own, so two real threads can corrupt SQLDelight's transaction bookkeeping. Even with `fileDb()`, keep fixtures free of writes the test doesn't care about: SQLite's deferred `BEGIN` means a transaction that reads before it writes can fail an unrelated concurrent writer's lock upgrade with a non-retryable `SQLITE_BUSY` that `busy_timeout` does not cover — see "A concurrent write can fail a read-then-write transaction with a non-retryable SQLITE_BUSY" in `known-issues.md`.
- New Android Compose UI instrumented tests go in `androidApp/src/androidTest/` (`composeApp` is a
  library module, so its own instrumented tests live in the application module instead — see
  Structure above). A DB test that depends on the bundled `requery` SQLite goes in
  `composeApp/src/androidDeviceTest/`. **There is no `androidUnitTest`/`androidHostTest` source
  set** (Robolectric isn't set up) — pure logic that happens to live in an Android-specific class
  should be extracted into `commonMain` and tested from `commonTest` instead, the way
  `domain/BackgroundRefreshSchedule.kt` (tested by `BackgroundRefreshScheduleTest.kt`) already is.

## `Result<T>` Testing Policy

Test both success (`Result.Ok`) and failure (`Result.Err`) branches; on failure, verify the specific `KeryxException` subtype (`FeedTimeoutException`, `FeedNotFoundException(isGone=…)`, `SyncConflictException`, `CloudAuthException`, `FeedDiscoveryException`, etc.).

## Execution

One test is `@Ignore`d on purpose: `ArticleReuseCrashRepro` reproduces an outstanding Compose
defect that is deliberately not fixed, so it would fail every run. See
[known-issues.md](known-issues.md) — including how to re-enable it to check whether a Compose
upgrade has fixed the bug.

```bash
./gradlew :composeApp:desktopTest
```

Android has two separate instrumented suites, easy to conflate since only one of them is wired
into CI:

| Suite | Task | Covers | CI |
| --- | --- | --- | --- |
| `composeApp/src/androidDeviceTest/` | `:composeApp:connectedAndroidDeviceTest` | `DatabaseMerger`/`DatabaseSnapshot` against the real bundled SQLite | ✗ local only |
| `androidApp/src/androidTest/` | `:androidApp:connectedGithubDebugAndroidTest` | Compose UI (long-press gesture, search bar) | ✓ every push |

Both need a connected device or a running emulator — see [setup.md](setup.md) for how to create an
AVD (`<name>` below):

```bash
$ANDROID_HOME/emulator/emulator -avd <name> -no-snapshot -no-boot-anim &
./gradlew :composeApp:connectedAndroidDeviceTest
```

(the task name comes from AGP 9's `com.android.kotlin.multiplatform.library` plugin's own
`withDeviceTestBuilder` DSL — run `./gradlew :composeApp:tasks --all | grep -i device` if it's
renamed in a future AGP release). It is not wired into `ci.yml` — the desktop UI tests already run
under `xvfb` there, but an Android emulator is a separate CI concern this project hasn't taken on
yet — so it only runs locally today.

`androidApp`'s own instrumented suite (Compose UI gesture tests, see `androidApp/src/androidTest/`
above) uses the regular `com.android.application` task naming instead — flavor-qualified now that
`androidApp` splits into `github`/`play` product flavors (see `build.md`'s "Android (APK / AAB)"):

```bash
$ANDROID_HOME/emulator/emulator -avd <name> -no-snapshot -no-boot-anim &
./gradlew :androidApp:connectedGithubDebugAndroidTest
```

Only `github` runs in CI (and is the one to run locally too) — the suite doesn't exercise anything
flavor-specific (the two flavors differ only in the `REQUEST_INSTALL_PACKAGES` manifest permission).
There is no `connectedPlayDebugAndroidTest` to fall back to either way: `androidApp/build.gradle.kts`
disables the `playDebug` variant entirely (nothing debug-build-specific to exercise there that
`githubDebug` doesn't already cover — see that file's own comment), so `connectedAndroidTest` (no
flavor) now runs only `githubDebug`, the sole remaining debug variant.

That the flavors differ *only* in that permission is itself checked on every push, by `ci.yml`'s
"Verify REQUEST_INSTALL_PACKAGES is github-only (Linux)" step: it runs
`:androidApp:processGithubReleaseManifest`/`processPlayReleaseManifest` and greps the two **merged**
manifests, so the assertion holds against what AGP actually produces rather than against the flavor
source sets. Merged output is what matters here — a transitive library manifest could reintroduce
the permission into `play` without either flavor's own `AndroidManifest.xml` changing, and that is
exactly the case Google Play would reject.

Like `androidDeviceTest`, this is not part of `./gradlew build` — AGP's `build` lifecycle for an
application module only runs `lintAnalyzeDebugAndroidTest` (static analysis) on the `androidTest`
source set, not `compileDebugAndroidTestKotlin`/`assembleDebugAndroidTest`. A dedicated
`android-instrumented-test` job in `.github/workflows/ci.yml` runs this suite on every push.

Project-wide, this is on top of the two Android suites above: `commonTest`/`desktopTest` (run via
`./gradlew :composeApp:desktopTest` above) covers parser, fetcher redirect/304/404/410/timeout/discovery, OPML, Dropbox storage/auth, PKCE, OAuth loopback server, merge (last-write-wins / OR merge / collision guard / FK guard), schema, local settings, article upsert, URL resolver, datetime parser, Result, Repository layer (Article/Feed/Tag/Settings), CloudSession, NotificationCenter, IdGenerator, SyncRepository, ViewModel layer (Home/Settings/Setup/NotificationCenter, including `SettingsViewModel`'s OPML
import/export paths — the built document/read file round-tripping through the picked path, the
localized request fields reaching a `FakeFileSelector`, cancellation, and the document
build/write/import work actually running on the injected dispatcher rather than the EDT), the
Linux/macOS/Windows file-dialog backend split (`FilePickerTest` for `defaultFilePickerBackend`'s OS
selection, the extension predicate agreeing with `FileNameExtensionFilter` including accepting
directories, the overwrite-confirmation resolution, and dialog-owner selection), the feed-list
drag-and-drop rewrite (`parseFeedListDragSourceKey` in `HomeCommonTest.kt` for the pure key-parsing logic; `FeedListDragTest.kt` for the real end-to-end gesture via `performMouseInput`/`performKeyInput` against actual rendered composables — dragging a feed above another and asserting the persisted order, the sub-threshold-move-still-selects case, dropping onto a folder header / a tag row, a right-click landing mid-drag not opening the context menu or aborting the drag, the ghost overlay's appear/disappear lifecycle, Escape-cancel, folder-onto-folder reordering, and a drag pushed out past the pane's horizontal bounds never resolving to a valid target or applying a drop even when it lines up with a row's height), the feed list's in-row rename editor (`InlineRenameValidationTest` in `commonTest` for the shared blank-is-not-an-error validation rule and `toInlineEditTarget` in `HomeCommonTest.kt`; `FeedListInlineRenameTest.kt` for the real end-to-end flow against rendered composables — F2 opening the editor and Enter committing, Escape and the "×" icon cancelling, blur committing a valid name, a duplicate folder name blocking Enter and reverting silently on blur, a blank folder name simply not committing, a blank feed title resetting `custom_title` with the feed's own title shown as the placeholder, renaming a tag leaving its color alone, the tag color dot's popover applying a color immediately both outside and during a rename, and the Feed-menu `RenameFeed` command opening the editor for the current selection), the metadata lines that pair a name with a timestamp (`ArticleRowMetadataTest`: a long feed title ellipsizes without eating the article card's timestamp, which stays at its full width pinned to the trailing edge; `ArticleMetaTextTest`: `articleMetaText`'s join of author and timestamp and its dropping of a null or blank author so no leading separator dangles), the article reader's native WebView (`ArticleWebViewHtmlTest` for `extractLinks` and the three document builders `wrapArticleHtml`/`articleNoContentHtml`/`articlePlaceholderHtml` — including every document sharing one `<style>` block and painting the theme's colors/font scale so none of them can flash a default page; `ArticleDetailLoadGuardTest` for `shouldLoadArticleHtml`'s reload decision, keyed on the rendered document string rather than an article id since the placeholder/no-content states share the WebView with real articles; `ArticleDetailPaneTest` for the reader staying composed and its measured bounds staying fixed across a selection change — the regression guard for the whole-window flicker described in `known-issues.md` — plus the toolbar's disabled-rather-than-hidden treatment when nothing is selected or the selected article has no URL), AppFont (Pango font-description parsing for the Linux UI font), custom URI scheme registration (`UriSchemeRegistration`'s per-OS dispatch and packaged-launcher gate, `LinuxUriSchemeRegistrar`'s desktop-entry generation including the `%u` field code, non-destructive `mimeapps.list` merge, and idempotency), the `.opml` file association (`LaunchArg`'s classification of an OAuth URI vs. an `.opml` path, `registerWindowsOpmlAssociation`'s ProgID registry writes, `LinuxOpmlAssociationRegistrar`'s desktop-entry generation including the `%f` field code, its shared-mime-info package XML, and idempotency, and `OpmlImporter`'s added/failed counting and folder/tag reconciliation), FTS (FtsManager/FtsSearch, including `indexMissing` incremental insert / non-destructive behavior, `rebuildIndex` requiring table existence, sync upload excluding `articles_fts` via `VACUUM INTO` snapshot while preserving `user_version`), the Linux SNI tray (`TrayPixmapTest` for the big-endian ARGB32 / RGBA encoders and alpha preservation, `TrayMenuModelTest` for the dbusmenu layout, `TrayMenuRevisionTest` for revision / `AboutToShow` / event dispatch, `DBusSignatureTest` for the exported D-Bus signatures), the Windows tray menu (`WindowsTrayMenuTest` for the built Swing widgets, their labels, their callbacks, and the forced-heavyweight popup — the AWT widgets it replaces overlap their own labels on a HiDPI Windows desktop), the KDE Global Menu / AppMenu (`AppMenuTreeTest` for the shared menu-tree model shape / `isMacOs` omissions / enabled-checked mirroring / the optional "Show Menu Bar" item, `AppMenuLayoutBuilderTest` for the recursive `com.canonical.dbusmenu` layout / property filtering / checkbox mapping / pre-order id stability, `AppMenuRevisionTest` for revision bump / `AboutToShow` / click dispatch / no-dedup, `AppMenuSignatureTest` for the `com.canonical.AppMenu.Registrar` wire signatures, `MenuBarVisibilityTest` for the AWT key-code map / shortcut→node matcher / visibility persistence),
`SqliteConnectionPropertiesTest` (the production connection properties really reach every
connection — foreign keys enforced, `busy_timeout` applied — which a one-off `PRAGMA` does not,
since the JVM driver opens a connection per statement), `FormatTimestampTest` (pins
`formatTimestamp`'s exact output, which the other timestamp assertions cannot because they derive
their expected value from the function itself), `LazyNativePopupTest` (nothing native is built until
the first right-click; not observable through a Compose UI test, where `LocalNativeWindow` is null),
`WindowGeometryTest` (dialog window geometry: owner-centering and the screen-bounds clamp, the
auto-fit arithmetic `fitWindowSize`/`sizeMatches`, and `nextDialogFit`'s drift-correction state
machine — including the regression case where a size applied behind Compose's back *after* the fit
had settled must still be corrected, plus the per-target attempt cap that keeps a window manager
that refuses the geometry from spinning the guard forever, and the `presentable` flag that keeps a
dialog invisible until its fit has landed — including its release once that cap is spent, so a window
manager refusing the geometry can never leave a dialog invisible),
cloud-data corruption/incompatibility recovery (`SyncRepositoryTest.kt`/`SyncMergerTest.kt`: constraint-violating cloud data — a `feeds` row set with a UNIQUE-`url` duplicate or a NOT-NULL-violating NULL only the cloud DB's own laxer schema allowed — classified as `CloudDataIncompatibleException` alongside corrupt-file and foreign-schema cases, `SyncMergerTest.mergeDoesNotClassifyABrokenLocalSchemaAsCloudDataIncompatible` guarding the inverse, and `SyncRepositoryTest.postMergeIndexFailureIsNotClassifiedAsCloudDataIncompatible` guarding that a post-commit `FtsManager.indexMissing()` failure — which shares the same ambiguous SQLite error code as a broken cloud schema — is never misclassified as the cloud's fault; `core/SqliteFileTest.kt` for the downloaded-bytes SQLite-header rejection symmetric with the upload-side check), the cloud-data reset now archiving rather than deleting (`core/CloudBackupPathTest.kt` for the deterministic UTC-formatted backup path; `CloudStorage.rename` exercised per provider in `DropboxStorageTest.kt`/`GoogleDriveStorageTest.kt`/`OneDriveStorageTest.kt` including the destination-conflict and absent-source cases; `SyncRepositoryTest.kt`'s `resetCloudData*` tests for the rename-then-recreate flow and its delete fallback), the file-streamed cloud transfers (`CloudFileTransferTest.kt`: a response body written to its destination verbatim across several read chunks, a shorter payload replacing rather than appending to an existing destination file, and `FileUploadContent` streaming a file — optionally wrapped in the prefix/suffix that make Drive's `multipart/related` envelope possible — while reporting the right `contentLength`; `ContentDigestTest.kt` for the chunked SHA-256 the upload skip is keyed on, including a change in the final chunk still registering and a missing file yielding no digest rather than a false match; `SqliteFileTest.kt` for the path-based header check deciding from the first 16 bytes of a file far larger than any buffer), the unchanged-transfer skip (`SyncRepositoryTest.kt`: a second sync with nothing changed on either side transferring zero payload bytes and issuing only its one metadata request, a local edit made after such a skip still being uploaded, a remote change still being downloaded and merged, the revision recorded from the upload's own response so a device never re-downloads its own write, `sync_state` being absent from the uploaded snapshot so its digest cannot drift on its own, `clearSyncFailureState()` not being undone by a sync that was already in flight — one test per half of the shared-mutex guarantee, since a *successful* gated sync is what rewrites the revision/digest markers while only a *failing* one rewrites `lastSyncError` — and the compressed upload / legacy-fallback split (a legacy-only cloud being merged then migrated to `.gz` via create-only rather than a rev-guarded update, the legacy file surviving migration byte-for-byte, a migrated device never reading the legacy file again even when it is left corrupted, a corrupt legacy file during migration and an invalid (non-gzip) `.gz` payload both classified as `CloudDataIncompatibleException`, and a reset renaming/recreating only `.gz` while leaving the legacy file untouched)), and the automatic-sync suspension gate (`SyncRepositoryTest.kt`: an `AUTOMATIC`-triggered sync skipped while `autoSyncSuspended`, a `MANUAL` one never gated, `scheduleSync()` likewise suppressed, and the gate clearing on a successful sync/reset/`clearSyncFailureState()` — `SchemaVersionException` deliberately never triggers the gate),
the in-app update pipeline (`UpdateCheckerTest.kt`: `assets[]`/`body` parsing into `asset`/`releaseNotes`, a non-`sha256` or malformed `digest` yielding no asset, an asset whose `state` isn't `"uploaded"` excluded; `UpdateAssetSelectorTest.kt`: per-`InstallKind` asset-suffix matching, `.aab` never selected regardless of what a release ships; `UpdateInstallPolicyTest.kt`: `InstallLocation` × asset → `UpdatePlan`, and `canInstallAndroidApkUpdate`'s plan-kind/OS-consent gating — the one piece of `AndroidUpdateInstaller`'s decision pulled out as a pure function specifically so it's exercisable here, since `androidMain` itself has no JVM-testable unit-test source set (see "Known uncovered areas" below); `UpdateDownloaderTest.kt`: the host allowlist (leading-dot-anchored suffix match, rejecting a bare-IP or lookalike host), manual redirect-following capped at `MAX_REDIRECTS`, a digest or size mismatch leaving neither the `.part` file nor the final one behind, and progress emission monotonically reaching `bytesTotal`; `UpdateStateMachineTest.kt`: `Ready` surviving a `UpToDate`/same-version re-check but not a newer one, and never being interrupted by `Downloading`/`Verifying`/`Installing`; `UpdateRepositoryTest.kt`: `startDownload()` called twice starting exactly one download, `cancelDownload()` removing the `.part` file and reverting to `Available` rather than `Failed`, the sweep protecting an in-progress `.part` and the current `Ready` file while removing everything else, and a newer version's check deleting a superseded `Ready` version's directory; `ReleaseNotesTextTest.kt`), the desktop self-replace/installer scripts (`UpdateScriptWriterTest.kt`: the generated script text itself, per template — the retreat-before-delete ordering, the rollback branch on a failed placement, and the health check gating the old copy's removal; `DesktopUpdateInstallerTest.kt`: `canInstall`'s per-`InstallKind`/asset-kind gating, the exact command line launched for each of macOS/Windows/Linux self-replace and the Windows MSI path via a fake `ProcessLauncher` that never actually runs one, a version-mismatched or executable-less extracted bundle failing before the launcher is ever called, an `ArchiveExtractor` that rejects the archive failing before it too (with its own reason carried through to the caller), and a stale partial `extracted/` tree being cleared before extraction starts; `ZipExtractorTest.kt`: zip-slip rejection, the `maxBytes` limit, restoring only the listed entries' executable bit, and the same two guards again through `validate`, which must additionally not create the destination directory it only resolves against (the entry-count limit itself — `MAX_ZIP_ENTRIES` in `ZipExtractor.kt`, 100,000 — is unverified for either function: no practical fixture exists for it, since exercising it means actually building and extracting a hundred-thousand-plus-entry ZIP in a unit test); `FileSystemExtrasTest.kt`/`InstallLocationDesktopTest.kt` extended for `setExecutable`/`isDirectoryWritable`/`move`, `copyTree` reproducing a symlink (file or directory) as a link rather than dereferencing it, and the per-OS `InstallLocation` detection),
etc. `SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` failures specifically indicate regression in DB schema / merge SQL / sync orchestration and require extra attention.

Known uncovered areas: `SettingsViewModel.exportOpml`/`importOpml` now have a test seam
(`FileSelector`, faked as `FakeFileSelector`) and are covered — what remains uncovered is the native
dialog actually appearing (a real `JFileChooser`/`FileDialog` needs a display and a human, so it
stays a manual check below) and `FilePicker.desktop.kt`'s `resolveDialogOwner()` against real
windows (only the pure `chooseDialogOwner` selection it delegates to is tested). The browser-launch → callback-wait → code-exchange portion of `OAuthConnectFlow.connect()` (depends on actual `BrowserOpener`/`LoopbackRedirectTransport` I/O and cannot be mocked without a seam; only the immediate-error branch for empty App Key is covered in `OAuthConnectFlowTest`), `DatabaseDriverFactory.create()` itself (it references `AppDirs.appDataDir()` and cannot be pointed at a test directory) — though the part that matters, the connection configuration, is now covered: it is extracted as `sqliteConnectionProperties()` and exercised against a real file DB by `SqliteConnectionPropertiesTest`, and `inMemoryDb()`/`fileDb()` build their drivers with it too. For dialog auto-sizing, `WindowGeometryTest` covers the whole decision — what size to request and whether to re-apply it — but not the *application* of that size to a real `DialogWindow` (that needs an OS window with a native peer, so it stays a manual check below). The feed/folder reorder gesture (`ui/home/FeedListDragController.kt`/`FeedListDragGestures.kt`) is now a hand-rolled Compose-native drag rather than OS-level DnD, specifically so it could be exercised directly: `FeedListDragTest.kt` drives it end to end via `performMouseInput`/`performKeyInput` (drag-and-reorder, threshold gating, folder/tag drop, right-click-during-drag, ghost lifecycle, Escape-cancel). The reordering calculation logic itself (`reorderIds`, a top-level function in `ReorderUtil.kt`) and its consumers `FeedRepository.moveFeed`/`FolderRepository.reorderFolders` are tested normally. On the Linux SNI tray, `SniConnection` (connecting, claiming the bus name, exporting, registering, re-registering, closing) needs a live session bus and a running `org.kde.StatusNotifierWatcher`, which CI runners do not have; likewise the actual delivery of `NewIcon`/`NewToolTip`/`LayoutUpdated` (the *decision* to emit them is covered), the `NameOwnerChanged` re-registration path, host-initiated `Activate`/`Event` arriving through dbus-java's worker threads, `LinuxNotifier.notify` reaching a real daemon, and the `LinuxTray` composable wiring. Whether the icon actually renders transparently on a panel is inherently a visual check. For the KDE Global Menu the same applies: `X11WindowId.findOwnWindowId()` (needs a real X server + a mapped window with `_NET_WM_PID`), the real `AppMenuConnection` connect/detect/`RegisterWindow`/reregister/`close` round trip, KWin/Plasma actually writing `_KDE_NET_WM_APPMENU_*` and a panel widget / titlebar button rendering the menu, the `startMinimized` XID timing/retry path, whether Compose's own `MenuBar` shortcut handling truly depends on frame attachment (verified manually), and the live `MenuShortcutDispatcher` Ctrl+M/N/W/,/Q/R interception through `KeyboardFocusManager` are all uncovered (only the pure matcher it delegates to is tested). On Android, everything above the two instrumented suites described in Execution is likewise uncovered: `WorkManager`'s actual periodic-job scheduling/execution (only the pure schedule mapping in `BackgroundRefreshSchedule.kt` is tested), real notification posting through `NotificationManagerCompat`, the Storage Access Framework file picker, Keystore-backed token storage, and `AndroidUpdateInstaller`'s `PackageInstaller` session/`BroadcastReceiver`/`canRequestPackageInstalls()` handling (only the pure plan/consent decision it delegates to, `canInstallAndroidApkUpdate`, is tested — see the in-app update pipeline above). Likewise on desktop, the actual execution of a self-replace/`msiexec` script (`UpdateScriptWriter`'s output) — its own generated text is asserted directly and `DesktopUpdateInstaller` never runs one in a test (see the fake `ProcessLauncher` above) — is a manual check only; see "In-App Update" below. Two more pieces of that path are out of reach of a *unit* test and are covered elsewhere instead. `DittoArchiveExtractor` actually running `ditto` is exercised by `ArchiveExtractorTest.kt`'s `isMacOs`-gated cases (`macos-latest` is in the CI matrix, so they really run; the Linux/Windows runners have no `ditto`, and the installer's own tests inject `InProcessArchiveExtractor` by default). A real signed `.app` surviving the `zip -ry` → `ditto` → `codesign --verify --strict --deep` round trip needs macOS *and* a jpackage bundle, which no test source set has — so `ci.yml`'s "Verify packaging (macOS)" step performs exactly that round trip against the freshly built app image, asserting the symlink count is unchanged and the extracted bundle still verifies. Its companion is `createDistributable`'s own `verifyMacOsBundleSeal`/signature-property guard, which fails the build if the *pre*-zip bundle is already broken (see [build.md](build.md)); between them, neither half of the original defect can reach a release unnoticed. `FileSystemExtras.move`'s cross-volume fallback is likewise unreachable from a test (a second filesystem cannot be provoked), which is why the link-preserving copy it delegates to is split out as `copyTree` and tested directly.

## Manual Confirmation (UI)

Launch with `./gradlew :composeApp:run` and visually verify 3-pane UI, theme switching, feed addition, and search. `FeedListDragTest.kt` now covers the drag mechanics end to end (reorder, threshold gating, folder/tag drop, right-click guard, ghost lifecycle, Escape), but the actual pixel rendering — colors, animation smoothness, the ghost's appearance over real content — is inherently visual, so confirm the following by hand too:

Also relaunch once with the English locale forced —
`JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" ./gradlew :composeApp:run`
(or switch the OS display language) — and confirm labels render in English throughout, including
the plural strings at a count of 1 and at 2+ (Add Feed's article-count preview and success/failure
text, the OPML import result, the tray's new-articles notification), and that Settings' "Website"
link opens the `/en/` page. `StringsXmlParityTest` catches a forgotten translation at the resource
level, but only this manual pass catches wording that reads oddly in context.

- Pressing down on a feed row / folder header and moving less than the
  drag-start threshold (`Modifier.feedListReorderDrag` in
  `FeedListDragGestures.kt`) does not start a drag — the row still
  registers a plain click (selection) normally, with no visible drag ghost or
  drop-target highlight anywhere in the list. Moving past the threshold starts
  the drag exactly as before.
- A drag now shows an in-app, Compose-drawn chip ghost (icon + title) that follows the pointer,
  identical on macOS/Windows/Linux — **Linux gets a ghost for the first time** (X11 AWT never
  supported one at all, even before Wayland's own cursor limitation, both now moot since the drag no
  longer touches OS-level DnD; see the "Linux Wayland/XWayland" entry in `docs/known-issues.md`).
  Confirm the chip is legible over both light and dark content, and that it never shows the OS's own
  forbidden/no-drop cursor — the pointer just stays the ordinary arrow throughout, on every platform.
- The chip is semi-transparent (`DRAG_GHOST_ALPHA` in `FeedListDragController.kt`) so the row/highlight
  underneath it stays visible rather than being fully covered. Over a position that would actually
  accept the drop it tints neutral (the same look as before); over blank space, a section header, or
  (dragging a folder) the folder hovering itself, it tints toward `error`/`errorContainer` instead —
  confirm this switch is visually obvious in both light and dark theme, and that it tracks the pointer
  immediately as it crosses in and out of valid rows (no lag or stale tinting).
- Drag a feed or folder out past the feed pane's right edge, over the article list — the ghost must
  tint invalid (`error`) regardless of vertical position, even if it happens to line up with a row's
  height, and releasing there must never apply a move/reorder/tag-attach (`isWithinHost` in
  `FeedListDragController.kt`; regression-tested by `FeedListDragTest.kt`'s
  `draggingOutsideTheHostHorizontallyNeverAppliesADrop` — row hit-testing is vertical-only, so this
  horizontal check is what actually excludes a sibling pane, not the highlighting happening to line up).
- Right-click mid-drag opens no context menu and doesn't abort the drag; the drag still completes
  normally on release.
- Dragging the feed pane's vertical scrollbar thumb scrolls the list as usual and never starts a
  feed/folder drag, even after moving past the drag threshold.
- Pressing Escape mid-drag cancels it with no reorder applied and the ghost disappears immediately;
  pressing Escape with no drag in progress behaves as before (not swallowed by the drag handling).
- Alt-Tabbing (or otherwise losing window focus) mid-drag cancels it the same way.
- Drag folders to reorder; order persists after app restart.
- Drag feeds inside a folder to reorder; order persists after restart.
- Reorder feeds inside the "No Folder" group.
- Drop a feed onto another folder (and onto the "No Folder" group) at any position (line position):
  the header tints `secondaryContainer` and gains a `secondary`-colored (green) border while hovered,
  folder move and positioning are applied simultaneously, and no "+" badge appears (a move has no
  "adding" semantics).
- "Move to folder" dialog moves the feed to the end of the destination group.
- Drag a feed onto a tag row: the row highlights in a *different* tone from a folder drop-target
  (`tertiaryContainer` vs. `secondaryContainer`), gains a `tertiary`-colored (blue) border — vs. the
  folder's `secondary`-colored (green) border — and its color dot is replaced by a filled "+" badge
  while hovered — distinct at a glance, including in dark mode, from both the folder "move" highlight
  and the drag ghost's own neutral chip. Dropping attaches the tag without moving the feed out of its
  folder. Re-dropping an already-attached feed on the same tag changes nothing. Dragging over a feed
  listed under an expanded tag, or over blank space, shows no highlight anywhere and the pointer stays
  the ordinary arrow — there is no special "can't drop here" cursor now that the drag isn't OS-level.
- Expand a tag with its chevron: its attached feeds are listed beneath it (a feed with several tags
  appears once per expanded tag, plus once under its folder — expected, not a duplicate bug). The
  expanded/collapsed state persists after restart, tags default to collapsed, and deleting a tag
  leaves no stale expansion behind.
- Right-click a feed listed under an expanded tag → "タグから外す" detaches it from that tag only
  (its folder placement and other tags are untouched); dragging it out of the tag list onto a folder
  or another tag still works.
- With a subscription list long enough to scroll, hold a dragged feed (and separately, a dragged
  folder) near the top and the bottom edge of the feed list: the list auto-scrolls in that
  direction, faster the closer the pointer is to the edge, stops in the middle dead zone, and stops
  at either end of the list without error. The drop lands where the insertion line says it will
  after the auto-scroll, and the list stops scrolling the instant the drag is dropped or released —
  it must not keep drifting for a further fraction of a second. The pane's own scrollbar and the
  drag ghost keep behaving normally.
  Specifically confirm the scroll stays smooth (no stutter or stop-start) while crossing the
  "Folders"/"Tags" section headers, the divider above "Tags", and the top sidebar rows — not just
  while scrolling through folder/feed rows.
- With the "Folders"/"Tags" section headers now pinned while scrolling (VSCode-Explorer-style),
  confirm each header visually covers the rows scrolling underneath it with no flicker/see-through,
  and that dragging a feed or folder so the pointer sits over a *pinned* header resolves the
  hover/drop target to the header's own row, not any row hidden behind it — then confirm the pinned
  header correctly hands off to the next section's header the moment that header reaches the top.
- **Drop onto a row revealed only by auto-scroll, without leaving the window**: start dragging a feed
  from well above the tags section, hold near the bottom edge until a tag scrolls into view, then drop
  directly on it — the tag highlights and the drop succeeds on the first try. Repeat dropping into a
  folder that was off-screen at drag-start. (This is the scenario the pane's single centralized drop
  target exists for — earlier per-row targets could only accept a drop from a row that already existed
  when the drag began, so anything auto-scroll revealed afterward silently rejected drops until the
  drag left and re-entered the window.)
- Hold a dragged feed over a *collapsed* folder's header for about a second: the folder expands by
  itself (Finder/Explorer-style spring loading), its feeds become drop targets, and the folder
  **stays** expanded after the drop — the same persisted state as a click on its chevron. Merely
  passing over the header on the way elsewhere does not expand it, and dragging a *folder* over
  another collapsed folder's header (a reorder gesture) never expands it either.
- **(Linux, X11 and Wayland)** The drag ghost and drop behavior should now be indistinguishable
  between the two session types — repeat the reorder/folder-drop/tag-drop checks above on both a
  Plasma X11 session and a Plasma Wayland (XWayland) session and confirm they behave identically
  (see the "Linux Wayland/XWayland" entry in `docs/known-issues.md` for the OS-level DnD bugs this
  no longer has any exposure to).

The parallel feed refresh's core concurrency (overlapping fetches + complete per-feed writes) is covered automatically by `refreshAllFetchesFeedsConcurrentlyAndAppliesEveryWrite`, and the no-revert guarantee by `refreshAllDoesNotRevertConcurrentUnsubscribe` / `refreshAllDoesNotRevertConcurrentReorder`. The genuinely visual / end-to-end parts still need eyeballs, so with a multi-feed subscription visually confirm:

- "Refresh all" over many feeds: articles still appear incrementally (feed by feed) rather than all at once at the end, and the final list order is stable.
- Feed error / 301·308 URL-change / 410 Gone notifications still fire, and missing favicons still fill in after a refresh.

The unchanged-transfer skip (see "Skipping Unchanged Transfers" in [sync-architecture.md](sync-architecture.md)) is covered end to end by `SyncRepositoryTest`, but only against the `CloudStorage` fake — that each real provider actually returns a usable revision from its metadata call *and* from its own write response is not something a MockEngine test can prove. Confirm by hand, once per connected provider (Dropbox / Google Drive / OneDrive):

- Sync, then sync again immediately with nothing changed on either side: the second sync completes without transferring the database. The app log shows `Sync: nothing changed locally or remotely; skipping transfer`, and the provider's own activity/version history shows no new revision.
- Repeat that idle sync several times (or just leave the app running across a few background intervals) and confirm it never starts uploading again on its own — a provider whose write response omitted the revision would silently re-download and re-upload every cycle instead.
- Toggle one article's read state, wait out the debounce: exactly one upload happens and no download (the cloud revision is still the one this device wrote).
- Change something on a *second* device, then sync on the first: the download happens and the change appears — the skip must not hide another device's writes.
- Reconnect the account (disconnect → connect) and confirm the first sync afterwards still works: the stored revision/digest belong to the previous connection.

Cloud-data corruption recovery needs a real cloud connection end to end, so confirm by hand, once per connected provider (Dropbox / Google Drive / OneDrive):

- Replace the cloud `keryx.db.gz` with an arbitrary non-gzip file, then sync: the bell notification offers "同期データをリセット" (`ResetCloudData`). Running it leaves a `keryx-YYYYMMDD-HHMMSS.db.gz.bak` archive in the provider's app folder alongside a freshly re-created `keryx.db.gz` — the old file is not simply deleted.
- Download `keryx.db.gz`, decompress it, then relax its `feeds` table's `UNIQUE(url)` constraint (SQLite has no `ALTER TABLE ... DROP CONSTRAINT`; recreate the table without it and copy the existing rows across) so a duplicate `url` can actually be inserted, then add a `feeds` row whose `url` duplicates an existing one (only reachable this way — the app's own schema, and the app itself, never produce this), re-compress, re-upload, then sync: the same `ResetCloudData` notification appears — confirms constraint-violating cloud data is treated the same as outright corruption.
- **Legacy fallback** (see "Compressed Upload / Legacy Fallback" in [sync-architecture.md](sync-architecture.md)): with a provider connected, manually delete `keryx.db.gz` from the app folder if present and upload a plain (uncompressed) `keryx.db` in its place (e.g. one downloaded from a fresh `createFresh` before this feature, or by decompressing a `.gz` by hand) — this simulates a cloud this device has never synced to since compression was added. Sync: the app must download and merge the plain file, then create a fresh `keryx.db.gz` (visible in the provider's activity log as a create, not an update) — while the plain `keryx.db` is left in the folder completely untouched. Sync again with nothing else changed: only the one metadata check for `keryx.db.gz` happens (no payload download or upload), confirming the plain file is never read again.
- With the cloud DB still unusable, trigger several automatic syncs (toggle read/star repeatedly, or wait for the background interval) and confirm no further download happens (no network activity, no repeated/duplicate notification) until the data is reset — then confirm a manual "sync now" *does* still attempt a real sync (and fails again) even while automatic syncs are suppressed.
- After a successful reset, confirm automatic syncing resumes (a read/star toggle triggers a real sync again).

The article reader's native WebView (`ui/home/ArticleDetailPane.kt`) is a heavyweight AWT surface
that Compose UI tests cannot host at all, so its actual on-screen behavior — beyond the bounds/
enabled-state checks `ArticleDetailPaneTest` covers — needs manual confirmation. See
`known-issues.md`'s "Selecting an article after none was selected flickered the whole window" for
why the reader is always mounted:

- With no article selected, click an article, then click back to an empty selection (or a feed
  with no articles), repeatedly, alternating with articles that do and don't have a body — no part
  of the window (feed list, article list, window frame) flickers, in both light and dark theme.
- With nothing selected, the placeholder text renders centered on the pane's theme background with
  no default white flash, and the toolbar above it (star / mark unread / copy URL / open in
  browser) is visible but disabled; selecting an article with a URL enables all four, while an
  article with a blank URL leaves the copy/open-in-browser pair visible but disabled rather than
  hiding them. The toolbar's position and height never change between any of these states.
- Toggling light/dark theme (and the font-size setting) while an article is open re-renders the
  reader in the new theme/scale immediately (scroll resets to the top — expected).
- (Windows) On startup, the reader renders in its correct pane position (no stray blank/misplaced
  rectangle) and clicking anywhere in the window never freezes the app — the regression check for
  the WebView2 `dataDirectory` Access Denied bug in `known-issues.md`. Running with
  `WRYWEBVIEW_LOG=1` set should show no `WebViewException` in the console.

Native context menus (`nativeContextMenu`, backed by a real `JPopupMenu` on Windows/Linux and
`java.awt.PopupMenu` on macOS — not a Compose-drawn popup) cannot be exercised by Compose
UI tests, so visually confirm. Note the widgets are now built on the **first right-click** rather
than at composition (`LazyNativePopup`), so these checks are the only verification that the native
peer creation still works from inside the click's own call stack:

- Right-clicking a feed row, a folder header, and an article row shows the native menu with the
  correct actions.
- The feed row's "Tags" submenu shows a checkmark on every currently attached tag, and its
  "Move to folder" submenu shows a checkmark on the feed's current folder; toggling either updates
  the checkmark immediately.
- Right-clicking a feed row (both under its folder and under an expanded tag) shows "Copy feed
  URL", "Copy site URL", and "Open site", grouped with a separator on each side (matching the app
  menu bar's Feed menu ordering — see below), and each does what it says. For a feed with no
  discovered site URL, the latter two render grayed out (disabled) rather than being omitted, and
  re-enable once a refresh discovers one — without needing to reopen the menu.
- The separators in a feed row's menu render as real native dividers on every platform (a
  dash-labelled item on macOS, a `JPopupMenu.Separator` on Windows/Linux) — not as a visible menu
  item — and stay in place across a resync (e.g. toggling a tag) without the menu rebuilding.
- Right-clicking an article row with no usable URL still shows "Copy URL" and "Open in Browser"
  grayed out (disabled) rather than omitting them; an article with a URL shows both enabled.
- Opening any of these menus while the article reader's WebView is visible renders the menu above
  the WebView, not behind it.
- (Linux) After switching the in-app theme (light ↔ dark) with no restart: the menu bar and an
  open dialog's button row restyle immediately, and a context menu opened afterward picks up the
  new theme.

(Android) `nativeContextMenu`'s Android `actual` is a long-press-triggered Material 3
`DropdownMenu`, covered by `NativeMenuAndroidGestureTest.kt`'s instrumented tests for the gesture
policy itself (long-press opens without selecting, a short tap or a scroll-sized move does not open
it — see `androidApp/src/androidTest/` above). What those tests cannot cover end to end against the
real app UI, so confirm manually on a device or emulator:

- Long-pressing an article row shows the menu, and the article is **not** marked read and the pane
  does not advance (Single/Dual layout) — unlike a plain tap on the same row.
- Pressing down on an article row and slowly dragging it vertically before releasing scrolls the
  list as a normal drag; the menu does not open and the row is not activated.
- Long-pressing a feed row, a folder header, and a tag row shows the menu with the correct actions,
  and the row's selection does not change as a side effect of the long-press itself.

(Android, phone width) Search — `KeryxSearchBarAndroidTest.kt` covers the two `actual`s'
semantics/text-input/font-scale behavior in isolation (see `androidApp/src/androidTest/` above);
confirm the full navigation flow manually on a device or emulator:

- On launch, a saved `HomePane.ArticleDetail` comes back as the article list (depth 2), not the
  last-read article — `initialPaneFor`'s clamp. A saved `HomePane.FeedList` is restored as-is
  (depth 1); the article list is only what a session with nothing saved falls back to.
- Tapping the article list's own search icon, or the feed list's collapsed search bar, opens the
  search screen with the keyboard already up and the field focused.
- Typing 2+ characters shows results on the same screen, below the field — no pane change needed.
- With the keyboard still open, the results list scrolls all the way to its last item without the
  keyboard covering it.
- Opening a result, then going back, returns to the search screen with the query and results still
  in place (no keyboard auto-reopening).
- From an article list already showing some other feed/tag/folder, tapping its own search icon and
  then going back returns to **that same article list**, with the original scope selected again —
  not to the feed list (`homeBackAction`'s `ExitSearch`, the fix for the bug where the article
  list's search icon didn't advance the stack but going back still popped a pane).
- From the feed list's collapsed search bar, going back returns to the feed list with the scope
  restored to what it was before; the query itself survives on the collapsed bar, and tapping it
  again reopens the search screen with the same results.
- Rotating to a tablet-width landscape (`PaneLayout.Dual`) mid-session does not eject the user from
  whatever they were reading, and the first back press from the article list there is not silently
  swallowed (`canNavigateBack`'s fix). Entering search there and pressing back exits the search
  scope the same way it does at phone width — the back arrow stays enabled while searching even
  though `PaneLayout.Dual`'s depth 1→2 step is otherwise a no-op for back navigation.
- At a tablet-width landscape wide enough to reach `PaneLayout.Triple`, the layout matches desktop
  exactly — the search field is back in the feed list sidebar, and the article list carries no
  search bar of its own. Back navigation stays disabled at every depth there, same as desktop.
- Opening an article from partway down the article list and then going back returns to the list at
  the same scroll position, with **no visible scroll animation** — at phone width (where the pane is
  genuinely unmounted) and at tablet-width landscape alike (where the sliding window keeps it on
  screen). The same holds for the feed list and for a search results list.
- With an article open, using a notification's "show feed" action to switch to a different feed and
  then going back opens that feed's list at the **top**, not at the previous feed's scroll position.
- Scroll the article list down, open Search from its own icon, then close Search without picking
  anything — the list returns to the same scroll position, not the top. Applies at every
  `PaneLayout`, including desktop's `Triple` (this round trip stays inside `ArticleListPane`, not
  `NarrowPaneRow`, so it isn't narrow-layout-specific).
- With "unread only" on, select an unread article (it stays visible, shown read, while browsing) and
  then open and close Search without picking anything — that article must still be there, still
  shown read, not silently dropped from the list. The selection itself, and the article detail pane,
  must also come back exactly as they were before Search opened.
- With an article open in Search results (not the same one that was selected beforehand), mark it
  unread from its own row and then close Search — it must show as unread, not snap back to read.
- With an article selected (and therefore pinned read under "unread only"), have another device sync
  a "mark unread" for that same article — it must switch to showing unread reactively, without
  needing a filter switch or app restart. The same for a re-star synced in while browsing under
  "unstarred" browsing of the Starred filter.

**Display scaling.** Every check above must also be run at a **non-100% display scale**, on Windows
in particular — 200% first, then 150%. The AWT menu backend was mispositioning menus and painting
their labels on top of each other at exactly those settings while looking perfect at 100%, and
nothing in this list would have caught it (see `known-issues.md`). Change it in Windows Settings →
System → Display → Scale, and restart the app so AWT re-reads it. Confirm for each menu that it
opens **at the cursor** and that no two labels overlap, and do the same for the tray menu below.

The tray menu is also a native widget outside Compose's reach (a `JPopupMenu` driven by
`WindowsTray` on Windows, a `java.awt.PopupMenu` on macOS via `MacTray`, `com.canonical.dbusmenu` on
Linux SNI), so confirm by hand:

- Right-clicking the tray icon opens the menu **at the cursor**, sitting above the taskbar — not
  pinned to a screen edge or clipped off it. Check this from both an icon shown directly in the
  notification area and one inside the overflow flyout, since the two sit at very different
  distances from the screen's right edge. This is the regression check for the tray's own
  device-pixel coordinates (see `known-issues.md`); with more than one monitor at different scale
  factors, check it on each.
- Right-clicking the tray icon opens the menu with the show/hide entry reflecting the window's
  current visibility, and both entries do what they say.
- Clicking outside the open tray menu dismisses it, leaving no stray window behind and no entry in
  the taskbar or Alt+Tab.
- (Windows) A double click on the tray icon, and a click on a new-article notification, both still
  reach `onTrayAction` — i.e. they show or hide the window per `shouldHideOnTrayAction`, exactly as
  before `WindowsTray` replaced Compose's `Tray()`.
- (Windows) New-article notifications still appear, with the unread badge on the icon updating.

The OPML file dialogs are real OS windows (an `NSSavePanel` on macOS, `GetOpenFileName` on Windows, a
`JFileChooser` `JDialog` on Linux) and cannot be driven by a Compose UI test, so confirm by hand.
Linux is the platform this backend split exists for — do these on a **packaged** build
(`createDistributable` → `bin/Keryx`), on a Plasma **X11** and a Plasma **Wayland** (XWayland)
session, and on GNOME:

- **Select an article first** so the reader's WebKitGTK WebView is live in-process (the condition
  under which the old GTK file-dialog peer crashed the JVM — see `known-issues.md`), then Settings ▸
  データ管理 ▸ OPML をインポート. A Swing chooser opens — not a GTK one — the app does not crash, and
  the chosen file imports. Repeat for エクスポート. Nothing in `<appDataDir>/logs/keryx.0.log` and no
  `hs_err_pid*.log` next to the launcher.
- The chooser renders **above** the article reader's WebView, never behind it.
- Import: only `.opml`/`.xml` files are listed with the "OPML ファイル" filter selected; switching to
  "すべてのファイル" lists everything; double-clicking a folder navigates into it (a filter that
  rejects directories would freeze navigation there).
- Export: the name is prefilled `keryx.opml`. Picking an **existing** file shows a 置き換える／
  キャンセル confirmation in Japanese; キャンセル returns to the chooser rather than aborting the
  export; 置き換える overwrites. A new file name saves with no prompt.
- All chooser chrome ("開く"/"キャンセル"/"ファイル名"/…) renders in **Japanese** on a packaged build —
  this is what the `jdk.localedata` module addition in `composeApp/build.gradle.kts` is for; if it
  reads in English, that module list is the first thing to check.
- Invoke import/export from all three places and confirm the chooser is owned by the right window:
  (1) the Settings dialog's buttons — the chooser appears **above** Settings, never behind it; (2) the
  in-window menu bar File ▸; (3) the KDE Global Menu File ▸ with the in-window bar hidden.
- Switch the in-app theme light ↔ dark without restarting, then reopen the chooser: it renders in the
  new FlatLaf theme.
- While a large OPML import runs, the app stays responsive — the import button's spinner keeps
  animating and the feed/article panes still scroll (the regression check for moving the OPML
  build/write/import work off the EDT).

macOS and Windows keep `java.awt.FileDialog`, but its owner and the thread it's shown from both
changed (previously an unowned dialog shown off the EDT), so re-confirm there too:

- The panel/dialog appears **centred over the window that opened it** (over the Settings dialog when
  opened from its buttons), and the Compose window behind it **keeps repainting** while the dialog is
  up (the check for showing it from the EDT's own secondary event loop rather than a background
  thread).
- macOS: the extension filter still hides non-OPML files on import, and saving over an existing file
  still raises the system's own replace prompt.
- Windows: import/export still complete (the `FilenameFilter` being inert on import is pre-existing,
  not a regression from this change).

Dialog auto-sizing (`DialogWindow` OS window behavior) cannot be auto-tested, so visually confirm:

- Open Settings, About, add feed and rename feed **ten times in a row each**; every open shows its
  full content at the correct size **and at its final position, from the very first visible frame**.
  Content must never appear in one place and then warp to another (typically upward, by half the
  difference between the 240pt placeholder and the fitted height) — the window is now held invisible
  until its fit has landed. Never a short ~240pt-tall window, never a tiny ~80x28 one, never a narrow
  one with the trailing tabs clipped. The repetition is the point — the races these guard against
  reproduced on roughly 7 of 10 opens.
- In those same ten opens, the dialog must appear **already centred** — never at the screen's
  top-left corner for a frame before jumping to the centre. That is what AWT's default location for
  a freshly constructed `Window` looks like, and it shows whenever the fitted size reaches the
  native window ahead of the fitted position.
- Repeat those opens in **dark mode**: not a single frame may show a light rectangle or a
  lighter-toned band — not around the card's edges, and not around the native button row at the
  bottom (the native window background and the full-bleed fill are both painted with the dialog's own
  container color now, so nothing should differ in tone from the card).
- The invisible-until-fitted gate must not be **perceptible**: each dialog still appears immediately
  on click, with no dead time where nothing is on screen. If one ever takes visibly long, suspect the
  500ms `DIALOG_PRESENT_FALLBACK_MS` safety net having been what released it, and check the log for
  the `Dialog stayed at …` warning below.
- **Autofocus still works**: in a text-prompt dialog (rename feed/folder/tag, add feed) the caret is
  in the text field the moment it appears, and typing straight away goes into it. This is the most
  likely thing for the delayed-visibility change to have broken — check it every time.
- Type continuously in a rename dialog so its supporting text appears and disappears: no judder, and
  the native button row does not flicker or re-lay-out per keystroke (it now revalidates only when a
  button *label* changes, not when the confirm button's enabled state does).
- Settings: switch tabs repeatedly, including between the tallest (general) and the shortest
  (notifications) tab. **The window must not resize at all** — same height on every tab, no movement
  of any edge, and no frame where the tab labels or the card sit displaced within the window before
  snapping back. The dialog's tab-content area is a fixed height precisely so this cannot happen; a
  resize reappearing here means something has reintroduced per-tab sizing (see `known-issues.md`,
  where forcing the resize to paint correctly is recorded as having made it permanently worse).
- Settings, tallest tab: the general tab's content must fit **without scrolling** at font size
  "中"（1.0）. If it scrolls, `KERYX_TAB_DIALOG_CONTENT_HEIGHT` needs bumping — cosmetic, not a
  correctness bug. At "大"/"特大" the taller tabs are expected to scroll.
- Switch the in-app theme light ↔ dark while running, then reopen each dialog: the *native* window
  background follows too — visible as the tone behind and around the native button row, and in any
  surplus area — not just the Compose-drawn card.
- Drag a dialog off-centre, then make its content change (add-feed URL → candidate list). The alert
  dialog re-centres on the content change (expected); the Settings dialog stays where it was dropped.
  That expansion is a resize of an *already visible* dialog, so seeing it resize is expected; what
  must not happen is the newly exposed area, or the strip around the native button row, flashing
  light while it settles (dark mode especially).
- On a multi-monitor setup with different scale factors (e.g. macOS Retina + an external 1x
  display): open both dialogs with the main window on each screen in turn, and drag an open dialog
  across the boundary. Correctly sized on both — the fit converts the measured content with the
  *dialog's* own density, not the owner window's.
- Minimize the app to the tray (or fully occlude the main window) immediately after opening
  Settings, then restore: the dialog is still correctly sized. The fit no longer depends on the
  dialog's frame clock, which stops delivering frames while nothing is rendered.
- Through all of the above: no visible size oscillation, no CPU spin, and no
  `Dialog stayed at … after … attempts to fit …` warning in `<appDataDir>/logs/keryx.0.log`
  (macOS: `~/Library/Application Support/Keryx/logs/`).
- (Linux, Plasma **X11** and **Wayland**) Repeat the checks above to confirm the two earlier dialog
  defects have not regressed: a modeless dialog (Settings, About) opens at the correct size, and its
  width does not creep narrower over the following second.
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

(macOS) `MacTray` has no notification-click handling of its own (removed — see known-issues.md
"macOS: clicking a notification banner does not restore a tray-hidden window" for why: it never
worked, and every case it appeared to cover turned out to be macOS's own default
click-to-activate behavior for a visible app, independent of any app code). So clicking a
notification banner is not app-tested behavior on macOS at all; confirm by hand only that the
*OS default* still holds — trigger a new-article notification (e.g. via a manual refresh with
unread articles) and click it while the window is in each of these states:

- Behind other windows on the same Space → the window comes to front and gets focus (OS default,
  not app code).
- **Minimized to the tray (hidden) → does *not* restore the window — known, unfixed limitation,
  not a regression to chase.** Restoring from tray-hidden still works via the ordinary tray-icon
  click or by relaunching the app (single-instance forwarding) — confirm those two still work
  instead.
- On a different Space → macOS switches to that Space and brings the window to front (OS default;
  confirm it still holds on the OS version under test).
- Also confirm a plain click on the tray icon itself still toggles show/hide as before.

(Linux, SNI host present — KDE/GNOME) Clicking a notification's body (`LinuxNotifier`'s `"default"`
action, routed through `SniConnection.notificationActionInvoked` and filtered by
`LinuxNotifier.consumeIfOwn`) also cannot be auto-tested, so confirm by hand — same window-state
cases as the macOS list above (behind other windows, minimized to the tray, on a different
workspace), plus:

- **Critical regression check**: trigger a notification from a *different* application (e.g. a
  chat client, a mail client) while Keryx is running, and click it — Keryx's window must **not**
  come to front. This is the check for the id-filtering in `PendingNotificationIds`/
  `consumeIfOwn` — the `ActionInvoked` D-Bus signal is unscoped by sender, so without correct
  filtering, any application's notification click would wrongly activate Keryx.
- Also confirm a plain click on the tray icon itself still toggles show/hide as before (the SNI
  icon's `Activate`/`SecondaryActivate` path is unrelated to `ActionInvoked`, but worth
  reconfirming alongside the above).
- If no notification daemon is present, or the daemon doesn't honor the `"default"` action key,
  notifications should still display (best-effort) with no crash — clicking them just does
  nothing, same as before this change.

(Windows, and Linux without an SNI host — the Compose `Tray()` fallback) Since this path funnels
both a tray-icon click and a notification-balloon click through the same `onAction` hook
(`KeryxTray`'s `onTrayAction`, decided by `shouldHideOnTrayAction` in `tray/TrayActionPolicy.kt` —
a focus-aware "hide if visible-and-focused, else bring to front" heuristic, biased for
`TRAY_ACTION_NOTIFICATION_RECENCY_MS` (5s) after a notification is sent so a balloon click landing
while the window happens to already be visible and focused still activates instead of hiding —
see the KDoc on `shouldHideOnTrayAction` and the wiring in `main.kt`), confirm by hand:

- With the window visible and focused, click the tray icon **more than 5s after any new-article
  notification** → the window hides, same as before this change.
- Trigger a new-article notification while the window is already visible and focused, then click
  the tray icon (or the balloon, if the daemon fires `onAction` for it) **within 5s** → the window
  stays visible and gets focus, rather than being hidden. Click the icon again after waiting out
  the 5s → it hides normally. (The residual gap this doesn't cover: a genuine icon click landing
  inside that same 5s window still activates instead of hiding — an accepted, narrower trade-off.)
- With the window visible but *not* focused (click another app, or move it behind another window,
  then trigger a new-article notification and click it — or click the tray icon itself while
  unfocused) → the window comes to front and gets focus, rather than being hidden.
- With the window minimized to the tray (hidden), click the tray icon or a notification → the
  window restores and comes to front.
- The "表示"/"非表示" tray menu item still toggles deterministically regardless of focus state
  (it uses the unchanged `onToggle`, not `onTrayAction`).

- The tray icon asset depends on how the platform draws it. macOS and Linux-with-an-SNI-host get the white glyph +
  black outline (`tray_icon_outlined.png`), which needs real alpha and at least ~22px. The Windows notification area
  and the Linux AWT fallback get the full-colour glyph (`tray_icon.png`), because Windows renders at 16px and never
  tints tray icons, and the AWT fallback paints an opaque white box behind the icon. With unread > 0 the red dot is
  drawn on either. Confirm each on its own platform:
  - (Windows) The icon reads clearly on a **light** taskbar as well as a dark one — the light theme is where an
    outlined glyph would wash out, so it is the case worth checking.
  - (Linux, no SNI host) The full-colour glyph is legible inside the white box AWT draws around it.
  - (macOS / Linux with an SNI host) Still the outlined glyph, legible on both light and dark backgrounds.

The Linux SNI tray cannot be auto-tested at all, so on a KDE Plasma session confirm (roughly in order of how
likely each is to be wrong):

- The icon is **not** inside a white box at 22px and 24px, on both a light and a dark panel, and at 2x panel scaling.
  If a bad entry is picked, trim `SNI_ICON_SIZES`.
- Package with `./gradlew :composeApp:createDistributable` and launch `build/compose/binaries/main/app/Keryx/bin/Keryx`
  — a missing jlink module (`jdk.security.auth`) only shows up there, never under `run`.
- Left-click toggles the window (this depends on `ItemIsMenu = false`; if the menu opens instead, that property is wrong).
- Right-click shows the menu with the correct labels, and the Show/Hide label flips after toggling the window
  *without* reopening the menu (exercises `AboutToShow` + `LayoutUpdated`).
- The unread dot appears/disappears live (`NewIcon` reaches the host).
- After `systemctl --user restart plasma-plasmashell` the icon comes back without restarting Keryx.
- A background refresh raises a desktop notification with the app icon.
- A notification's id is forgotten once it closes for any reason (clicked, dismissed, or
  auto-expired) - trigger several notifications, let some expire/dismiss without clicking, and
  confirm no leftover state affects later click-to-front handling (best confirmed indirectly,
  since PendingNotificationIds has no visible size counter).
- On GNOME without the AppIndicator extension it silently falls back to the AWT tray (no crash, no stack trace), and
  launching without `DBUS_SESSION_BUS_ADDRESS` neither hangs nor throws.
- Same behaviour on a Plasma Wayland session.
- Scrolling over the icon logs no errors in `journalctl --user -f`.
- Quitting from the menu leaves nothing behind in `busctl --user list | grep StatusNotifierItem`.
- `GetGroupProperties`/`AboutToShowGroup`/`EventGroup` behave (their `ai` / `a(isvu)` inputs are deserialized by
  dbus-java; `DBusSignatureTest` only proves the declared signature). If they misbehave, switch those parameters to
  `IntArray` / `Array<DBusMenuEventEntry>`.

The KDE Global Menu (AppMenu) likewise cannot be auto-tested (it needs a real X server, the
`com.canonical.AppMenu.Registrar` kded module, and a panel consumer), so on a KDE Plasma session confirm (roughly
in order of how likely each is to be wrong):

- With no Global Menu consumer configured: the in-window menu bar hides itself automatically shortly after launch
  (once `RegisterWindow` succeeds), and **Ctrl+N, Ctrl+W, Ctrl+, , Ctrl+Q, Ctrl+R still work** even though the bar
  is hidden (the regression this feature specifically guards against). **Ctrl+M** and the Global-Menu-exported
  **"Show Menu Bar" checkbox** both bring the in-window bar back, and the choice persists across restart. Shortcuts
  fire **exactly once** (not twice) in both states — bar visible (native accelerator) and hidden
  (`MenuShortcutDispatcher`) — a mishandled handoff between the two could double-fire an action like Add Feed,
  a `Ctrl+Shift+<letter>` item like Toggle Star, and a bare-key item like Rename Feed (F2).
- Add the "Application Menu Bar" panel widget: File/View/Article/Feed/Help appear there with correct labels,
  and items with a shortcut show the matching accelerator hint — the plain `Ctrl+<letter>` items (Add Feed,
  Close Window, Settings, Quit, Refresh All, Show Menu Bar, Search, Import/Export OPML, Unread Only), the
  `Ctrl+Shift+<letter>` Article/Feed items (Toggle Read, Toggle Star, Open in Browser, Copy URL, Refresh Feed),
  and the bare-key Feed items (Rename: F2, Delete: Delete — these two act on whatever feed list item is
  selected, a feed *or* a folder *or* a tag, so their label follows the selected item's type). The Feed menu's
  Copy Feed URL / Copy Site URL / Open Site items (no shortcut) sit between Move to Folder and Rename.
- Select a **folder**, then a **tag**, and confirm the Feed menu's rename/delete pair is enabled with the
  matching wording ("フォルダー名を変更"/"フォルダーを削除", "タグ名を変更"/"タグを削除") and that **F2 and
  Delete still fire through the Global Menu path** with the in-window bar hidden — previously both items were
  greyed out (and the shortcuts therefore dead) unless a feed was selected. The feed-specific items above them
  (Refresh Feed, Assign Tags, Move to Folder, Copy Feed URL, Copy Site URL, Open Site) stay disabled for a
  folder/tag selection, as before.
- Select a feed with no discovered site URL: Copy Feed URL stays enabled, but Copy Site URL and Open Site are
  greyed out; selecting a feed that does have one enables all three.
- Enable the titlebar "Application Menu" button instead: same check.
- Dynamic state (enabled/disabled items, the "Unread only" checkbox check state) and every click action match the
  in-window menu exactly.
- Clicking a **greyed-out** item (e.g. "同期" while no cloud account is connected, or a Feed-menu item with
  nothing selected) never runs its action — confirms the D-Bus click handler's `isEnabled()` guard
  (`AppMenuBarHost.kt`) actually blocks a `clicked` event the host still delivers for a disabled item, mirroring
  what `MenuShortcutDispatcher` already enforced for the keyboard-shortcut path.
- `startMinimized`: launch minimized, restore, confirm the Global Menu populates (validates the deferred/retried
  XID lookup) and the in-window bar still hides once ready.
- `systemctl --user restart plasma-plasmashell`: the Global Menu keeps working without restarting Keryx (validates
  the `NameOwnerChanged` reregistration).
- Repeat the key checks on a **Plasma Wayland** session (Keryx is an XWayland client there).
- **No regression** on GNOME/XFCE/other DEs (registrar absent → `AppMenuConnection.tryCreate()` returns `null` →
  the in-window menu bar shows exactly as before, all shortcuts work natively, no errors in `journalctl --user -f`)
  and on Windows/macOS (the whole path is `isLinux`-gated; macOS keeps its screen-menu-bar behavior unchanged).
- Quit Keryx: the Global Menu entry disappears immediately (no stale/frozen last-known menu for a dead process).

`AppMenuConnection` speaks the plain `com.canonical.AppMenu.Registrar` interface with no KDE-specific
assumption, so it is expected to work identically wherever else that interface is implemented and an
X11 window ID is available (registration resolves the window's XID the same way as the KDE path above,
so a pure-Wayland session with no XWayland falls back to the in-window bar here too) — notably
`vala-panel-appmenu` (the Global Menu applet for `vala-panel`/`xfce4-panel`/`mate-panel`, paired with the
`appmenu-gtk-module`/`unity-gtk-module` client-side GTK module). On an XFCE/MATE/Budgie X11/XWayland
session with `vala-panel-appmenu` and `appmenu-gtk-module`/`unity-gtk-module` installed and the panel
applet added, confirm:

- The menu appears in the panel applet the same way it does in KDE's Global Menu widget.
- Dynamic state (enabled/disabled items, the "Unread only" checkbox) and every click action match the
  in-window menu exactly.
- **Ctrl+M** and the exported **"Show Menu Bar" checkbox** both recover the in-window bar.
- With the applet/module not installed, Keryx falls back to the in-window menu bar exactly as on any DE
  without a registrar (`AppMenuConnection.tryCreate()` returns `null`) — note that some distributions ship
  `vala-panel-appmenu` without its registrar actually owning the bus name (a known packaging issue, e.g.
  [Debian #930572](https://bugs.debian.org/930572)); that is an environment issue on the registrar side, not
  something Keryx's client-side code can detect or work around.

The `keryx://` scheme registration writes real files into the user's home and depends on the desktop environment, so
the end-to-end path can only be confirmed on a Linux machine (the unit tests cover the file contents and the merge, not
the OS routing). Install a packaged build (`./gradlew :composeApp:packageDeb` → `sudo dpkg -i`), launch it
once so startup registers the scheme, and confirm:

- `xdg-mime query default x-scheme-handler/keryx` returns `keryx-url-handler.desktop`.
- With Keryx running, `xdg-open 'keryx://oauth2/callback?code=test&state=test'` brings the window to the front.
- Dropbox and OneDrive linking both complete through the browser round trip.
- `./gradlew :composeApp:run` does **not** create `$XDG_DATA_HOME/applications/keryx-url-handler.desktop`
  (default `~/.local/share/applications/keryx-url-handler.desktop`).
- Unrelated entries in `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`) survive the
  registration untouched.

The `.opml` file association is the same kind of OS-integration behavior and needs the same manual
confirmation, on all three desktop platforms (build with `createDistributable`/`packageDeb`/etc. —
`./gradlew :composeApp:run` never registers it, exactly like the `keryx://` scheme):

- **macOS**: launch `Keryx.app` once, then double-click an `.opml` file in Finder → Keryx activates
  and the subscriptions appear; also confirm right-click → "Open With" → Keryx.
- **Windows**: launch the installed app once, then double-click an `.opml` file in Explorer.
- **Linux**: launch the packaged app once (registers on startup), then confirm
  `xdg-mime query filetype some.opml` reports `application/x-opml+xml` and
  `xdg-mime query default application/x-opml+xml` reports `keryx-opml-handler.desktop`, then
  double-click an `.opml` file in the file manager.
- On all three: repeat while Keryx is already running (second launch) to confirm single-instance
  forwarding activates the existing window and imports without spawning a second process.

### (Android) The notification bell and the foreground alert Snackbar

Where the bell is drawn is covered by `NotificationBellPlacementTest.kt`, and the Snackbar's own
policy by `ForegroundAlertSnackbarTest.kt` / `NotificationCenterViewModelTest.kt`. What no test can
cover is that the Snackbar is actually *visible* where the platform draws it, and whether
`LocalWindowInfo`'s focus flag behaves as assumed on a real device. Confirm on a phone-sized
device/emulator:

- Leave the app while the feed list is showing, then relaunch: the bell is on the feed list's own
  toolbar (this is the case that had no entry point at all before).
- With a cloud provider connected, launch in airplane mode: the startup sync fails and a Snackbar
  announces it, with an action that opens the settings dialog on the sync tab.
- Subscribe to an unreachable feed URL, then refresh: the Snackbar's action lands on **that feed's
  article list**, not back on the feed list.
- With an article open, raise an alert: the Snackbar draws above the reader's `WebView`.
- The Snackbar does not collide with the navigation bar, with both 3-button and gesture navigation.
- Raise an alert while the settings dialog is open (or the notification shade is pulled down):
  nothing is announced then, and the Snackbar appears once it is closed. If it is instead announced
  and lost underneath, `LocalWindowInfo.isWindowFocused` does not go false for a Compose `Dialog`
  on this platform — record that as a known limitation rather than removing the gate.
- Rotate to landscape (two panes) and try a tablet-width device (three panes): exactly one bell,
  never two.
- With cloud sync connected, the feed list toolbar's five icons fit without wrapping, including at
  the OS's largest display size.
- At a phone width, in Settings ▸ Cloud sync with a provider connected: the provider name fits on
  one line and the two trailing icon buttons sit beside it on the same row (including at the OS's
  largest display size combined with the app's largest font size).
  - On an unconnected row, verify that the connect button reads as a `primary` fill.
  - On a connected row, verify that reset uses an `errorContainer` fill and disconnect uses an outline;
    both remain distinguishable against the connected row's teal background. Long-press each icon to check its
  tooltip. While a reset is running, the spinner is visible against its container and the row's
  height doesn't change. Check in both light and dark themes.

### In-App Update

Nothing past `canInstallAndroidApkUpdate`/the pure state-machine functions is exercised by an
automated test (see "Known uncovered areas" above) — the whole self-replace/installer path needs a
real, packaged build. Build with `./gradlew :composeApp:createDistributable` (not `:run`, which has
no `jpackage.app-path` and is always treated as a development install — see `InstallLocation.desktop.kt`)
and launch the packaged app, not a developer's own already-installed copy: **the first self-replace
test on any platform should run against a throwaway copy** of the `.app`/install directory, in case
a rollback path has a bug that leaves it damaged.

- **macOS**: launch an older build, trigger the tray/Updates-tab download, let it verify and reach
  "ready to install", then install. Confirm the app relaunches at the new version, `xattr -l` on the
  new `.app` shows no `com.apple.quarantine` (this app wrote the file itself — see
  `background-update.md`'s "In-App Update"), and the old `.app` is gone (not left as a stray `.old`
  directory next to it). Then check the swapped-in bundle itself, since these are the two things no
  automated test on any platform can reach and "it relaunched" does not imply either:
  - `codesign --verify --strict --deep` on the relaunched `.app` passes, and `codesign -dv` still
    reports the same `flags=`/`hashes=13+N` as the downloaded asset — a bundle can relaunch fine and
    still have lost its entitlements or its seal (see [build.md](build.md)).
  - `find <app>/Contents/runtime -type l | wc -l` is non-zero, i.e. the bundled JDK's
    `legal/` symlinks came through the archive round trip as links. A regression here otherwise
    shows up only as "the install failed", with nothing naming the cause.
  - Run it once more with the install directory on a **second volume** (a RAM disk —
    `hdiutil attach -nomount ram://…` — or a scratch APFS volume), so the cross-volume staging copy
    (`FileSystemExtras.copyTree`) is actually exercised. The default `~/Library/Caches` +
    `/Applications` layout is always same-volume, so an ordinary run never enters that code at all.
- **Windows (MSI-installed)**: same flow; confirm the UAC elevation prompt appears once, the app
  relaunches at the new version from the same install path, and declining the UAC prompt (or the
  upgrade otherwise failing) still relaunches the previous, still-working install rather than
  leaving nothing running.
- **Windows / Linux (portable ZIP)**: same self-replace flow as macOS; confirm the relaunched app
  runs from the same directory and no `.new`/`.old` sibling directories are left behind.
- **Linux (deb/rpm)**: confirm the Updates tab and tray both fall back to "open the release page"
  rather than offering a download — `updatePlan` always returns `OpenReleasePage` for
  `LINUX_PACKAGE`.
- **Android**: sideload a `github`-flavor APK (see `build.md`'s flavor split), trigger a download
  and install, and confirm the OS's own install-confirmation dialog appears and the app updates in
  place. Separately, sideload a `play`-flavor APK and confirm the Updates tab never offers a
  download at all (`canInstallAndroidApkUpdate` requires `REQUEST_INSTALL_PACKAGES`, which only the
  `github` manifest declares). If "install unknown apps" hasn't been granted yet, confirm clicking
  install opens that system settings screen instead of a session, and that returning without
  granting it leaves the Updates tab back at "ready to install" rather than stuck.
- **Failure paths, each platform**: cancel a download mid-transfer (state returns to `Available`,
  not `Failed`, and the `.part` file is gone); disconnect the network mid-download (state becomes
  `Failed` with a retry action); fill the disk before starting a download (the pre-flight free-space
  check refuses before any bytes are written). On desktop, killing the app between "ready to
  install" and clicking Install, then relaunching, should re-offer the same version as `Available`
  again on the next check rather than resuming a stale `Ready`.

# Keryx Sync Architecture

[日本語](sync-architecture.ja.md)

Target: cloud sync (Dropbox / Google Drive / OneDrive). Implementation is in `domain/SyncRepository.kt`, `domain/MergeSql.kt`, `platform/DatabaseMerger`, `platform/DatabaseSnapshot`.

## Design Philosophy

- The sync file is `keryx.db` (SQLite) uploaded as-is. However, the live DB is not read directly; a consistent snapshot is created via `VACUUM INTO`, and **`articles_fts` is DROPped on the copy side** before upload (the live index is never DROPped → concurrent searches do not hit `no such table`. See `articles_fts` section in [db-schema.md](db-schema.md)).
- Conflict resolution is merge (ATTACH DATABASE) on the app side before upload.
- FTS5 index is not included in the cloud (dropped on the copy side). New articles from merge are **incrementally indexed** after merge.
- **The database is never held in memory.** `CloudStorage` moves it by local file path — `download(path, destPath)` streams the response body straight to disk, `upload(path, sourcePath)`/`create` stream the file straight onto the wire — so peak memory is a fixed handful of 64 KB buffers rather than a multiple of the database's size. Both ends of the transfer are files anyway: the merge attaches the downloaded DB as a file, and the upload snapshot is produced as one by `VACUUM INTO`. The shared streaming helpers live in `data/cloud/CloudFileTransfer.kt` and are built on `kotlinx-io`, so they stay in `commonMain` with no platform-specific code. The digest that decides whether to upload (below) and the SQLite-header check (`core/SqliteFile.kt`'s path-based `looksLikeSqliteFile`) both read from the file too — the header check reads only its first 16 bytes.

## Cloud File Structure

```bash
/keryx.db                              ← Sync SQLite (without articles_fts)
/keryx-YYYYMMDD-HHMMSS.db.bak          ← archive left behind by a cloud-data reset (never pruned)
```

Conflict prevention is done via a revision check on upload — Dropbox: `rev`, a real server-side compare-and-set (409 on mismatch); Google Drive: the file's `version` field, compared client-side before writing (mitigated by the sync retry loop); OneDrive: the DriveItem `eTag`, sent via `If-Match` (412 on mismatch). No lock file is used.

## Sync Flow (`SyncRepository.sync()`)

1. `CloudStorage.metadata(CLOUD_DB_PATH)` fetches the cloud file's revision — or `null` when it does not exist yet, in which case the local DB is uploaded as-is via create-only (first time) and the sync ends. This single request replaces the old existence check; every provider already returned the revision in it (Dropbox's `get_metadata` `rev`, Drive's name-lookup `version`, Graph's item `eTag`) and simply discarded it, so learning the revision costs no extra round trip.
2. Stream `keryx.db` from the cloud into a temp file — **skipped when the revision equals `sync_state.cloud_file_rev`**, i.e. this device has already merged exactly this file. Re-downloading it would only re-merge bytes that are by definition already in the local DB.
   - The downloaded file is checked against SQLite's 16-byte file header (`core/SqliteFile.kt`'s path-based `looksLikeSqliteFile`, which reads only those bytes) before the merger opens it — symmetric with the same check on the upload side (step 5). A payload that fails it (truncated download, an HTML error page, a 0-byte or otherwise non-SQLite file) is rejected immediately as `CloudDataIncompatibleException`, rather than reaching `DatabaseMerger` and failing deep inside the merge statements with an ambiguous `no such table: cloud.folders`.
3. **`DatabaseMerger.merge()` to merge** (see below). Immediately after, `ftsManager.indexMissing()` incrementally indexes new articles from merge (without wiping the live index), then `driver.notifyListeners(...)` (all tables touched by merge) is called. Because merge writes via `DatabaseMerger`'s dedicated raw JDBC connection without firing SQLDelight query notifications, `watchAll` flows (and re-search with updated index) must be re-triggered to reflect sync content in the UI without restart.
4. Record `sync_state.cloud_file_rev`.
5. `DatabaseSnapshot.exportForUpload()` creates a `VACUUM INTO` snapshot, **drops `articles_fts` and `sync_state` on the copy side** (live DB is unchanged). Stream it to the cloud specifying `rev` — **skipped when the snapshot's SHA-256 equals `sync_state.last_uploaded_snapshot_digest` and step 2 merged nothing**, i.e. the cloud already holds exactly these bytes.
   - If `rev` mismatch (409 → `SyncConflictException`), retry from re-download (max 3 retries).
6. On success, record `last_synced_at`, the uploaded snapshot's digest, and **the revision the upload itself produced** (`CloudStorage.upload`/`create` return it). It must come from the write's own response, never a follow-up `metadata()` call: a second request could observe another device's newer write, and storing that revision would make the next sync skip a download whose contents were never merged.

Debouncing: After changes such as read/star, `SyncScheduler.scheduleSync()` batches sync after a fixed delay from the last operation.

### Skipping Unchanged Transfers

The background loop syncs on a timer, so the overwhelmingly common case is that neither side changed since last time. Both halves of the cycle are therefore conditional, and a sync in that state costs **one metadata request and zero payload bytes**:

- **Download** is skipped on an unchanged revision (step 2). Because step 6 records the revision the upload produced, a device that is the only writer recognises its own uploads and never downloads them back.
- **Upload** is skipped when the freshly built snapshot hashes to the same digest as the last uploaded one *and* no merge happened this cycle (step 5). A merge is always followed by an upload even if nothing else changed locally, because last-write-wins can leave the local DB holding rows the cloud lacks.

Comparing the snapshot's own content is what makes skipping safe: a local edit cannot hash to the previous digest, so no change is ever silently dropped. The opposite misjudgement — treating identical data as changed — merely uploads, exactly as before. Dropping `sync_state` from the snapshot (step 5) is what makes the digest stable: `last_synced_at` is rewritten on every successful sync, so leaving it in would change the bytes every cycle and the check could never fire. That table is device-local by design (see [db-schema.md](db-schema.md)) and appears in neither `MergeSql` nor `DatabaseMerger`'s expected schema, so no receiving device ever read it out of the uploaded file.

The digest is stored in `sync_state`, which is itself excluded from the upload — so it is per-device state, and a device that has never uploaded simply finds no digest and uploads.

Both markers describe **one provider's file**, so `clearSyncFailureState()` clears them alongside the failure state when a connection is disconnected or switched (`SettingsViewModel.disconnect()`/`switchTo()`). Each provider's revision is an opaque string in its own format, so a stale one would in practice never match the next provider's — but a match would skip a download that was never merged, and that is not a risk worth leaving to chance. Reconnecting to the same provider re-establishes both on the first sync.

The clear runs **under the same mutex `sync()` holds**, which is also why `updateAutoSyncGate()` / `emitErrorNotification()` are called inside that lock rather than after it: all four fields the clear touches (the revision, the digest, `lastSyncError`, `autoSyncSuspended`) are written by a sync too, so a sync already in flight would otherwise finish *after* the disconnect and restore the markers describing the provider that was just torn down — reintroducing exactly the skipped-download-never-merged case the clear exists to prevent. The cost is that disconnecting waits out an in-flight sync (bounded by the HTTP timeouts, and visible as the usual sync spinner), which is the correct ordering anyway.

### Automatic-Sync Suspension

`SyncRepository.sync(trigger: SyncTrigger = MANUAL)` takes who is asking. `SyncTrigger.AUTOMATIC` — the debounced-write consumer, `runStartupTasks`, and `backgroundUpdateLoop` — is subject to a gate: while `autoSyncSuspended` (a `StateFlow<Boolean>`) is true, an `AUTOMATIC` call skips the download/merge/upload cycle entirely and returns `Result.Ok(Unit)` without spinning the sync spinner or touching the notification center, so a known-unusable cloud DB is not re-downloaded and re-merged on every debounced write. `SyncTrigger.MANUAL` (the default, used by every UI-triggered sync — the toolbar/menu "sync now", "Refresh All", the initial connect-time sync, `SettingsViewModel.connect()`) **always runs for real**, so a person who explicitly asked for a sync always gets a real attempt and the failure that explains why, never a silent no-op.

The gate is set by `updateAutoSyncGate` (called from both `sync()` and `resetCloudData()`, right before `emitErrorNotification`): a result carrying `CloudDataIncompatibleException` sets it, any `Result.Ok` clears it. `SchemaVersionException` is deliberately excluded — it is equally permanent, but its fix is "update the app", and gating background syncing on it would hide the moment a newly-installed version starts working again. `scheduleSync()` also checks the gate before enqueueing a debounce signal, so a write burst does not even spin up the debounce wait while the cloud is known-unusable.

`autoSyncSuspended` is deliberately **in-memory, not persisted**: a process restart is a free, honest retry (another device may have fixed the cloud data in the meantime), and the gate's entire purpose — not re-downloading/re-merging the same unusable file, and not re-raising the same notification, within one running process — needs nothing more durable than that. It clears on any successful sync (manual or automatic), on a successful `resetCloudData()`, and via `clearSyncFailureState()` (renamed from `clearLastSyncError()` — it now also clears the gate, alongside the mirrored failure-reason text), called when the connection that produced it is disconnected or switched (`SettingsViewModel.disconnect()`/`switchTo()`).

Nothing about the reset/notification UI is affected by the gate: the notification-center `ResetCloudData` button and the settings screen's reset button are unconditional (not gated), and `lastSyncError` is left untouched by a skipped `AUTOMATIC` call, so the cloud-sync tab keeps showing why sync is currently broken.

## Merge (`DatabaseMerger` + `MergeSql`)

Downloaded DB is attached as `cloud` and merged table-by-table via timestamp comparison.

> [!IMPORTANT]
> SQLDelight's JVM `JdbcSqliteDriver` opens a new connection per statement for file DBs. Therefore, executing `ATTACH` through the driver makes it invisible to subsequent merge statements (`no such table: cloud.*`). The merge is done on a **single dedicated JDBC connection** in `platform/DatabaseMerger`: attach → version check → merge (transaction) → detach.

Merge SQL (`MergeSql`) key points:

- **Explicitly specify columns** (avoid `SELECT *` which can cause column count mismatch on schema differences).
- feeds / tags / folders / global_settings: last-write-wins (including logical deletion). However, the `ON CONFLICT` in the feeds statement **does not handle user-edited fields (`folder_id` / `sort_order` / `custom_title` / `deleted_at`) at all** (delegated to dedicated statements below). This prevents these fields from being overwritten just because the content is newer.
  The `ON CONFLICT` only handles content fields (url/title/description/etag etc. + `updated_at`).
  feeds are matched **`id`** so feed ids must be deterministically generated from `url` as **UUIDv5** at subscription time (`IdGenerator.feedId`), ensuring the same feed has the same id on all devices. With random ids, two devices independently subscribing to the same URL would get different ids and the URL collision guard would skip them, preventing convergence (and article ids derived from `feed_id` would also diverge). See `feeds` section in [db-schema.md](db-schema.md) for details.
- articles: Read (`read_at`) / star (`starred_at`) are last-write-wins, body is OR merge, `search_text` is recalculated. Deletion is last-write-wins on `deleted_at` / `deleted_updated_at` (field-specific, like read/star), so a cache-cleanup soft-delete propagates instead of being resurrected from the cloud; a star newer than the deletion revives the article (`deleted_at` → NULL). `upsert` (feed refresh) never writes `deleted_at`, so a refresh cannot revive a deleted article.
  Articles are matched **`id`** so article IDs must be deterministically generated from `(feed_id, guid)` as **UUIDv5** (`IdGenerator.articleId`), ensuring the same article has the same ID on all devices. With random IDs, two devices independently fetching the same article would get different IDs and the guid collision guard below would skip them, preventing read-state propagation (this was a fixed bug). See `articles` section in [db-schema.md](db-schema.md) for details.
- feed_tags: last-write-wins. Only imported if the referenced feed / tag exists in main (FK protection).
- **feeds user-edited fields are merged independently via dedicated statements using field-specific timestamps** (same design as `read_at` / `starred_at` for articles, separated from row-level `updated_at` = content refresh update):
  `mergeFeedFolderId` (`folder_id` / `folder_updated_at`), `mergeFeedSortOrder` (`sort_order` / `sort_order_updated_at`), `mergeFeedCustomTitle` (`custom_title` / `custom_title_updated_at`), `mergeFeedDeletedAt` (`deleted_at` / `deleted_updated_at`). All use NULL-aware comparison (`c.<ts> IS NOT NULL AND (main is NULL or cloud is strictly newer)`), satisfying: propagation not blocked by refresh, no useless writes after convergence, local preserved if newer. `folder_id` is resolved by the dedicated statement (keep if folder exists in main, fall back to same-name resolution, else NULL) and is not included in feeds INSERT. `sort_order` / `custom_title` / `deleted_at` remain in feeds INSERT for initial value propagation (only excluded from `ON CONFLICT`).
- `NOT EXISTS` / `EXISTS` guards skip colliding rows (same URL, different ID, etc.) so UNIQUE / FK violations do not fail the entire transaction.
- `MergeSql.all` application order:
  `updateFoldersByName, insertFolders, feeds, mergeFeedFolderId, mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt, updateTagsByName, insertTags, articles, feedTags, globalSettings`.
  `folders` is before `feeds` (for `feeds.folder_id` FK), and the four `mergeFeed*` are after `feeds` (and `insertFolders`) (so main has both feed rows and folder rows before resolution).

### Merge Failure Classification

`DatabaseMerger.merge` classifies a failure from `mergeUnclassified` (its private, unclassified implementation) using SQLite's **error code** — not message text, which is locale- and driver-version-fragile — found by walking the cause chain for an `org.sqlite.SQLiteException` (`findSqliteCause`, bounded against a cause cycle). `SchemaVersionException` is caught and rethrown first, before the classifying catch-all, so it is never reclassified. Classification is deliberately conservative: an error it doesn't recognize (or one with no `SQLiteException` in its cause chain) is rethrown unchanged, so a miss falls through to `SyncRepository`'s own catch-all as a transient `CloudStorageException` rather than regressing behavior.

| SQLite primary result code | Classification |
| --- | --- |
| `SQLITE_NOTADB`, `SQLITE_CORRUPT`, `SQLITE_FORMAT`, `SQLITE_EMPTY` | **Permanent** → `CloudDataIncompatibleException`. The file itself is broken. |
| `SQLITE_CONSTRAINT` (covers every extended `SQLITE_CONSTRAINT_*` variant — `_UNIQUE`, `_NOTNULL`, `_FOREIGNKEY`, etc. — since an extended code's low byte is always its primary code) | **Permanent** → `CloudDataIncompatibleException`. `MergeSql`'s `NOT EXISTS`/`EXISTS` guards already rule out every collision with *main*'s own rows (see above), so the only way a merge statement can still hit a constraint is the cloud DB's own row set violating it — a duplicate `url` inside the cloud DB, a NULL where the cloud's own (laxer) schema allowed one, etc. That is data this app's schema cannot represent, exactly what `CloudDataIncompatibleException` means. |
| `SQLITE_ERROR` (`no such table`, `no such column`) | **Ambiguous** — this is what a foreign/legacy cloud schema looks like, but also what a broken *local* schema (an unrelated app bug) looks like. Resolved by calling `validateSchema` against the downloaded cloud file: `false` → `CloudDataIncompatibleException`; `true` or `null` (undetermined) → rethrown unchanged, since neither confidently pins the failure on the cloud. |
| Anything else (`SQLITE_CANTOPEN`, `SQLITE_IOERR`, `SQLITE_FULL`, `SQLITE_BUSY`, `SQLITE_LOCKED`, `SQLITE_READONLY`, no `SQLiteException` found, …) | **Transient / app-side** — rethrown unchanged. |

Because classification lives entirely inside `DatabaseMerger.merge` (which only wraps `mergeUnclassified`, not anything the caller does afterward), it structurally cannot see `SyncRepository.mergeCloud`'s post-commit steps — `ftsManager.indexMissing()` and `driver.notifyListeners(...)`, both of which run *after* the merge has already committed. A failure there (e.g. a dropped local `articles_fts` table, itself `SQLITE_ERROR`) reaches `SyncRepository`'s own catch-all unclassified and is reported as `CloudStorageException`, never `CloudDataIncompatibleException` — the merge already succeeded, so offering a destructive cloud-data reset for it would be wrong. (This was a real risk under the previous message-text-matching design in `SyncRepository`, whose `try` covered these same post-commit calls.)

A residual, accepted risk: with `PRAGMA foreign_keys=ON` active during the merge transaction, a pre-existing inconsistency on the *main* (local) side could in principle only be exposed once a merge `UPDATE` statement touches it, surfacing as `SQLITE_CONSTRAINT_FOREIGNKEY` and being misclassified as cloud-caused. In practice this is unlikely — e.g. `mergeFeedFolderId` always resolves `folder_id` to either an existing `main.folders` row or `NULL` — and even if misclassified, no data is lost: the reset path (below) archives rather than deletes. The extended SQLite error code is logged for post-hoc diagnosis.

**Future work**: `PRAGMA quick_check`/`integrity_check` on the downloaded cloud DB is deliberately *not* run on every sync — it is O(DB size), and SQLite already surfaces a corrupt page as a distinct error code the moment the merge touches it (see "Merge failure classification" below), so a whole-file scan on every sync would buy nothing for the pages the merge doesn't visit. It also cannot detect the failure mode this feature targets (a cloud DB with duplicate/NULL data that violates *this app's* constraints but not the cloud DB's own, since `quick_check` only verifies a DB's internal consistency against its own schema). If ever added, it belongs as a second-stage check inside the merge-failure classification path (only once the ambiguous `SQLITE_ERROR` case has already ruled out a schema mismatch), not on the hot sync path.

## Schema Version

Managed via `PRAGMA user_version`. At merge time, `cloud.user_version` is checked; if the cloud is newer than local, `SchemaVersionException` is thrown to prompt the user to update the app (merge aborts).
Current `user_version` is 2 (`1.sqm` adds `articles.deleted_at` / `deleted_updated_at`).

> [!NOTE]
> **Local-direction migration for older cloud schema**: `DatabaseMerger.merge` checks the downloaded cloud DB's `user_version` before merging, and if older than local, runs `KeryxDatabase.Schema.migrate` on the temp file to bring it up to the local schema before merging. This prevents merge statements referencing newer columns from failing with `no such column` against an old cloud DB. With version 2, this uplift branch (`migrateCloudIfOlder`) now fires for a version-1 cloud DB, applying `1.sqm` to the downloaded copy so the article merge can reference `deleted_at`.

`DatabaseMerger.validateSchema(dbPath, schemaVersion)` returns a **nullable** `Boolean` — `true`/`false` for a registered schema version's tables/columns, `null` when `schemaVersion` has no entry in the desktop actual's `EXPECTED_SCHEMAS` map. This is deliberately fail-safe in the direction that matters: a version bump (`KeryxDatabase.Schema.version`) whose expected-schema entry was forgotten degrades `validateSchema` from `true` to `null` rather than `false`, and every caller treats `null` the same as `true` — an undetermined verdict must never be used to offer a destructive cloud-data reset for what is really just a missing registration. `SyncMergerTest.validateSchemaReturnsTrueForValidKeryxDb` pins the current schema version to `true`, so a forgotten registration fails that test immediately rather than silently degrading behavior in the field; `schemaVersion` is a plain `Long`, so this cannot be enforced by the compiler (no sealed/enum exhaustiveness check applies), making that test the actual guard.

## FTS5 Handling

**Never DROP `articles_fts` on the live DB.** Excluding the index from upload is done by `VACUUM INTO` snapshot (`DatabaseSnapshot.exportForUpload`) **dropping it on the copy side**. `VACUUM INTO` preserves `user_version`, so the receiving `DatabaseMerger`'s schema version check works correctly.

Index maintenance is two-tier:

- **Hot path (after feed refresh / sync merge)**: `FtsManager.indexMissing()` incrementally indexes only unindexed new articles (O(new rows), does not wipe index). Full rebuild (`'rebuild'`) is O(total indexed text) and heavy, and could reject running searches, so it is not used on hot paths. Indexes of existing articles with updated body text remain stale until the next rebuild (acceptable; they still match old tokens so searches do not regress to zero results).
- **Healing full rebuild (`rebuildIndex()` = `'rebuild'`)**:
  Executed only in the daily idle pass in `StartupTasks.kt` (`maybeRebuildFtsIndex`, gated by `local_settings.lastFtsRebuiltAt` 24h gate + `ActivityCenter` idle). Rebuilds stale existing rows (body text updated since incremental indexing). `'rebuild'` is a single atomic statement (readers see only before or after) + `busy_timeout` wait, so running searches do not regress to zero results either.

The two index writers are **mutually exclusive**: `FtsManager` serializes `indexMissing()` and
`rebuildIndex()` behind an internal mutex (both are therefore `suspend`). The daily pass's idle gate is a
lock-free `ActivityCenter` check, so a refresh starting just after it passes would otherwise overlap the
rebuild — always wasted work (a rebuild subsumes an incremental insert), and on a corpus whose rebuild
outlasts `busy_timeout`, a raw `SQLiteException` no caller catches. Searches are deliberately **not**
serialized: they still rely on `'rebuild'` being a single atomic statement plus the `busy_timeout` wait.

On startup, `FtsManager.ensureIndexed()` (initial creation + unindexed row incremental insert) is called as
before, from a `runBlocking` in `main.kt` — the window must not open on an absent index. The writer mutex
is coroutine-based and, that early, can only be held briefly by an `.opml` import dispatched moments
before, so blocking the main thread on it cannot deadlock.

## Cloud Authentication (OAuth PKCE + Offline Access)

OAuth 2.0 authorization-code-with-PKCE orchestration (PKCE generation, authorization URL building, browser launch, state verification, code exchange) is consolidated in `OAuthConnectFlow` (desktop). Provider differences are only in **redirect reception method (`OAuthRedirectTransport`) and endpoints/scopes (`CloudAuthManager` implementation)**, so `DropboxAuthManager` / `GoogleDriveAuthManager` / `OneDriveAuthManager` implement `CloudAuthManager`. All request offline access (Dropbox: `token_access_type=offline`, Google: `access_type=offline` + `prompt=consent`, OneDrive: `offline_access` scope) to **obtain and save refresh tokens**.

Redirect reception method is chosen per provider (see the `.claude/rules/cloud-oauth-transport.md` design rule — prefer the custom URI scheme when both work):

- **Dropbox / OneDrive — Custom URI scheme** (`CustomUriRedirectTransport`): Redirect URI is `keryx://oauth2/callback`, shared by both providers and disambiguated by `state`. The authorization URL is opened in the default browser, and the OS delivers the URL to the running instance (`main.kt` parses via `parseOAuthUri` and feeds a shared `MutableSharedFlow<OAuthCallbackParams>`). OneDrive uses the Microsoft Identity platform (`common` tenant) and Microsoft Graph; it is a **PKCE public client with no client secret** (unlike Google), and stores the sync DB in the hidden app folder (`/me/drive/special/approot`, scope `Files.ReadWrite.AppFolder`). Microsoft has no standard token-revocation endpoint, so `OneDriveAuthManager.revoke` is a no-op and disconnect just clears the stored tokens. Optimistic concurrency uses the DriveItem `eTag` as the `rev`, sent back via `If-Match` (412 → conflict); `create` uses `@microsoft.graph.conflictBehavior=fail` (409 → conflict).
- **Google Drive — Loopback** (`LoopbackRedirectTransport`): Google's "Desktop app" client does not allow arbitrary custom schemes; only `http://127.0.0.1:<port>` loopback is accepted. A temporary HTTP server (`com.sun.net.httpserver`; `jdk.httpserver` module is bundled) is started to receive the redirect and stopped after reception. No OS scheme registration is required (`keryx://` registration remains for Dropbox / OneDrive). **Unlike Dropbox, the client secret (`GOOGLE_DRIVE_CLIENT_SECRET`) is also sent for both token exchange and refresh** — despite using PKCE, Google's "Desktop app" OAuth client is not treated as a full public client like iOS/Android, and Google's token endpoint rejects token exchange / refresh without `client_secret` with `invalid_request: client_secret is missing` (regardless of PKCE). Scope is `drive.appdata` only (app-specific hidden folder in the user's Drive). During development, set the OAuth consent screen to "Testing" and register test users.

How the scheme is registered with the OS differs per platform. macOS declares it in Info.plist (`CFBundleURLTypes`) at packaging time. Windows and Linux register it at startup, from `registerCustomUriScheme()`: the Windows path writes `HKEY_CURRENT_USER\Software\Classes\keryx` (the per-user hive, so no admin elevation is needed), the Linux path writes a user-level `.desktop` entry (`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`, default `~/.local/share/applications/keryx-url-handler.desktop`) and a `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`) association via `LinuxUriSchemeRegistrar`. The Linux entry's `Exec` line must end in `%u` — without it the desktop-entry spec does not hand the URI to the process, and the browser cannot resolve the scheme at all (an "unknown protocol" error). On both platforms the OS then launches the app with the URL as a command-line argument, which `main.kt` forwards to the running instance via single-instance.

> [!NOTE]
> **Custom-URI providers on every desktop OS**: `./gradlew :composeApp:run` cannot complete Dropbox / OneDrive linking. On macOS, LaunchServices routes `keryx://` to the packaged `Keryx.app`, so the `gradlew run` instance never receives the redirect. On Windows and Linux, the startup registration deliberately no-ops unless the process is a packaged launcher (`packagedLauncherPath()`), because registering the JDK's own `java` binary as the `keryx://` handler would outlive the Gradle run. To test/perform linking, build the app with `./gradlew :composeApp:createDistributable` and launch it (see [setup.md](setup.md) for details). Google Drive uses loopback reception, so this restriction does not apply and `gradlew run` can complete linking.

### Token Storage

**Per-provider separate `TokenStorage` instances** are constructed in DI (`platformModule`) (do not share a single instance across providers; `SecurityCliTokenStorage` caches results per instance, so sharing would break). Keychain account name and fallback file name are derived from `CloudStorageType.id` (Dropbox is `"dropbox"`, matching the legacy hardcoded value so no existing token migration needed. Google Drive is `"google_drive"`). `KEYCHAIN_SERVICE` is shared.

- Windows/Linux: OS secure storage (java-keyring — Credential Manager / Secret Service, `KeyringTokenStorage`).
- macOS: Delegated to Apple-signed `/usr/bin/security` CLI (`SecurityCliTokenStorage`). java-keyring fails to write to Keychain from a shared JVM, so macOS uses `security` instead.
- On failure for either, fallback to a file in the data directory `.{CloudStorageType.id}_tokens.json` (0600. Dropbox is `.dropbox_tokens.json`). DI switches between the two using `isMacOs`.
- macOS performs a **read-back verification** after writing (explicitly specifying login keychain), and falls back to file if persistence cannot be confirmed. **Write persistence is session-dependent**: packaged version (GUI login session) persists to login keychain, but `gradlew run` (detached session under Gradle daemon via launchd) may not persist even if `security add` returns success, so file is used. **Read is possible from either session** (once linked via packaged version, `gradlew run` can also reuse the connection).

### Future Work (not yet supported)

- **file → Keychain migration heal**: If `.dropbox_tokens.json` exists but Keychain is empty, `SecurityCliTokenStorage.load()` writes the file value back to Keychain and **deletes the file only if read-back verification succeeds** (on failure, keeps the file to prevent data loss). Phase 2.5 verification logic can be reused.

## Sync Target Article Range

Startup cache cleanup **soft-deletes** articles past the retention period (stamps `deleted_at` / `deleted_updated_at`) rather than physically removing them. The tombstones are uploaded and propagate via the merge (last-write-wins on `deleted_updated_at`), so a device that missed the deletion converges instead of re-adding the article. The rows are not physically reclaimed yet (physical GC of old tombstones is future work), so soft-deleted articles still ride along in the uploaded snapshot until then.

## Resetting (Archiving) Cloud Data

`SyncRepository.resetCloudData()` is the recovery path for a cloud DB the app cannot use. It does **not** delete the cloud file outright: `archiveCloudDb()` first renames it to a timestamped path via `CloudStorage.rename()` (`core/CloudBackupPath.kt`'s `cloudBackupPath(clock.nowMillis())`, e.g. `/keryx-20260811-103000.db.bak`, formatted in UTC so the name is deterministic for a fixed instant regardless of device time zone), then `createFresh()` re-uploads a snapshot of the local DB as the new `/keryx.db`. The archive is never automatically pruned — `CloudStorage` has no listing API, and removing that mechanism entirely would defeat the point of keeping a way back to a mistaken reset or a merge bug that only *looked* like corruption.

- **`rename` semantics** (`CloudStorage.rename`, implemented per-provider — Dropbox `files/move_v2` with `autorename=false`, Google Drive a metadata `PATCH` on the file id resolved by name lookup, Microsoft Graph a metadata `PATCH` on the app-folder item): idempotent when the source is already absent (`Result.Ok`, so a reset on an already-clean cloud folder still proceeds to `createFresh`), and fails rather than overwriting when the destination already exists.
- **Delete fallback**: if the rename itself fails for a storage reason (e.g. an occupied archive name), `archiveCloudDb()` falls back to `cloud.delete(CLOUD_DB_PATH)` so a reset can never become permanently blocked. A `CloudAuthException` is the one exception *not* retried as a delete — the same missing credentials would fail identically.
- **Backup path naming**: deliberately not derived from `CLOUD_DB_PATH` (`CLOUD_DB_BACKUP_PREFIX`/`CLOUD_DB_BACKUP_SUFFIX` in `core/Constants.kt`), so the archive's basename never matches Google Drive's `name = 'keryx.db'` lookup or OneDrive's basename addressing — otherwise `CloudStorage.exists(CLOUD_DB_PATH)` would see the archive too.

# Keryx Sync Architecture

[日本語](sync-architecture.ja.md)

Target: cloud sync (Dropbox / Google Drive / OneDrive). Implementation is in `domain/SyncRepository.kt`, `domain/MergeSql.kt`, `platform/DatabaseMerger`, `platform/DatabaseSnapshot`.

## Design Philosophy

- The sync file is `keryx.db` (SQLite) uploaded as-is. However, the live DB is not read directly; a consistent snapshot is created via `VACUUM INTO`, and **`articles_fts` is DROPped on the copy side** before upload (the live index is never DROPped → concurrent searches do not hit `no such table`. See `articles_fts` section in [db-schema.md](db-schema.md)).
- Conflict resolution is merge (ATTACH DATABASE) on the app side before upload.
- FTS5 index is not included in the cloud (dropped on the copy side). New articles from merge are **incrementally indexed** after merge.
- **Cloud files are not backward-compatible with the legacy version** (user decision).

## Cloud File Structure

```bash
/keryx.db   ← Sync SQLite (without articles_fts)
```

Conflict prevention is done via a revision check on upload — Dropbox: `rev`, a real server-side compare-and-set (409 on mismatch); Google Drive: the file's `version` field, compared client-side before writing (mitigated by the sync retry loop); OneDrive: the DriveItem `eTag`, sent via `If-Match` (412 on mismatch). No lock file is used.

## Sync Flow (`SyncRepository.sync()`)

1. If `keryx.db` does not exist in the cloud, upload the local DB as-is (first time).
2. Download `keryx.db` from the cloud (to a temp file).
3. **`DatabaseMerger.merge()` to merge** (see below). Immediately after, `ftsManager.indexMissing()` incrementally indexes new articles from merge (without wiping the live index), then `driver.notifyListeners(...)` (all tables touched by merge) is called. Because merge writes via `DatabaseMerger`'s dedicated raw JDBC connection without firing SQLDelight query notifications, `watchAll` flows (and re-search with updated index) must be re-triggered to reflect sync content in the UI without restart.
4. Record `sync_state.cloud_file_rev`.
5. `DatabaseSnapshot.exportForUpload()` creates a `VACUUM INTO` snapshot, **drops `articles_fts` on the copy side** (live DB is unchanged). Upload its bytes specifying `rev`.
   - If `rev` mismatch (409 → `SyncConflictException`), retry from re-download (max 3 retries).
6. On success, record `last_synced_at`.

Debouncing: After changes such as read/star, `SyncScheduler.scheduleSync()` batches sync after a fixed delay from the last operation.

## Merge (`DatabaseMerger` + `MergeSql`)

Downloaded DB is attached as `cloud` and merged table-by-table via timestamp comparison.

> **Important implementation note**: SQLDelight's JVM `JdbcSqliteDriver` opens a new connection per statement for file DBs. Therefore, executing `ATTACH` through the driver makes it invisible to subsequent merge statements (`no such table: cloud.*`). The merge is done on a **single dedicated JDBC connection** in `platform/DatabaseMerger`: attach → version check → merge (transaction) → detach.

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

## Schema Version

Managed via `PRAGMA user_version`. At merge time, `cloud.user_version` is checked; if the cloud is newer than local, `SchemaVersionException` is thrown to prompt the user to update the app (merge aborts).
Current `user_version` is 2 (`1.sqm` adds `articles.deleted_at` / `deleted_updated_at`).

> **Local-direction migration for older cloud schema**: `DatabaseMerger.merge` checks the downloaded cloud DB's `user_version` before merging, and if older than local, runs `KeryxDatabase.Schema.migrate` on the temp file to bring it up to the local schema before merging. This prevents merge statements referencing newer columns from failing with `no such column` against an old cloud DB. With version 2, this uplift branch (`migrateCloudIfOlder`) now fires for a version-1 cloud DB, applying `1.sqm` to the downloaded copy so the article merge can reference `deleted_at`.

## FTS5 Handling

**Never DROP `articles_fts` on the live DB.** Excluding the index from upload is done by `VACUUM INTO` snapshot (`DatabaseSnapshot.exportForUpload`) **dropping it on the copy side**. `VACUUM INTO` preserves `user_version`, so the receiving `DatabaseMerger`'s schema version check works correctly.

Index maintenance is two-tier:

- **Hot path (after feed refresh / sync merge)**: `FtsManager.indexMissing()` incrementally indexes only unindexed new articles (O(new rows), does not wipe index). Full rebuild (`'rebuild'`) is O(total indexed text) and heavy, and could reject running searches, so it is not used on hot paths. Indexes of existing articles with updated body text remain stale until the next rebuild (acceptable; they still match old tokens so searches do not regress to zero results).
- **Healing full rebuild (`rebuildIndex()` = `'rebuild'`)**:
  Executed only in the daily idle pass in `main.kt` (`maybeRebuildFtsIndex`, gated by `local_settings.lastFtsRebuiltAt` 24h gate + `ActivityCenter` idle). Rebuilds stale existing rows (body text updated since incremental indexing). `'rebuild'` is a single atomic statement (readers see only before or after) + `busy_timeout` wait, so running searches do not regress to zero results either.

On startup, `FtsManager.ensureIndexed()` (initial creation + unindexed row incremental insert) is called as before.

## Cloud Authentication (OAuth PKCE + Offline Access)

OAuth 2.0 authorization-code-with-PKCE orchestration (PKCE generation, authorization URL building, browser launch, state verification, code exchange) is consolidated in `OAuthConnectFlow` (desktop). Provider differences are only in **redirect reception method (`OAuthRedirectTransport`) and endpoints/scopes (`CloudAuthManager` implementation)**, so `DropboxAuthManager` / `GoogleDriveAuthManager` / `OneDriveAuthManager` implement `CloudAuthManager`. All request offline access (Dropbox: `token_access_type=offline`, Google: `access_type=offline` + `prompt=consent`, OneDrive: `offline_access` scope) to **obtain and save refresh tokens**.

Redirect reception method is chosen per provider (see the `.claude/rules/cloud-oauth-transport.md` design rule — prefer the custom URI scheme when both work):

- **Dropbox / OneDrive — Custom URI scheme** (`CustomUriRedirectTransport`): Redirect URI is `keryx://oauth2/callback`, shared by both providers and disambiguated by `state`. The authorization URL is opened in the default browser, and the OS delivers the URL to the running instance (`main.kt` parses via `parseOAuthUri` and feeds a shared `MutableSharedFlow<OAuthCallbackParams>`). OneDrive uses the Microsoft Identity platform (`common` tenant) and Microsoft Graph; it is a **PKCE public client with no client secret** (unlike Google), and stores the sync DB in the hidden app folder (`/me/drive/special/approot`, scope `Files.ReadWrite.AppFolder`). Microsoft has no standard token-revocation endpoint, so `OneDriveAuthManager.revoke` is a no-op and disconnect just clears the stored tokens. Optimistic concurrency uses the DriveItem `eTag` as the `rev`, sent back via `If-Match` (412 → conflict); `create` uses `@microsoft.graph.conflictBehavior=fail` (409 → conflict).
- **Google Drive — Loopback** (`LoopbackRedirectTransport`): Google's "Desktop app" client does not allow arbitrary custom schemes; only `http://127.0.0.1:<port>` loopback is accepted. A temporary HTTP server (`com.sun.net.httpserver`; `jdk.httpserver` module is bundled) is started to receive the redirect and stopped after reception. No OS scheme registration is required (`keryx://` registration remains for Dropbox / OneDrive). **Unlike Dropbox, the client secret (`GOOGLE_DRIVE_CLIENT_SECRET`) is also sent for both token exchange and refresh** — despite using PKCE, Google's "Desktop app" OAuth client is not treated as a full public client like iOS/Android, and Google's token endpoint rejects token exchange / refresh without `client_secret` with `invalid_request: client_secret is missing` (regardless of PKCE). Scope is `drive.appdata` only (app-specific hidden folder in the user's Drive). During development, set the OAuth consent screen to "Testing" and register test users.

How the scheme is registered with the OS differs per platform. macOS declares it in Info.plist (`CFBundleURLTypes`) at packaging time. Windows and Linux register it at startup, from `registerCustomUriScheme()`: the Windows path writes `HKEY_CURRENT_USER\Software\Classes\keryx` (the per-user hive, so no admin elevation is needed), the Linux path writes a user-level `.desktop` entry (`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`, default `~/.local/share/applications/keryx-url-handler.desktop`) and a `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`) association via `LinuxUriSchemeRegistrar`. The Linux entry's `Exec` line must end in `%u` — without it the desktop-entry spec does not hand the URI to the process, and the browser cannot resolve the scheme at all (an "unknown protocol" error). On both platforms the OS then launches the app with the URL as a command-line argument, which `main.kt` forwards to the running instance via single-instance.

> **Note (custom-URI providers on every desktop OS)**: `./gradlew :composeApp:run` cannot complete Dropbox / OneDrive linking. On macOS, LaunchServices routes `keryx://` to the packaged `Keryx.app`, so the `gradlew run` instance never receives the redirect. On Windows and Linux, the startup registration deliberately no-ops unless the process is a packaged launcher (`packagedLauncherPath()`), because registering the JDK's own `java` binary as the `keryx://` handler would outlive the Gradle run. To test/perform linking, build the app with `./gradlew :composeApp:createDistributable` and launch it (see [setup.md](setup.md) for details). Google Drive uses loopback reception, so this restriction does not apply and `gradlew run` can complete linking.

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

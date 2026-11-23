# Keryx DB Schema

[日本語](db-schema.ja.md)

Target: local SQLite (managed by SQLDelight). `.sq` files are located at
`shared/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/`.

The live file's actual path differs by platform: desktop's `JdbcSqliteDriver` opens
`AppDirs.appDataDir()/keryx.db` directly, but Android's `AndroidSqliteDriver` places it at
`Context.getDatabasePath("keryx.db")` — `<dataDir>/databases/keryx.db`, a different directory than
`AppDirs.appDataDir()` (`Context.filesDir`, i.e. `<dataDir>/files`). `platform/DatabaseFile.kt`'s
`databaseFilePath()` is the single `expect` function that resolves the real value per platform;
`DatabaseMerger`/`DeltaExporter` (both work by path, not through the driver) must always go
through it rather than composing `AppDirs.appDataDir()` with the filename themselves.

## Design Philosophy

- All tables are managed by SQLDelight (`.sq`). `articles_fts` is created/maintained separately via raw SQL (`FtsManager`).
- The **synced tables** are `feeds`, `articles`, `tags`, `feed_tags`, `folders` and `global_settings`, minus the
  columns noted as device-local below (`articles.search_text`; a feed's fetch state lives in its own table). Every
  local change to a synced row is recorded in `sync_outbox` by a trigger, and sent as a delta — see
  [sync-architecture.md](sync-architecture.md).
- Logical deletion uses `deleted_at` (NULL = alive). Sync timestamp is `updated_at`.
- A delete and the event that undoes it never share a stamp on the same row. `tags` / `folders` stamp
  `updated_at`, and `feeds` stamps `deleted_updated_at`, as `MAX(now, previous + 1)` on a soft delete and on a
  recreation / reactivation (`upsertByName`) or resubscribe (`stampResubscribed`), so the stamps strictly increase
  even within one millisecond. The sync merge's last-write-wins can only order events whose stamps differ: on an
  equal stamp it falls back to its tie rule (decided by value — see [sync-architecture.md](sync-architecture.md)),
  which knows nothing of which event came later, so an equal stamp could let the earlier event win — and would
  leave a recreated row indistinguishable from the one before the delete.
  Because those stamps can run a few milliseconds ahead of the clock, every other local write to such a row moves
  its stamp forward the same way, so an edit made right after them is still the newest.
- **Every other last-write-wins stamp moves only forward on its row the same way**, `MAX(now, previous + 1)`:
  `feeds.custom_title_updated_at` / `folder_updated_at` / `sort_order_updated_at` (`updateCustomTitle`,
  `updateFolder`, `updateSortOrder`, `updateFolderAndSortOrder`), `articles.read_at` / `starred_at`
  (`updateReadStatus`, `updateReadStatusByIds`, `updateStarStatus`, the `markAllRead*` queries) and
  `deleted_updated_at` (`softDeleteExpired`) — `starred_at` and `deleted_updated_at` rank one merge group together, so
  a star or a cleanup stamps above *both* of them —, `articles.body_updated_at` (the article upsert, moved only when a
  body column changes), `tags.name_updated_at` / `color_updated_at` / `sort_order_updated_at`
  and `folders.name_updated_at` / `sort_order_updated_at` (each moved only when its own field changes — `upsert`,
  `updateSortOrder`, `upsertByName`), and `feed_tags.updated_at` / `global_settings.updated_at` (`upsert`).
  Without it, a write stamped at the value the row already holds — two writes within one millisecond, or one made
  right after a merge brought that very stamp in — could lose to the older value on the next merge, whose tie rule
  knows nothing of which write came later. A tie is thereby left only to two devices' independent writes.
- Booleans and timestamps are **INTEGER (`Long`)**. Booleans are 0/1; times are Unix milliseconds.
- Schema version is managed by `PRAGMA user_version` (currently 3). On desktop, `DatabaseDriverFactory`
  drives create/migrate manually off this pragma; on Android, `AndroidSqliteDriver` drives it internally via its own
  `onCreate`/`onUpgrade` callbacks. Version 2 adds `articles.deleted_at` / `deleted_updated_at` via `1.sqm`.
  Version 3 (`2.sqm`) adds the sync bookkeeping (`sync_outbox`, `sync_capture`, `sync_merging`, `sync_applied`,
  `sync_cursor`, `ks_search_pending`, `ks_search_awaiting_body`, `ks_feed_folder_raw`, and the capture triggers), moves `feeds`' `etag` / `last_modified` /
  `error_count` / `last_error` into `feed_fetch_state` (`ALTER TABLE … DROP COLUMN`, SQLite 3.35+), **rebuilds
  `tags` / `folders`** — adding their per-field stamps (initialized from `updated_at`) and dropping `UNIQUE(name)` in
  one table swap, with the children evacuated and restored around it (foreign keys stay on: `feeds.folder_id` is
  saved and NULLed only where its folder exists, the `feed_tags` rows whose tag exists move to a work table, and a
  child whose parent was never there survives untouched, so no new `foreign_key_check` violation appears; a link
  whose feed is gone cannot be restored and is dropped) —, adds `articles.body_updated_at` (initialized from
  `updated_at`), registers in `ks_search_pending` every
  article whose `search_text` still equals its raw `content` / `summary` (written before HTML stripping), and — in
  the `AfterVersion(2)` callback every migration runs (`domain/TagFolderIdRekey.kt`, wired as
  `KERYX_SCHEMA_CALLBACKS` into every platform's driver and `DatabaseMerger`'s cloud-file migration) — **re-keys
  every tag and folder onto the id its name derives from** (`IdGenerator.tagId` / `folderId`): the row is copied
  under the new id, `feed_tags.tag_id` / `feeds.folder_id` move with it, the old row is deleted, and each move is
  recorded in `ks_id_remap` so `SettingsRepository` rewrites the same ids in `local_settings.json`
  (`lastFilter`, `collapsedFolderIds`, `expandedTagIds`) once at startup and then clears the table. Idempotent:
  running again changes nothing. Version 3 is unreleased; a pre-release development environment that already
  ran version 3 with the interim random ids is NOT re-keyed (the callback fires only when crossing version 2) —
  such an environment needs its cloud sync data reset once.
  (SQLDelight derives the version from the highest migration file + 1.) When the schema changes, add a `.sqm` file
  (`<from-version>.sqm`) and the version bumps automatically; `domain/MergeSchema.EXPECTED_SCHEMAS` (which
  `DatabaseMerger.validateSchema` checks against), `domain/TransportSchema` (the delta file schema and its
  migrations) and the capture triggers' column lists must be updated in lockstep.
  A file whose `user_version` is newer than the running build's schema is never opened: desktop throws
  `DatabaseTooNewException` (`data/local/DatabaseSchemaGuard.kt`) before writing anything; Apple does the same through
  the same guard — `DatabaseDriverFactory.apple.kt`'s `createDriver` calls `requireSupportedSchemaVersion` on the
  file's `user_version` before `NativeSqliteDriver` opens it, and `KeryxSdk.start()` unwraps it from Koin's wrapping
  with `findDatabaseTooNew` (as desktop's `main.kt` does) so it reaches Swift as a thrown error; and Android's open
  helper refuses a downgrade by default.

## Table List

`feeds` / `feed_fetch_state` / `articles` / `tags` / `feed_tags` / `folders` / `global_settings` / `sync_state` /
the sync bookkeeping tables / `articles_fts` (virtual).

### feeds

`id`(PK, UUID), `url`(UNIQUE), `site_url`, `title`, `description`, `favicon_url`,
`custom_title`, `folder_id`(FK→folders, nullable),
`deleted_at`, `updated_at`, `created_at`, `sort_order`, `folder_updated_at`(nullable),
`sort_order_updated_at`(nullable), `custom_title_updated_at`(nullable), `deleted_updated_at`(nullable).

- `url` is the unique key for a subscription but not the primary key (so the same feed can be treated as the same entity even if the URL changes).
- `id` is deterministically generated from (redirect-resolved) `url` as **UUIDv5** at subscription time (`IdGenerator.feedId`). The same feed gets the same id on all devices, so sync merge (`feeds` matched by `id`) can converge independently subscribed feeds, and article ids (derived from `feed_id`) also match. Re-subscribing to an existing URL reuses the existing id, so the existing row is left in place (deterministic generation only applies to new rows).
- Logical deletion via `deleted_at`. Re-subscribing resets it to NULL.
- `folder_id` is the folder a feed belongs to (1 feed = max 1 folder). This is an independent classification axis from tags (`feed_tags`, many-to-many). `feeds.upsert` (for subscription/refresh) does not touch this column at all; folder assignment changes are only made via `feeds.updateFolder` / `updateFolderAndSortOrder` (so they are not overwritten by subscription/refresh).
- `folder_updated_at` / `sort_order_updated_at` / `custom_title_updated_at` / `deleted_updated_at` are
  **per-field timestamps for user-edited fields (folder assignment, sort order, manual rename, subscription state)**
  (same concept as `read_at` / `starred_at` for articles). Separated from the row-level `updated_at` (updated by content refresh) so that a refresh on another device cannot overwrite these manual operations in sync merge. Each field-updating query only updates its corresponding column, and `upsert` (refresh) touches none of them — except that a re-subscribe, which moves the feed to the end of its group, stamps `sort_order_updated_at` along with the new `sort_order`, so the new position reaches other devices. In sync merge, each field is independently last-write-wins using these columns (see [sync-architecture.md](sync-architecture.md)). NULL means "no such event" (distinct from "happened at time 0"). Unsubscription is `softDelete`; re-subscription uses `stampResubscribed` inside `subscribeFeed` to stamp `deleted_updated_at`.

### feed_fetch_state (device-local, non-sync)

`feed_id`(PK, FK→feeds), `etag`, `last_modified`, `error_count`(default 0), `last_error`.

- What this device learned the last time it fetched the feed. It is never synced: another device's validators
  (`etag` / `last_modified`) would make this device send a conditional request for content it never fetched, and an
  error belongs to the device that saw it. A row is created on the first fetch; a feed without one is treated as
  "never fetched, no errors".
- The feed list flags a feed as erroring whenever `error_count > 0` (or the feed is gone, see below) — that is,
  when fetching it failed **on this device**. `FEED_TIMEOUT_RETRY_COUNT` is a separate, unrelated constant — the
  fetch-attempt retry budget `FeedFetcher` spends before giving up on a single fetch (`core/Constants.kt`).
- `last_error` has two uses: the raw error text of the last failed fetch, and — for a 410 Gone feed — the fixed internal marker `FEED_ERROR_REASON_GONE` (`"gone"`, written by `markGone`). A 410 deliberately does not bump `error_count` (it is permanent, not a retry candidate), so this marker is the only signal the feed is gone, and it is what makes the feed list flag it (with its own localized tooltip — the column value itself is never shown to the user). Cleared by `resetErrorCount` on the next successful fetch.

### articles

`id`(PK), `feed_id`(FK→feeds), `guid`, `url`, `title`, `summary`, `content`, `author`,
`published_at`, `thumbnail_url`, `is_read`, `read_at`, `is_starred`, `starred_at`, `cached_at`,
`search_text`, `updated_at`, `created_at`, `deleted_at`, `deleted_updated_at`, `body_updated_at`(nullable).
`UNIQUE(feed_id, guid)`.
Indexes: `feed_id`, `is_read`, `is_starred`, and a composite `(published_at DESC, created_at DESC, id DESC)` for list ordering.

- `id` is deterministically generated from `(feed_id, guid)` as **UUIDv5** (`IdGenerator.articleId`). The same article gets the same ID on all devices, so sync merge (articles matched by `id`) can propagate read/star states via last-write-wins. Existing rows keep their id via `upsert`'s `ON CONFLICT(feed_id, guid)` — deterministic generation only applies to new rows.
- Read conflict resolution is last-write-wins via `read_at`; the star is part of the retention group (below), ranked with `starred_at`. Both are stamped only when the state actually changes (`updateReadStatus` / `updateStarStatus` are guarded on it), so re-opening an already-read article does not move them.
- `body_updated_at` is the body's own last-write-wins stamp — for `content`, `summary`, `title`, `url`, `author`,
  `published_at` and `thumbnail_url`, which the merge takes together from one version. The article upsert
  (`insert`, `insertIfAbsent`) sets it to the write's time on a new row, and on an existing row moves it forward
  (`MAX(now, previous + 1)`) only when a body column actually changes; read, star and deletion writes never touch it.
  So a device that merely opened an article cannot make its older body beat another device's newer one. `updated_at`
  stays the row's generic stamp (moved by every write) and is no merge group's key. The column is nullable only
  because `2.sqm` adds it (initialized from `updated_at`); every write sets it.
- `content` is displayed in preference to `summary`. If both are NULL, the reader shows a localized "no content" placeholder in place (with a link/button to open the article in the external browser); nothing opens automatically.
- `search_text` = the HTML-stripped plain text of `content` (falling back to `summary`), or `""` if both are NULL. Computed at insert/update time (`ArticleRepository`, `HtmlText.toPlainText`). It is **device-local**: never synced, since it is derived. An article whose body a sync merge filled in or changed is listed in `ks_search_pending`, and its `search_text` is recomputed by the next `FtsManager.indexMissing()` before it indexes anything (a newly inserted one starts as `""`); `indexMissing()` skips rows still listed.
- Logical deletion via `deleted_at` (NULL = alive). Cache cleanup (`softDeleteExpired`) is the only **local**
  originator of a deletion; starred articles are never deleted. (Sync merge also writes `deleted_at`, propagating a
  deletion made on another device — see below.) `deleted_updated_at` is a field-specific last-wins
  timestamp for the delete/undelete event (like `read_at` / `starred_at`, and like `feeds.deleted_updated_at`), kept
  separate from `updated_at` so a content refresh / read change can't clobber a deletion during the sync merge.
  In the merge the star and the deletion are **one group** (the *retention* group: `is_starred`, `starred_at`,
  `deleted_at`, `deleted_updated_at`), ranked by `MAX(starred_at, deleted_updated_at)` (NULL as 0): the newer operation
  wins and the whole group comes from it. A star clears `deleted_at` (`updateStarStatus`) — starring keeps the
  article, so a star on an article a merge has just deleted revives it, here and on every device — while an unstar
  leaves the deletion alone; both a star and a cleanup stamp above both stamps of their row, so each wins the group
  it changed. A row is therefore never both starred and deleted. An article revived by a star elsewhere and then
  unstarred stays visible until the next cache cleanup deletes it again. This makes the deletion propagate across
  devices instead of the article being resurrected from the cloud on the next sync. Rows are
  never physically removed here (physical GC of old tombstones is future work); the row is kept so its deletion — and
  starred-article references — survive. All UI/list/search queries filter `deleted_at IS NULL`; `upsert` (feed refresh)
  never touches `deleted_at`, so a refresh cannot revive a deleted article.

### tags / feed_tags

`tags`: `id`(PK), `name`, `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`,
`name_updated_at`, `color_updated_at`, `sort_order_updated_at`.

- `id` is deterministically generated from the creation-time name as **UUIDv5** (`IdGenerator.tagId`,
  case-sensitive, exact). The same tag gets the same id on all devices, so the sync merge (matched by
  id) converges same-named tags created independently on each device into one row. A row keeps its id
  when renamed — so its id is the one its ORIGINAL name derived, and a later creation of that original
  name falls back to a random UUIDv4 (`tags.insertIfNameAbsent` picks between the two in the statement
  itself) rather than colliding. Existing random ids were re-keyed onto the derived ones by the v2→v3
  migration (below).
- `name` is **not** UNIQUE (schema version 3 dropped the constraint): same-named rows coexist where a
  rename crosses a same-named creation, and the UI tells them apart with an ordinal. A non-unique index
  `tags_name` backs the by-name lookups. Every path that resolves a tag by name (creation, reactivation)
  lands on the one **canonical row** of the name: an alive row over a deleted one, then the earliest
  `created_at`, then the smallest id (`tags.getByName`). The UI still warns the user about duplicates among
  live rows (`NameValidation`), so a second same-named row is created by a rename crossing a creation, not
  by a create dialog.
- Name, color and sort order are each last-write-wins by their own stamp (like the user-edited fields of `feeds`),
  so two devices changing different fields of the same tag both keep their change. `updated_at` stamps creation,
  deletion and reactivation.
`feed_tags`: `feed_id` + `tag_id`(composite PK), `deleted_at`, `updated_at`. Tag attach/detach is represented via `deleted_at`.

### folders

`id`(PK), `name`, `sort_order`, `deleted_at`, `updated_at`, `created_at`, `name_updated_at`,
`sort_order_updated_at`.

- `id` is deterministically generated from the creation-time name as **UUIDv5**
  (`IdGenerator.folderId`), exactly as `tags.id` is (its own namespace, so a tag and a folder of the
  same name never share an id); the same fallback to a random id applies when the derived id is held.
- `name` is **not** UNIQUE (schema version 3 dropped the constraint), for the same reason as `tags.name`: same-named
  rows coexist where a rename crosses a creation, shown with an ordinal, backed by the non-unique index `folders_name`, and every by-name
  lookup lands on the canonical row of the name (see `tags` above and `folders.getByName`).
- Name and sort order are each last-write-wins by their own stamp; `updated_at` stamps creation, deletion and
  reactivation, as for `tags`.
- Independent classification axis from tags. Since 1 feed = max 1 folder (`feeds.folder_id`, not many-to-many), there is no junction table like `feed_tags`.
- When deleting a folder, `FolderRepository.deleteFolder` resets the linked `feeds.folder_id` to NULL in the same transaction, then logically deletes the folder itself. However, this only affects the local DB, so after sync, feeds on other devices may point to a logically deleted / non-existent folder. The UI (`groupFeedsByFolder`) defensively treats this as "no folder".

### global_settings (KVS, sync target)

`key`(PK), `value`(plain string — an int/boolean encoded as its `toString()`, not JSON), `updated_at`. Known keys:

| Key | Type | Default |
| --- | --- | --- |
| `read_timeout_seconds` | INTEGER | 30 |
| `cache_retention_days` | INTEGER \| "null" | 30 ("null" = unlimited) |
| `article_list_default_unread_only` | boolean | false |

### sync_state (KVS, non-sync)

`key`(PK), `value`. Known keys: `last_synced_at` (Unix millis), `ks_device_id` (this device's sync identity in the
current generation), `ks_generation`, `ks_last_seq` (the last delta this device committed), `ks_attempt` (the file
being sent: number, name, outbox high-water mark, whether it is the full send), `ks_pointer_rev`, `ks_received_at`
(when this device last completed a receive), `ks_bases` (JSON: what this device knows of its generation's bases),
`ks_legacy_stub_rev`, `sync_index_pending` (present from a sync merge's commit until the search index has caught up
with it, so a failed indexing is retried by the next sync). Device-local; cleared (all but `last_synced_at` and
`sync_index_pending`) when the cloud connection is disconnected or switched.

### Sync bookkeeping (device-local, non-sync)

| Table | Content |
| --- | --- |
| `sync_outbox` | `seq`(PK, AUTOINCREMENT), `tbl`, `pk`, `pk2` (`feed_tags` only: the tag id), `heavy` — one row per local change to a synced row, appended by triggers |
| `sync_capture` | a single row: `enabled` (recording on while connected), `full_pending` (a full send is due) |
| `sync_merging` | a marker row present only inside a merge transaction, which suppresses recording |
| `sync_applied` | `name`(PK) — cloud files already merged |
| `sync_cursor` | `device`(PK), `seq` — per device, the highest delta number up to which everything is merged |
| `ks_search_pending` | `rowid`(PK) — articles whose `search_text` must be recomputed |
| `ks_search_awaiting_body` | `id`(PK) — articles a merge inserted without a body (a light row), kept out of the search index until one arrives or the daily rebuild indexes them |
| `ks_feed_folder_raw` | `feed_id`(PK), `folder_updated_at`, `folder_id` — per feed, the raw folder version (as the winning sync file carried it) that `feeds.folder_id`'s resolved value came from, so the merge ranks raw against raw; valid only while `folder_updated_at` equals the feed's own |
| `ks_id_remap` | `kind` + `old_id`(composite PK), `new_id` — the tag/folder id re-key the v2→v3 migration ran (`domain/TagFolderIdRekey.kt`): the old random id and the name-derived one it became, so `SettingsRepository` rewrites the same ids in `local_settings.json` (`lastFilter`, `collapsedFolderIds`, `expandedTagIds`) once at startup after the migration and then clears the table. Populated only by that migration; empty on every fresh database |

None of these, nor `sync_state`, `feed_fetch_state` or `articles_fts`, ever leaves the device: delta and base files
are built from the synced tables only (see [sync-architecture.md](sync-architecture.md)).

### articles_fts (FTS5 virtual table, outside SQLDelight management)

```sql
CREATE VIRTUAL TABLE articles_fts USING fts5(
  title, search_text,
  content='articles', content_rowid='rowid',
  tokenize='trigram'
);
INSERT INTO articles_fts(articles_fts) VALUES('rebuild');
```

External content mode keeps only the index, referencing `articles.search_text` for body text.

- **Never DROP `articles_fts` on the live DB.** It never leaves the device: transport files are built from the
  synced tables only — see "FTS5 Handling" in [sync-architecture.md](sync-architecture.md).
- After feed refresh or sync merge, `FtsManager.indexMissing()` **incrementally indexes only unindexed new
  articles**. Never use a full `'rebuild'` on a hot path — it is `O(total indexed text)` and too heavy to run there.
- Full rebuild (`rebuildIndex()` = `'rebuild'`) runs only in the daily idle pass (`local_settings.lastFtsRebuiltAt`
  24h gate + `ActivityCenter` idle), to rebuild stale existing rows whose body text changed since incremental
  indexing.
- `'rebuild'` is atomic and waits on `busy_timeout`, so a search running concurrently never regresses to zero
  results — as long as the rebuild releases its write lock within `busy_timeout`. A search that waits longer fails
  with `SQLITE_BUSY`, and `ArticleRepository.search` then returns no results (see
  [sync-architecture.md](sync-architecture.md) "FTS5 Handling").
**On startup, call `FtsManager.ensureIndexed()` to create the table on first run and backfill any missing rows.**
Android instead calls the cheaper `ensureIndexedIfTableAbsent()` on every process start (including a `WorkManager`
wakeup, up to ~96 times/day) — it skips straight to a no-op once the table already exists, instead of re-running
`indexMissing()`'s `O(articles)` scan on every wakeup.
The Apple SDK creates the table in `KeryxSdk.start` (`createTable()`, best effort) so it exists whatever the UI
does — `prepareSearchIndex()` runs only once the scene is active, and a subscription made before that must still
have a table to index into — and `prepareSearchIndex()` then backfills the missing rows.

`tokenize='trigram'` needs SQLite ≥3.34, which AOSP's own SQLite build never provides (it omits
FTS5 entirely, at any API level) — Android's `DatabaseDriverFactory` actual uses a bundled SQLite
instead. See `.claude/rules/android-sqlite-bundling.md` for the rationale and exit criteria.

**The trigram tokenizer produces no tokens at all for a query string under 3 characters** — a
`MATCH` against a 1- or 2-character string silently returns zero rows rather than erroring. Search's
minimum term length (`SEARCH_MIN_TERM_LENGTH` in `core/Constants.kt`, currently 2) is therefore
below the trigram floor (`TRIGRAM_MIN_TERM_LENGTH`, 3): a 2-character term is instead matched with a
`LIKE '%term%'` scan directly over `articles` (title and `search_text`, bind-parameterized and
escaped — never string-concatenated), bypassing `articles_fts` entirely. See `FtsSearch` (`data/local/`).
A query made up only of such short terms has no FTS rank to sort by, so results fall back to
`published_at DESC` and are capped at `SEARCH_FALLBACK_RESULT_LIMIT` (200) rather than left
unbounded. A query that mixes a 3+-character term with a 2-character one still runs through
`articles_fts MATCH` (rank-ordered) with the short term ANDed in as an extra `LIKE` filter on the
matched rows.

**Search is scoped to an `ArticleFilter`, not run over every article.** `FtsSearch.search(rawQuery,
scope)` (`data/local/FtsSearch.kt`) takes the currently selected `ArticleFilter` and, via
`articleScopeSql`, appends a `WHERE`-clause fragment matching the exact row set the corresponding
`articles.sq` query (`watchAll`/`watchStarred`/`watchByFeed`/`watchByTag`/`watchByFolder`) would
show as a plain, non-search article list for that same filter — including that those five queries
are not symmetric about joining `feeds`: `Starred` and `Feed` don't, so a starred article (or any
article) belonging to an unsubscribed (soft-deleted) feed still matches under those two scopes,
while `All`/`Tag`/`Folder` exclude it. The scope predicate is expressed as `EXISTS (...)` rather
than `feed_id IN (...)`, so a folder/tag scope spanning many feeds still binds at most one
parameter — relevant because it shares the same query's bound-parameter budget with the short-term
`LIKE` clauses above. `SEARCH_FALLBACK_RESULT_LIMIT` applies *within* the scope (the `WHERE` already
narrows the rows the `LIMIT` counts against), not before it, so a search scoped to one feed isn't
starved by an unrelated feed's hits filling the cap first. See "Search is orthogonal to
`ArticleFilter`" in [app-architecture.md](app-architecture.md) for how `HomeViewModel` decides which
scope to pass.

## local_settings.json (outside keryx.db, non-sync)

Location: directly under the app data directory (`AppDirs.appDataDir()`. macOS: `~/Library/Application Support/Keryx`).
Setup completion = file exists.

| Key | Type | Default |
| --- | --- | --- |
| `themeMode` | string | "system" |
| `fontSizeScale` | number | 1.0 |
| `refreshIntervalMinutes` | int | 30 |
| `startMinimized` | boolean | false |
| `cloudStorageType` | string\|null (`"dropbox"` / `"google_drive"` / `"onedrive"` / null=local only. `CloudStorageType.id`) | null |
| `notificationEnabled` | boolean | true |
| `lastCacheCleanupAt` | int\|null | null |
| `lastFtsRebuiltAt` | int\|null | null (24h gate for FTS full rebuild. Updated by daily heal) |
| `updateCheckIntervalHours` | int | 24 |
| `lastUpdateCheckAt` | int\|null | null |
| `windowWidth` / `windowHeight` / `windowX` / `windowY` | number\|null | null (the window's floating-state bounds, independent of `windowPlacement` — this is what a maximized/fullscreen window restores to once un-maximized) |
| `windowPlacement` | string | "floating" ("floating" \| "maximized" \| "fullscreen"; an unrecognized value restores as floating) |
| `feedListPaneWidth` / `articleListPaneWidth` | number | 260 / 360 |
| `collapsedFolderIds` | string[] | `[]` (folders default to *expanded*, so only the collapsed ones are tracked) |
| `expandedTagIds` | string[] | `[]` (tags are the opposite — they default to *collapsed*, keeping the sidebar short) |
| `lastFilter` | string\|null | null |
| `lastArticleId` | string\|null | null |
| `lastFocusedPane` | string\|null | null |
| `lastUnreadOnly` | boolean\|null | null (the article list's All/Unread mode; its Starred mode is session-only and never saved) |
| `lastUnreadOnlyStarred` | boolean\|null | null (scoped to the Starred filter alone, independent of `lastUnreadOnly`) |
| `lastNewestFirst` | boolean\|null | null |
| `appMenuBarVisible` | boolean\|null | null (Linux KDE Global Menu: null=auto (shown until `RegisterWindow` succeeds, then hidden); true/false=explicit override via Ctrl+M / the exported "Show Menu Bar" checkbox. No effect where no `com.canonical.AppMenu.Registrar` is present) |
| `syncOnMeteredNetwork` | boolean | false (whether automatic cloud sync also runs on a metered network — mobile data, tethering, and on Android a Wi-Fi the user marked as metered. Never overrides the OS's Data Saver / Low Data Mode. Offered only on Android and iOS; see "Network Gate" in [sync-architecture.md](sync-architecture.md)) |
| `faviconMissedAt` | object (feed id → Unix ms) | `{}` (when a refresh's favicon lookup last found none for a feed; that feed is not looked up again for `FAVICON_RETRY_INTERVAL_MS` (24 h). Entries older than that are dropped whenever one is added. Device-local, not synced) |

## Cache Cleanup

Based on `cache_retention_days`, runs in the background at startup if 24+ hours have passed since the last cleanup. Starred articles and the latest 10 articles per feed are preserved regardless of retention period. If `null` (unlimited), cleanup is skipped. Cleanup **soft-deletes** (stamps `articles.deleted_at` / `deleted_updated_at`) rather than physically removing rows, so the deletion propagates via the sync merge instead of being resurrected from the cloud. Physical reclamation of old tombstones is future work.

Cleanup decides by `cached_at` (the last time the article was seen in its feed). Sync carries a `cached_at` change
only when its day changes, so a device that does not refresh a feed itself can see another device's `cached_at` up to
about a day late, and may clean up an unstarred article at the edge of the retention period up to a day earlier than
when every sync carried the exact value. The starred and latest-10 protections are unaffected.

## Favicon / Thumbnail

Image binaries are not stored in the DB; only URLs are kept (non-sync target). Favicons are displayed (Coil3 `AsyncImage`); article thumbnail (`thumbnail_url`) display is still omitted in α.

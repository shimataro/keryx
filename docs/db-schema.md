# Keryx DB Schema

[日本語](db-schema.ja.md)

Target: local SQLite (managed by SQLDelight). `.sq` files are located at
`composeApp/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/`.

## Design Philosophy

- All tables are managed by SQLDelight (`.sq`). `articles_fts` is created/maintained separately via raw SQL (`FtsManager`).
- Logical deletion uses `deleted_at` (NULL = alive). Sync timestamp is `updated_at`.
- Booleans and timestamps are **INTEGER (`Long`)**. Booleans are 0/1; times are Unix milliseconds.
- Schema version is managed by `PRAGMA user_version` (currently 2). `DatabaseDriverFactory` drives create/migrate.
  Version 2 adds `articles.deleted_at` / `deleted_updated_at` via `1.sqm` (SQLDelight derives the version from the
  highest migration file + 1). When the schema changes, add a `.sqm` file (`<from-version>.sqm`) and the version bumps
  automatically; `DatabaseMerger.EXPECTED_SCHEMA` / `validateSchema` must be updated to the new version in lockstep.

> **Backward compatibility with the legacy version is not considered** (a user decision). The schema is the best reasonable form.

## Table List

`feeds` / `articles` / `tags` / `feed_tags` / `folders` / `global_settings` / `sync_state` / `articles_fts` (virtual).

### feeds

`id`(PK, UUID), `url`(UNIQUE), `site_url`, `title`, `description`, `favicon_url`, `etag`,
`last_modified`, `error_count`, `last_error`, `custom_title`, `folder_id`(FK→folders, nullable),
`deleted_at`, `updated_at`, `created_at`, `sort_order`, `folder_updated_at`(nullable),
`sort_order_updated_at`(nullable), `custom_title_updated_at`(nullable), `deleted_updated_at`(nullable).

- `url` is the unique key for a subscription but not the primary key (so the same feed can be treated as the same entity even if the URL changes).
- `id` is deterministically generated from (redirect-resolved) `url` as **UUIDv5** at subscription time (`IdGenerator.feedId`). The same feed gets the same id on all devices, so sync merge (`feeds` matched by `id`) can converge independently subscribed feeds, and article ids (derived from `feed_id`) also match. Previously, random UUIDv4 at subscription time caused divergent ids when two devices independently subscribed to the same URL, preventing convergence. The version nibble of v5 guarantees no collision with legacy v4 ids. Re-subscribing to an existing URL reuses the existing id, so the existing row is left in place (deterministic generation only applies to new rows).
- Logical deletion via `deleted_at`. Re-subscribing resets it to NULL.
- `error_count` reaching the constant `FEED_TIMEOUT_RETRY_COUNT` is treated as an error.
- `folder_id` is the folder a feed belongs to (1 feed = max 1 folder). This is an independent classification axis from tags (`feed_tags`, many-to-many). `feeds.upsert` (for subscription/refresh) does not touch this column at all; folder assignment changes are only made via `feeds.updateFolder` / `updateFolderAndSortOrder` (so they are not overwritten by subscription/refresh).
- `folder_updated_at` / `sort_order_updated_at` / `custom_title_updated_at` / `deleted_updated_at` are
  **per-field timestamps for user-edited fields (folder assignment, sort order, manual rename, subscription state)**
  (same concept as `read_at` / `starred_at` for articles). Separated from the row-level `updated_at` (updated by content refresh) so that a refresh on another device cannot overwrite these manual operations in sync merge. Each field-updating query only updates its corresponding column, and `upsert` (refresh) touches none of them. In sync merge, each field is independently last-write-wins using these columns (see [sync-architecture.md](sync-architecture.md)). NULL means "no such event" (distinct from "happened at time 0"). Unsubscription is `softDelete`; re-subscription uses `stampResubscribed` inside `subscribeFeed` to stamp `deleted_updated_at`.

### articles

`id`(PK), `feed_id`(FK→feeds), `guid`, `url`, `title`, `summary`, `content`, `author`,
`published_at`, `thumbnail_url`, `is_read`, `read_at`, `is_starred`, `starred_at`, `cached_at`,
`search_text`, `updated_at`, `created_at`, `deleted_at`, `deleted_updated_at`. `UNIQUE(feed_id, guid)`.
Indexes: `feed_id` / `is_read` / `is_starred` / `published_at DESC`.

- `id` is deterministically generated from `(feed_id, guid)` as **UUIDv5** (`IdGenerator.articleId`). The same article gets the same ID on all devices, so sync merge (articles matched by `id`) can propagate read/star states via last-write-wins. **Reason**: Previously, article IDs were random UUIDv4 generated at fetch time, so when two devices independently fetched the same article they got different IDs, and the guid collision guard in merge skipped them, preventing read-state propagation. The version nibble of v5 guarantees no collision with legacy v4 IDs. The ID generation change only affects new rows; existing rows keep their old ID via `upsert`'s `ON CONFLICT(feed_id, guid)`.
- Read/star conflict resolution is last-write-wins via `read_at` / `starred_at`.
- `content` is displayed in preference to `summary`. If both are NULL, open in external browser.
- `search_text = COALESCE(content, summary, '')`. Computed at insert/update time.
- Logical deletion via `deleted_at` (NULL = alive). Cache cleanup is the **only** writer of `deleted_at`
  (`softDeleteExpired`); starred articles are never deleted. `deleted_updated_at` is a field-specific last-wins
  timestamp for the delete/undelete event (like `read_at` / `starred_at`, and like `feeds.deleted_updated_at`), kept
  separate from `updated_at` so a content refresh / read / star change can't clobber a deletion during the sync merge.
  In the merge, deletion propagates by last-write-wins on `deleted_updated_at`, but a star newer than the deletion
  **revives** the article (`deleted_at` → NULL), since cleanup only ever deletes non-starred articles. This makes the
  deletion propagate across devices instead of the article being resurrected from the cloud on the next sync. Rows are
  never physically removed here (physical GC of old tombstones is future work); the row is kept so its deletion — and
  starred-article references — survive. All UI/list/search queries filter `deleted_at IS NULL`; `upsert` (feed refresh)
  never touches `deleted_at`, so a refresh cannot revive a deleted article.

### tags / feed_tags

`tags`: `id`(PK), `name`(UNIQUE), `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`.
`feed_tags`: `feed_id` + `tag_id`(composite PK), `deleted_at`, `updated_at`. Tag attach/detach is represented via `deleted_at`.

### folders

`id`(PK), `name`(UNIQUE), `sort_order`, `deleted_at`, `updated_at`, `created_at`.

- Independent classification axis from tags. Since 1 feed = max 1 folder (`feeds.folder_id`, not many-to-many), there is no junction table like `feed_tags`.
- When deleting a folder, `FolderRepository.deleteFolder` resets the linked `feeds.folder_id` to NULL in the same transaction, then logically deletes the folder itself. However, this only affects the local DB, so after sync, feeds on other devices may point to a logically deleted / non-existent folder. The UI (`groupFeedsByFolder`) defensively treats this as "no folder".

### global_settings (KVS, sync target)

`key`(PK), `value`(JSON string), `updated_at`. Known keys:

| Key | Type | Default |
| --- | --- | --- |
| `read_timeout_seconds` | INTEGER | 30 |
| `cache_retention_days` | INTEGER \| "null" | 30 ("null" = unlimited) |
| `article_list_default_unread_only` | boolean | false |

### sync_state (KVS, non-sync)

`key`(PK), `value`. Known keys: `last_synced_at` (Unix millis), `cloud_file_rev` (cloud file revision.
Dropbox uses `rev`, Google Drive uses file resource's `version`).

> The issue that read/write to this table was unimplemented has been fixed; the current implementation actually records these values.

### articles_fts (FTS5 virtual table, outside SQLDelight management)

```sql
CREATE VIRTUAL TABLE articles_fts USING fts5(
  title, search_text,
  content='articles', content_rowid='rowid',
  tokenize='trigram'
);
INSERT INTO articles_fts(articles_fts) VALUES('rebuild');
```

External content mode keeps only the index, referencing `articles.search_text` for body text. **Never DROP `articles_fts` on the live DB** (exclusion from upload is done by dropping it on the `VACUUM INTO` snapshot copy side. See "FTS5 handling" in [sync-architecture.md](sync-architecture.md)). After feed refresh / sync merge, `FtsManager.indexMissing()` **incrementally indexes only unindexed new articles** (do not use full `'rebuild'` on every hot path because it is O(total indexed text) and heavy). Full rebuild (`rebuildIndex()` = `'rebuild'`) is only done in the daily idle pass (`local_settings.lastFtsRebuiltAt` 24h gate + `ActivityCenter` idle) in `main.kt`, rebuilding stale existing rows (body text updated since incremental indexing). `'rebuild'` is atomic + `busy_timeout` wait, so running searches do not regress to zero results.
**On startup, call `FtsManager.ensureIndexed()` to create the table on first run and backfill any missing rows**.

## local_settings.json (outside keryx.db, non-sync)

Location: directly under the app data directory (`AppDirs.appDataDir()`. macOS: `~/Library/Application Support/Keryx`).
Setup completion = file exists.

| Key | Type | Default |
| --- | --- | --- |
| `themeMode` | string | "system" |
| `fontSizeScale` | number | 1.0 |
| `refreshIntervalMinutes` | int | 30 |
| `startMinimized` | boolean | false |
| `cloudStorageType` | string\|null (`"dropbox"` / `"google_drive"` / null=local only. `CloudStorageType.id`) | null |
| `notificationEnabled` | boolean | true |
| `lastCacheCleanupAt` | int\|null | null |
| `lastFtsRebuiltAt` | int\|null | null (24h gate for FTS full rebuild. Updated by daily heal) |
| `windowWidth` / `windowHeight` | number\|null | null |
| `feedListPaneWidth` / `articleListPaneWidth` | number | 260 / 360 |
| `appMenuBarVisible` | boolean\|null | null (Linux KDE Global Menu: null=auto (shown until `RegisterWindow` succeeds, then hidden); true/false=explicit override via Ctrl+M / the exported "Show Menu Bar" checkbox. No effect where no `com.canonical.AppMenu.Registrar` is present) |

## Cache Cleanup

Based on `cache_retention_days`, runs in the background at startup if 24+ hours have passed since the last cleanup. Starred articles and the latest 10 articles per feed are preserved regardless of retention period. If `null` (unlimited), cleanup is skipped. Cleanup **soft-deletes** (stamps `articles.deleted_at` / `deleted_updated_at`) rather than physically removing rows, so the deletion propagates via the sync merge instead of being resurrected from the cloud. Physical reclamation of old tombstones is future work.

## Favicon / Thumbnail

Image binaries are not stored in the DB; only URLs are kept (non-sync target). Favicons are displayed (Coil3 `AsyncImage`); article thumbnail (`thumbnail_url`) display is still omitted in α.

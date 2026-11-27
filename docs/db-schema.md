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
  `deleted_updated_at` (`softDeleteExpired`, and the undelete of `updateStarStatus`'s unstar) — each above its own
  previous value, since the star and the deletion are separate merge groups —, `articles.body_updated_at` (the article upsert, moved only when a
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
  `sync_cursor`, `ks_search_pending`, `ks_search_awaiting_body`, `ks_feed_folder_raw` with the view and triggers that
  resolve `feeds.folder_id` from it, and the capture triggers), moves `feeds`' `etag` / `last_modified` /
  `error_count` / `last_error` into `feed_fetch_state` (`ALTER TABLE … DROP COLUMN`, SQLite 3.35+), **rebuilds
  `tags` / `folders`** — adding their per-field stamps (initialized from `updated_at`), dropping `UNIQUE(name)` and
  keeping each row's pre-upgrade id in `v2_id` in
  one table swap, with the children evacuated and restored around it (foreign keys stay on: `feeds.folder_id` is
  saved and NULLed only where its folder exists, the `feed_tags` rows whose tag exists move to a work table, and a
  child whose parent was never there survives untouched, so no new `foreign_key_check` violation appears; a link
  whose feed is gone cannot be restored and is dropped) —, adds `articles.body_updated_at` (initialized from
  `updated_at`), records the star of every article both starred and deleted as removed at its deletion (`is_starred`
  0, `starred_at` the later of its two stamps — see "articles" below), registers in `ks_search_pending` every
  article whose `search_text` still equals its raw `content` / `summary` (written before HTML stripping), and — in
  the `AfterVersion(2)` callback every migration runs (`domain/TagFolderIdRekey.kt`, wired as
  `KERYX_SCHEMA_CALLBACKS` into every platform's driver and `DatabaseMerger`'s cloud-file migration) — **re-keys
  every tag and folder onto the id its name derives from** (`IdGenerator.tagId` / `folderId`): the row is copied
  under the new id (its `v2_id` carried along), `feed_tags.tag_id` / `feeds.folder_id` move with it, the old row is
  deleted, and each move is recorded in `ks_id_remap` so `SettingsRepository` rewrites the same ids in
  `local_settings.json` (`lastFilter`, `collapsedFolderIds`, `expandedTagIds`) once the re-key table has loaded in the background after startup (see `ks_id_remap` below);
  that table is kept, so the rewrite is idempotent. The old id also stays on the row as `v2_id`, and the pair is
  recorded as an identity edge in `ks_v2_edge`, through which the sync merge links two devices that renamed the same
  pre-upgrade row differently before upgrading (see "tags / feed_tags" below).
  Idempotent:
  running again changes nothing.
  (SQLDelight derives the version from the highest migration file + 1.) When the schema changes, add a `.sqm` file
  (`<from-version>.sqm`) and the version bumps automatically; `domain/MergeSchema.EXPECTED_SCHEMAS` (which
  `DatabaseMerger.validateSchema` checks against), `domain/TransportSchema` (the delta file schema and its
  migrations) and the capture triggers' column lists must be updated in lockstep. A new synced table or column also gets
its merge field group and stamp in the same change: a column in no group changes only through an explicit fold rule
(`RowFold`: the earliest `created_at`, an article's latest `cached_at` / `updated_at`, the greatest non-NULL `v2_id`),
and one with neither diverges between devices. (A key column — an id, or a reference to one such as `feed_tags.feed_id` —
is not a value: an identity merge moves it onto the canonical id.)
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
- `id` is deterministically generated from (redirect-resolved) `url` as **UUIDv5** at subscription time (`IdGenerator.feedId`). The same feed gets the same id on all devices, so sync merge (`feeds` matched by `id`) can converge independently subscribed feeds, and article ids (derived from `feed_id`) also match. Re-subscribing to an existing URL reuses the existing id, so the existing row is left in place (deterministic generation only applies to new rows). A new subscription takes a random id (`IdGenerator.newId`) instead when the derived id is taken (`feeds.isIdTaken`): held by another URL's row — a feed whose URL a permanent redirect has since changed, which an upsert would otherwise rewrite — or mapped away by `ks_id_remap`, so no row ever carries an id a write would resolve onto another feed. The choice is made from the subscribe transaction's own reads.
- **A feed's id can move.** A permanent redirect keeps the id, so a feed subscribed anew at a moved feed's URL holds that URL under another id. Two feed ids that held one URL are recorded as an identity edge in `ks_feed_edge`, and a sync merge — or a refresh whose same-host permanent redirect lands on another feed's URL — moves every feed of the identity onto its canonical id (the smallest id), together with its articles, tag links, fetch state and raw folder assignment; the move is recorded in `ks_id_remap` (kind `feed`) and the old row is deleted (see "Identities of feeds and articles" in [sync-architecture.md](sync-architecture.md)). So that an id held from before a move still reaches its row, **every write that takes a feed id resolves it** in the statement itself, as tag and folder writes do — `feeds.sq`: `softDelete`, `stampResubscribed`, `reviveAfterMerge`, `updateCustomTitle`, `updateFaviconUrlIfMissing`, `updateContent`, `updateUrl`, `recordRedirectEdge`, `updateFolder`, `updateSortOrder`, `updateFolderAndSortOrder`; `feed_fetch_state.sq`: `upsertValidators`, `incrementErrorCount`, `resetErrorCount`, `markGone`; `feed_tags.sq`: `upsert`; `articles.sq`: `insert`, `insertIfAbsent`, `markAllReadByFeed` (and the list query `watchByFeed`). `feeds.resolveId` makes the same resolution as a read (`FeedRepository.resolveFeedId`), for a saved feed filter restored before the published re-key map has loaded and for a refresh of a row rendered before the move. Where a read needs the row too, it is resolved in the same statement, so a merge committing in between cannot hand back an id whose row it has just deleted: `feeds.getByIdResolved` (`FeedRepository.getFeedByIdResolved`, a refresh by id) and `feeds.groupForMove` (the destination group of a move, with the moved and target ids resolved among its rows — `moveFeed`). `feeds.reviveAfterMerge` revives a redirect-merged feed that came out deleted while the user still had either feed, as a stamped re-subscribe.
- `updated_at` is the content group's last-write-wins stamp, and the capture trigger does not watch it, so it moves on an existing row only together with a change that is captured: `upsert` moves it only when a column it writes changes, `updateUrl` writes only when the URL changes (and not while another feed holds it), and `updateContent` (a refresh's content write) only when its site URL, title or description changes. A stamp moved without being sent would beat a peer's later content change here and lose to it there.
- Logical deletion via `deleted_at`. Re-subscribing resets it to NULL.
- `folder_id` is the folder a feed belongs to (1 feed = max 1 folder). This is an independent classification axis from tags (`feed_tags`, many-to-many). `feeds.upsert` (for subscription/refresh) does not touch this column at all; folder assignment changes are only made via `feeds.updateFolder` / `updateFolderAndSortOrder` (so they are not overwritten by subscription/refresh).
- **`folder_id` / `folder_updated_at` are a cache.** A feed's folder assignment is stored raw in `ks_feed_folder_raw`
  (see "Sync bookkeeping" below) — the folder id and stamp as the winning version wrote them, even when that folder is
  soft-deleted — and that row is the source of truth: `updateFolder` / `updateFolderAndSortOrder` write it, the sync
  merge records into it, and a send carries it. The raw value is never rewritten after it is written — not mapped
  onto a canonical id when the merge moves a folder identity, not replaced when this device holds no row for it — so
  a tie between two assignments is settled by the same values everywhere (see "Merge" in
  [sync-architecture.md](sync-architecture.md)); only a pre-delta file's orphan reference is recorded as NULL.
  `feeds.folder_id` is resolved from it by the view `ks_feed_folder_resolved` — the raw folder id mapped one hop
  through `ks_id_remap`, while that folder exists and is alive, else NULL — and `folder_updated_at` is the raw stamp;
  triggers rewrite both in the same statement whenever the raw row changes, a folder is inserted, deleted or revived,
  or a folder entry of `ks_id_remap` changes, so a
  feed assigned to a deleted folder shows as "no folder" and is back in it when the folder is revived (by a merge or
  by recreating it by name). Deleting a folder, on whichever device, also moves its feeds to "no folder" (a raw
  change that syncs like any move; see `FolderRepository.deleteFolder` under "folders"), so a revival brings back only
  the feeds whose raw assignment still names the folder — for example one another device moved into it concurrently
  with the delete. Since the triggers keep these two columns derived, the capture trigger ignores them; a raw change is captured
  instead. `updateFolder` writes the feeds row first with the requested folder, so its FOREIGN KEY still rejects a
  folder that does not exist.
- `folder_updated_at` / `sort_order_updated_at` / `custom_title_updated_at` / `deleted_updated_at` are
  **per-field timestamps for user-edited fields (folder assignment, sort order, manual rename, subscription state)**
  (same concept as `read_at` / `starred_at` for articles). Separated from the row-level `updated_at` (updated by content refresh) so that a refresh on another device cannot overwrite these manual operations in sync merge. Each field-updating query only updates its corresponding column, and `upsert` (refresh) touches none of them — except that a re-subscribe, which moves the feed to the end of its group, stamps `sort_order_updated_at` along with the new `sort_order`, so the new position reaches other devices. In sync merge, each field is independently last-write-wins using these columns (see [sync-architecture.md](sync-architecture.md)). NULL means "no such event" (distinct from "happened at time 0"). Unsubscription is `softDelete`; re-subscription uses `stampResubscribed` inside `subscribeFeed` to stamp `deleted_updated_at`.

### feed_fetch_state (device-local, non-sync)

`feed_id`(PK, FK→feeds), `etag`, `last_modified`, `error_count`(default 0), `last_error`.

- What this device learned the last time it fetched the feed. It is never synced: another device's validators
  (`etag` / `last_modified`) would make this device send a conditional request for content it never fetched, and an
  error belongs to the device that saw it. A row is created on the first fetch; a feed without one is treated as
  "never fetched, no errors".
- The validators belong to the url they were fetched from, so **a change of the feed's `url` drops them** (trigger
  `ks_fetch_state_validators_url_change`; `error_count` and `last_error` stay): whatever changes the url — a sync merge
  taking another device's url, a feed identity merge giving the canonical feed the other feed's url or moving the other
  feed's fetch state onto it, a permanent redirect — the next fetch is unconditional rather than a conditional request
  that a `304` could answer for content never fetched from the new url. A refresh stores its response's validators after
  its own url write and any redirect merge, so those of a redirect's target survive on the row that is left — and only
  while that row still holds the url they were fetched from (`upsertValidators` takes the url and checks it in the
  statement): a refresh works from a snapshot, so a sync merge that moved the feed onto another url while its fetch was
  out leaves them unwritten, and the next fetch is unconditional.
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
- **An article's id can move.** When a feed moves onto its identity's canonical id, each of its articles is keyed by `(canonical feed id, guid)` and moves onto the smallest id of that key; of several rows of one key the one with the largest rowid survives (the others are folded into it and deleted), and the survivor keeps its rowid. The move is recorded in `ks_id_remap` (kind `article`). The writes that take an article id resolve it in the statement (`updateReadStatus`, `updateReadStatusByIds` — as a set —, `updateStarStatus`); `insert` / `insertIfAbsent` resolve the feed id and leave the article id as given, since an id moved away belongs to an article whose key the moved row now has, so the insert hits the conflict target. `resolveId` / `getByIdResolved` resolve an id held across time, and `movedIdsIn` / `rekeysOnto` tell which of a set of ids moved, and which ids moves landed on (`HomeViewModel`, see "Id re-keys" in [app-architecture.md](app-architecture.md)).
- Read conflict resolution is last-write-wins via `read_at`; the star is last-write-wins via `starred_at`, a group of its own, independent of the deletion (below). Both are stamped only when the state actually changes (`updateReadStatus` / `updateStarStatus` are guarded on it), so re-opening an already-read article does not move them.
- `body_updated_at` is the body's own last-write-wins stamp — for `content`, `summary`, `title`, `url`, `author`,
  `published_at` and `thumbnail_url`, which the merge takes together from one version. The article upsert
  (`insert`, `insertIfAbsent`) sets it to the write's time on a new row, and on an existing row moves it forward
  (`MAX(now, previous + 1)`) only when a body column actually changes; read, star and deletion writes never touch it.
  So a device that merely opened an article cannot make its older body beat another device's newer one. `updated_at`
  stays the row's generic stamp (moved by every write) and is no merge group's key. The column is nullable only
  because `2.sqm` adds it (initialized from `updated_at`); every write sets it.
- `content` is displayed in preference to `summary`. If both are NULL, the reader shows a localized "no content" placeholder in place (with a link/button to open the article in the external browser); nothing opens automatically.
- `search_text` = the HTML-stripped plain text of `content` (falling back to `summary`), or `""` if both are NULL. Computed at insert/update time (`ArticleRepository`, `HtmlText.toPlainText`). It is **device-local**: never synced, since it is derived. An article whose body a sync merge filled in or changed is listed in `ks_search_pending`, and its `search_text` is recomputed by the next `FtsManager.indexMissing()` before it indexes anything (a newly inserted one starts as `""`); `indexMissing()` skips rows still listed.
- Logical deletion via `deleted_at` (NULL = not deleted). Cache cleanup (`softDeleteExpired`) is the only **local**
  originator of a deletion; it never deletes a starred article. (Sync merge also writes `deleted_at`, propagating a
  deletion made on another device — see below.) `deleted_updated_at` is a field-specific last-wins
  timestamp for the delete/undelete event (like `read_at` / `starred_at`, and like `feeds.deleted_updated_at`), kept
  separate from `updated_at` so a content refresh / read change can't clobber a deletion during the sync merge.
  In the merge the star (`is_starred` by `starred_at`) and the deletion (`deleted_at` by `deleted_updated_at`) are
  **two independent groups**, with no rule across them, so a row can be both starred and deleted — a star made on one
  device and a cleanup made on another, merged in either order, or two rows a feed or article identity merged into
  one. Version 2 already wrote such a row — a star older than another device's cleanup — and hid it, since it showed
  only `deleted_at IS NULL`; `2.sqm` records each one's star as removed at its deletion, on the local database and on a
  pre-delta cloud file the merge migrates alike, so it stays hidden after the upgrade. **An article is visible while
  it is not deleted or is starred**: `(deleted_at IS NULL OR is_starred = 1)`,
  decided when it is read. Every list, unread count, search, `aliveFlagsIn` / `idsByFeeds` lookup and mark-all-read uses
  exactly that predicate (`articles.sq`; `data/local/ArticleVisibility.kt` for raw SQL and Kotlin), so a star is never
  lost to a cleanup. A star touches only the star group (`updateStarStatus`). An unstar of a row already deleted on
  this device also stamps an undelete (`deleted_at` NULL, `deleted_updated_at` moved forward), so the article stays
  visible until the next cache cleanup deletes it again. An unstar of a row that is not deleted leaves the deletion
  alone, so an older deletion from another device that arrives afterwards still applies and hides the article, now
  unstarred — as version 2 does. This makes the deletion propagate across
  devices instead of the article being resurrected from the cloud on the next sync. Rows are
  never physically removed here (physical GC of old tombstones is future work) — only a feed identity merge deletes
  the surplus rows of one article, folded into the survivor (above); the row is kept so its deletion — and
  starred-article references — survive. `getByFeedAndGuid` / `getGuidsByFeed` deliberately include deleted rows (a
  refresh must not re-add one); `upsert` (feed refresh)
  never touches `deleted_at`, so a refresh cannot revive a deleted article.

### tags / feed_tags

`tags`: `id`(PK), `name`, `color`, `sort_order`, `deleted_at`, `updated_at`, `created_at`,
`name_updated_at`, `color_updated_at`, `sort_order_updated_at`, `v2_id`(nullable).

- `id` is deterministically generated from the creation-time name as **UUIDv5** (`IdGenerator.tagId`,
  case-sensitive, exact). The same tag gets the same id on all devices, so the sync merge (matched by
  id) converges same-named tags created independently on each device into one row. A row keeps its id
  when renamed — so its id is the one its ORIGINAL name derived, and a later creation of that original
  name falls back to a random UUIDv4 (`tags.insertIfNameAbsent` picks between the two in the statement
  itself) rather than colliding. Existing random ids were re-keyed onto the derived ones by the v2→v3
  migration (below).
- **A tag's id can move.** The v2→v3 migration re-keys a row, and a sync merge may move a row onto another id of
  the same identity; every such move is recorded in `ks_id_remap` (below), and the old row is gone. So that an id
  held from before a move still reaches its row, **every write that takes a tag id resolves it** in the write
  statement itself — the id unchanged while a row has it, else the id `ks_id_remap` maps it to (`tags.sq`:
  `updateName`, `updateColor`, `softDelete`; `feed_tags.upsert`; `articles.markAllReadByTag`) — so a rename, recolor, delete,
  attach or mark-all-read issued with a stale id (an open rename field, a queued feed-list write, a drag) lands on
  the row it became instead of matching nothing or failing a foreign key. The resolution is part of the statement,
  not a read before it, so no transaction gains a read ahead of its first write and no re-key can slip in between.
  `insertIfNameAbsent` treats an id `ks_id_remap` maps as held, so a new row never takes one, and the
  resolution can never be diverted onto a newly created row.
- `v2_id` is the id the row carried before that re-key, set by the v2→v3 migration and NULL for a row
  created afterwards. Two devices that shared a v2 id derive different name-derived ids if they renamed
  the row differently before upgrading; the pair `(v2_id, id)` is an identity edge (`ks_v2_edge`), and
  the sync merge moves every row of one identity — on both sides — onto its canonical id, the smallest
  row id of the identity — never a pre-upgrade id (`domain/V2IdentitySql.kt`; "Identities of tags and folders" in
  [sync-architecture.md](sync-architecture.md)). The column itself decides nothing: it is a source of
  edges, carried in transport files so deltas and bases deliver them too, and a merge or fold keeps the
  greatest non-NULL value of the rows it combines (the other one's edge stays in `ks_v2_edge`), so every
  device converges on the same value.
  It is a temporary column: see "`tags.v2_id` / `folders.v2_id` are temporary and must be removed eventually" in
  [known-issues.md](known-issues.md) for when it can be removed.
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
`sort_order_updated_at`, `v2_id`(nullable).

- `id` is deterministically generated from the creation-time name as **UUIDv5**
  (`IdGenerator.folderId`), exactly as `tags.id` is (its own namespace, so a tag and a folder of the
  same name never share an id); the same fallback to a random id applies when the derived id is held (or mapped
  in `ks_id_remap`), and a folder id given to a write is resolved the same way (`folders.sq`: `updateName`,
  `softDelete`, `updateSortOrder`; `feeds.sq`: `updateFolder`, `updateFolderAndSortOrder`, `getByFolder`,
  `nextSortOrderInGroup`; `articles.markAllReadByFolder`), so a move, rename, reorder or delete made with a
  stale folder id applies to the folder it became.
- `v2_id` is the same pre-upgrade id as `tags.v2_id` (see above): set by the v2→v3 migration, NULL for
  a row created afterwards, and a source of the identity edges through which the sync merge moves folders
  renamed differently on two devices before they upgraded onto one canonical id.
- `name` is **not** UNIQUE (schema version 3 dropped the constraint), for the same reason as `tags.name`: same-named
  rows coexist where a rename crosses a creation, shown with an ordinal, backed by the non-unique index `folders_name`, and every by-name
  lookup lands on the canonical row of the name (see `tags` above and `folders.getByName`).
- Name and sort order are each last-write-wins by their own stamp; `updated_at` stamps creation, deletion and
  reactivation, as for `tags`.
- Independent classification axis from tags. Since 1 feed = max 1 folder (`feeds.folder_id`, not many-to-many), there is no junction table like `feed_tags`.
- When deleting a folder, `FolderRepository.deleteFolder` moves the linked feeds to "no folder" in the same transaction, as an ordinary stamped change of each feed's raw folder assignment (`feeds.updateFolderAndSortOrder` writing `ks_feed_folder_raw`, with `folder_updated_at` / `sort_order_updated_at` stamped), and then logically deletes the folder itself. The move therefore syncs like any other move, so other devices unassign those feeds as well; a feed another device moved into the folder concurrently with the delete keeps its raw assignment and returns to the folder if it is revived (see `feeds` above). The UI (`groupFeedsByFolder`) still treats a feed pointing at a deleted or missing folder defensively as "no folder", for the window before a sync arrives.

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
| `sync_outbox` | `seq`(PK, AUTOINCREMENT), `tbl`, `pk`, `pk2` (`feed_tags` only: the tag id), `heavy` — one row per local change to a synced row, appended by triggers; also one row per identity edge a merge run with capture on (the pre-delta merge) adds to `ks_v2_edge` (`tbl` `folder_v2_edges` / `tag_v2_edges`, `pk` the pre-upgrade id, `pk2` the row id), and one per feed identity edge added to `ks_feed_edge` while capture is on, inside a merge too (`tbl` `feed_edges`, `pk` `a`, `pk2` `b`) |
| `sync_capture` | a single row: `enabled` (recording on while connected), `full_pending` (a full send is due) |
| `sync_merging` | a marker row present only inside a merge transaction, which suppresses recording |
| `sync_applied` | `name`(PK) — cloud files already merged |
| `sync_cursor` | `device`(PK), `seq` — per device, the highest delta number up to which everything is merged |
| `ks_search_pending` | `rowid`(PK) — articles whose `search_text` must be recomputed |
| `ks_search_awaiting_body` | `id`(PK) — articles a merge inserted without a body (a light row), kept out of the search index until one arrives or the daily rebuild indexes them |
| `ks_feed_folder_raw` | `feed_id`(PK), `folder_updated_at`, `folder_id` — per feed, its raw folder assignment (the folder id and stamp as the winning version wrote them, alive here or not): the source of truth `feeds.folder_id` / `folder_updated_at` are resolved from (view `ks_feed_folder_resolved`), what the merge ranks raw against raw and what a send carries. One row per feed, created with it (trigger) and filled for existing feeds by `2.sqm`; `folder_id` is kept as written, so it can name an alias of a folder identity this device holds no row for (resolved through `ks_id_remap`), and is NULL only for no folder or a pre-delta file's orphan reference. Kept across joining and disconnect |
| `ks_id_remap` | `kind` + `old_id`(composite PK), `new_id` — every re-key: the id a row had and the id it was moved to. `kind` `tag` / `folder`: the v2→v3 migration's move onto the name-derived id (`domain/TagFolderIdRekey.kt`), and a sync merge's move onto another id of the same identity, recorded for every alias of the identity, including ones only another device's file used and pre-upgrade ids only an identity edge names (a feed's raw folder assignment can name one; `ks_feed_folder_resolved` resolves it through this table). `kind` `feed` / `article`: a sync merge's or a refresh's redirect merge's move of a feed, and of each article of the folded feeds, onto its canonical id (see "feeds" and "articles"), recorded for every alias including ones only another device's file or only a feed edge names. Recorded only through `domain/IdRemapSql.kt`, which folds chains within a kind as it adds a move (`a → b` then `b → c` leaves `a → c`, `b → c`; a move back drops the entry of the id that is live again), so a lookup takes one hop, and no row ever carries an old id (`insertIfNameAbsent` and `feeds.isIdTaken` treat a mapped id as held). It keeps ids held outside the database current: `domain/IdRemaps.kt` loads the tag, folder and feed moves (`idRemapPublished` — article moves are left out, since a feed merge can move far more articles than anything outside the database holds) in the background after startup and republishes them after every merge that re-keyed rows, as a `StateFlow` of the whole folded map; from it `SettingsRepository` rewrites `local_settings.json` (`lastFilter`, `collapsedFolderIds`, `expandedTagIds`, and the keys of `faviconMissedAt`), and `HomeViewModel` its selection, expansion and pending delete fallbacks. An article id held across a move (the selection, its pins, the saved `lastArticleId`) is resolved from this table one at a time instead (`articles.resolveId`). Every write that takes a tag, folder, feed or article id resolves it through this table (see "feeds", "articles", "tags / feed_tags" and "folders"). Kept indefinitely, so applying it again is a no-op. The sync merge reads its moves as identity edges as well (they normally duplicate edges already in `ks_v2_edge` / `ks_feed_edge`), so an id this device moved away from stays in the identity it moved to. Empty on every fresh database until something re-keys |
| `ks_v2_edge` | `kind` + `v2_id` + `id`(composite PK) — the identity edges of tags and folders (`kind` `tag` / `folder`): a pre-upgrade id and a row id the row carrying it was re-keyed to; rows connected by edges are one identity. A grow-only set: written by the v2→v3 re-key for every row carrying a `v2_id` and by every sync merge (the cloud file's edge tables and the `(v2_id, id)` of its and main's rows carrying a `v2_id`); never deleted, and not cleared on joining or disconnecting — an edge is a fact about two ids, not about the connection. The merge's identity step takes the ids it connects as one identity and the smallest row id among them as its canonical id (see "Identities of tags and folders" in [sync-architecture.md](sync-architecture.md)). Full sends carry it as the transport file's `folder_v2_edges` / `tag_v2_edges`, and an edge added while capture is on and no merge transaction marker exists is also recorded in `sync_outbox` (trigger `ks_capture_v2_edge_insert`) so the next ordinary delta carries it (see "Transport schema" in [sync-architecture.md](sync-architecture.md)) |
| `ks_feed_edge` | `a` + `b`(composite PK, `WITHOUT ROWID`) — the identity edges of feeds: two feed ids that held the same `url` at some point, normalized `a < b`; feeds connected by edges are one feed, whose canonical id is the smallest (byte-wise) id of the component (see "Identities of feeds and articles" in [sync-architecture.md](sync-architecture.md)). A grow-only set: written by every sync merge (the URL collisions it observes between main and the file and inside the file, and a transport file's `feed_edges`) and by a refresh whose same-host permanent redirect lands on another feed's URL (`feeds.recordRedirectEdge`); never deleted, and not cleared on joining or disconnecting, like `ks_v2_edge`. The writers normalize `a < b` themselves. Full sends carry it as the transport file's `feed_edges`, and every edge added while capture is on is recorded in `sync_outbox` (trigger `ks_capture_feed_edge_insert`, which — unlike every other capture trigger — records inside a merge too, since whether a device observes a collision depends on arrival order), so the next ordinary delta carries it |

None of these but `ks_v2_edge` and `ks_feed_edge`, nor `sync_state`, `feed_fetch_state` or `articles_fts`, ever leaves the device: delta
and base files are built from the synced tables only (see [sync-architecture.md](sync-architecture.md)) — except
that a feed's `folder_id` / `folder_updated_at` are written from its `ks_feed_folder_raw` row, the value the table
exists to hold, and that full sends and bases also carry the identity edges of `ks_v2_edge` and `ks_feed_edge` — an
ordinary delta carries the edges recorded in its outbox, and, when its raw folder assignment names an alias, the folder
edges of that alias's connected component (see "Sending" in [sync-architecture.md](sync-architecture.md)).

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

Based on `cache_retention_days`, runs in the background at startup if 24+ hours have passed since the last cleanup. Starred articles and the latest 10 articles per feed are preserved regardless of retention period (a starred article that another device's cleanup deleted stays visible all the same, see `articles` above). If `null` (unlimited), cleanup is skipped. Cleanup **soft-deletes** (stamps `articles.deleted_at` / `deleted_updated_at`) rather than physically removing rows, so the deletion propagates via the sync merge instead of being resurrected from the cloud. Physical reclamation of old tombstones is future work.

Cleanup decides by `cached_at` (the last time the article was seen in its feed). Sync carries a `cached_at` change
only when its day changes, so a device that does not refresh a feed itself can see another device's `cached_at` up to
about a day late, and may clean up an unstarred article at the edge of the retention period up to a day earlier than
when every sync carried the exact value. The starred and latest-10 protections are unaffected.

## Favicon / Thumbnail

Image binaries are not stored in the DB; only URLs are kept (non-sync target). Favicons are displayed (Coil3 `AsyncImage`); article thumbnail (`thumbnail_url`) display is still omitted in α.

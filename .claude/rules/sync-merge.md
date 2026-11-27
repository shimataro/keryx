---
paths:
  - "**/DatabaseMerger*.kt"     # the merge runs here, on a dedicated connection of its own
  - "**/MergeSql.kt"            # merge SQL statements
  - "**/ReduceSql.kt"           # folding transport files (same per-field rules, no local guards)
  - "**/SyncLww.kt"             # the value-based tie rank every merge/fold statement uses
  - "**/SyncRepository.kt"      # mutex, gates, debounce, user-facing sync state
  - "**/DeltaSync.kt"           # pointer → list → receive (fold + merge) → send → compact
  - "**/LegacyCloudFile.kt"     # pre-delta keryx.db.gz: migration, legacy watch, reset's stub
  - "**/TransportFiles*.kt"     # transport validation, prepareForMerge, the stub
  - "**/TransportSchema.kt"     # the delta/base file schema and its migrations
  - "**/TransportConnections*.kt" # per-platform dedicated connection for export/validation
  - "**/DeltaExport*.kt"        # building deltas and full sends (DeltaExporter / DeltaExportSql)
  - "**/CloudStorage.kt"        # the download/upload/create/list/metadata contract
  - "**/DropboxStorage.kt"      # per-provider rev semantics (rev / version / eTag)
  - "**/GoogleDriveStorage.kt"
  - "**/OneDriveStorage.kt"
  - "**/NextcloudStorage.kt"    # WebDAV: ETag/If-Match, 412/423, create via temp PUT + MOVE
  - "**/CloudSession.kt"        # current provider + access-token refresh
  - "**/CloudFileTransfer.kt"   # file-streamed transfers (never buffer a file in memory)
  - "**/Gzip*.kt"               # every transport file and keryx.db.gz is gzip-compressed
---

# Critical constraint: ATTACH-DATABASE merge — DO NOT violate without explicit user approval

**The ATTACH-DATABASE merge runs through `platform/DatabaseMerger`, NOT the
SQLDelight driver.** SQLDelight's JVM `JdbcSqliteDriver` opens a fresh
connection per statement for file DBs, so an `ATTACH` on one call is invisible
to the merge statements on the next. `DatabaseMerger` does the whole
attach → version-check → merge → detach on a single dedicated connection — JDBC on desktop,
requery's `SQLiteDatabase` on Android, and `RawSqliteConnection` (`appleMain`) on Apple. The same
holds for building and validating transport files (`DeltaExporter`, `TransportFiles`), which go
through `TransportConnections`' per-platform dedicated connection.

It has two modes: `mergeTransport` for a received delta/base (or the fold of several) — never
`KeryxDatabase.Schema.migrate`, checked against `TransportSchema.VERSION`, an older file brought up by
`TransportFiles.prepareForMerge` — and `merge`, the pre-delta mode, used only for `keryx.db.gz` (the
migration and the legacy watch in `LegacyCloudFile`).

## Capture suppression: `sync_merging`

Every local change to a synced row is recorded in `sync_outbox` by a trigger. A receive's merge
transaction therefore starts with `INSERT INTO sync_merging` and ends with `DELETE FROM sync_merging`
(`DeltaSync.mergeFolded`), so the rows it writes are not re-sent as this device's own changes; the
`sync_applied` / `sync_cursor` updates commit in the same transaction. The pre-delta merge
deliberately runs **without** it (capture on), so rows an older build wrote reach every device as a
delta. Do not drop the marker, move it outside the merge transaction, or add it to the pre-delta path.

## Merge-failure classification lives in DatabaseMerger, not the sync flow

Classifying "the cloud file is permanently unusable" vs. "this was transient / our bug" is done
inside `DatabaseMerger` from SQLite's **error codes** (`SQLiteException.resultCode`), and is
surfaced as `CloudDataIncompatibleException`. Do NOT reintroduce message-string matching in
`DeltaSync` / `SyncRepository`: `DeltaSync.mergeAndRefresh` also covers
`driver.notifyListeners()` and then the post-commit indexing (`finishIndexing` →
`ftsManager.indexMissing()`, which recomputes `search_text` first), which run **after** the merge has
committed — classifying those as "the cloud is corrupt" would offer the user a reset for a
local/app-side fault.

`CloudDataIncompatibleException` is what drives `AppNotificationAction.ResetCloudData` (a heavy
action — a new generation replaces the cloud data by this device's state, every other device
rejoins with a full send, and an unusable `keryx.db.gz` is archived and stubbed), so widening what
it covers is a destructive-action decision, not a refactor.

## Invariants every change must keep

- **No data is lost.** Nothing a merge, fold or garbage collection does may remove a row, value, body or link
  unless its field group's own rule (last-write-wins, an article body's `content` precedence), an explicit `RowFold`
  rule (the earliest `created_at`, the latest `cached_at` / `updated_at`, the greatest `v2_id`) or a logical deletion
  supersedes it; losing a concurrent write to such a rule is not a loss.
- **Every device ends identical.** After the same files are merged, all devices hold and show the same synced data
  (names and numbering included); device-local data never leaks into it.

See "Design Philosophy" and "Conditions every merge rule must meet" in `docs/sync-architecture.md`.

## Read the full design before changing any of this

`docs/sync-architecture.md` is **not** imported into the session (it is well over 100KB, so
`.claude/CLAUDE.md` lists it as read-on-demand). This rule is a guard rail, not the design.
**Read `docs/sync-architecture.md` before changing sync behavior** — it is the only place the
pointer/generation, delta and base files, validation, folding, garbage collection, the per-field
merge groups (the independent star and deletion groups, `body_updated_at`), the pre-delta migration and legacy watch, the
automatic-sync suspension gate, the network gate, and the reset/archive semantics are specified.

See also: `docs/sync-architecture.md` ("Sync Flow", "Merge (`DatabaseMerger` + `MergeSql`)",
"Merge Failure Classification", "Migration from the Pre-Delta Format", "Resetting (Archiving)
Cloud Data") and `docs/app-architecture.md` ("DatabaseMerger (expect / actual)").

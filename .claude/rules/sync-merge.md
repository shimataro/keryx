---
paths:
  - "**/DatabaseMerger*.kt"     # the merge runs here, on a dedicated JDBC connection
  - "**/MergeSql.kt"            # merge SQL statements
  - "**/SyncRepository.kt"      # download → merge → snapshot → upload orchestration
  - "**/DatabaseSnapshot*.kt"
  - "**/CloudStorage.kt"        # the download/upload/create/rename/metadata contract
  - "**/DropboxStorage.kt"      # per-provider rev semantics (rev / version / eTag)
  - "**/GoogleDriveStorage.kt"
  - "**/OneDriveStorage.kt"
  - "**/CloudSession.kt"        # current provider + access-token refresh
  - "**/CloudFileTransfer.kt"   # file-streamed transfers (never buffer the DB)
  - "**/Gzip*.kt"               # the compressed upload / legacy fallback
---

# Critical constraint: ATTACH-DATABASE merge — DO NOT violate without explicit user approval

**The ATTACH-DATABASE merge runs through `platform/DatabaseMerger`, NOT the
SQLDelight driver.** SQLDelight's JVM `JdbcSqliteDriver` opens a fresh
connection per statement for file DBs, so an `ATTACH` on one call is invisible
to the merge statements on the next. `DatabaseMerger` does the whole
attach → version-check → merge → detach on a single dedicated JDBC connection.

## Merge-failure classification lives in DatabaseMerger, not SyncRepository

Classifying "the cloud DB is permanently unusable" vs. "this was transient / our bug" is done
inside `DatabaseMerger.merge` from SQLite's **error codes** (`SQLiteException.resultCode`), and is
surfaced as `CloudDataIncompatibleException`. Do NOT reintroduce message-string matching in
`SyncRepository`: its `try` (`mergeCloud`) also covers `ftsManager.indexMissing()` and
`driver.notifyListeners()`, which run **after** the merge has committed — classifying those as
"the cloud is corrupt" would offer the user a destructive reset for a local/app-side fault.

`CloudDataIncompatibleException` is what drives `AppNotificationAction.ResetCloudData` (a
destructive action — the cloud DB is archived then recreated), so widening what it covers is a
destructive-action decision, not a refactor.

## Read the full design before changing any of this

`docs/sync-architecture.md` is **not** imported into the session (it is ~37KB and would
cost that on every session, so `.claude/CLAUDE.md` lists it as read-on-demand). This rule
is a guard rail, not the design. **Read `docs/sync-architecture.md` before changing sync
behavior** — it is the only place the download/merge/upload flow, the unchanged-transfer
skip (`cloud_file_rev` + snapshot digest), the compressed-upload / legacy `.gz` fallback,
the automatic-sync suspension gate, and the cloud-data reset/archive semantics are
specified.

See also: `docs/sync-architecture.md` ("Merge (`DatabaseMerger` + `MergeSql`)",
"Merge Failure Classification", "Resetting (Archiving) Cloud Data")
and `docs/app-architecture.md` ("DatabaseMerger (expect / actual)").

---
paths:
  - "**/Fts*.kt"                   # FtsManager / FtsSearch (+ their tests)
  - "**/DatabaseDriverFactory*.kt" # busy_timeout is set here
  - "**/DeltaExport*.kt"           # transport files from synced tables only; full send's VACUUM INTO copy is read-only
  - "**/DeltaSync.kt"              # search_text backfill, then indexMissing(), after each merge
  - "**/SearchTextBackfill.kt"     # recomputes ks_search_pending rows before they are indexed
  - "**/FeedRepository.kt"         # indexMissing() after refresh
  - "**/StartupTasks.kt"           # desktop startup orchestration (maybeRebuildFtsIndex via runStartupMaintenance)
  - "**/BackgroundRefreshLoop.kt"  # calls maybeRebuildFtsIndex on each background cycle
  - "**/FeedRefreshWorker.kt"      # Android WorkManager wakeup, calls maybeRebuildFtsIndex
  - "**/StartupMaintenanceTasks.kt" # maybeRebuildFtsIndex itself (daily idle heal, commonMain)
  - "**/main.kt"                   # ensureIndexed() called at startup, before application {}
  - "**/KeryxApplication.kt"       # ensureIndexedIfTableAbsent() called on every Android process start
  - "**/KeryxSdk.kt"               # Apple: createTable() in start(), prepareSearchIndex()
  - "**/*.sq"                      # do NOT add articles_fts to a .sq file
---

# Critical constraint: `articles_fts` / FTS index — DO NOT violate without explicit user approval

**`articles_fts` is never part of the SQLDelight-managed schema.** It is
created/maintained at runtime via `FtsManager` (raw SQL on the driver). Do not
add it to a `.sq` file. The **live table is never dropped**, and the index
**never leaves the device**: delta and base files are built from the synced
tables only (`DeltaExporter` / `DeltaExportSql`), and the full send's `VACUUM
INTO` copy (`DeltaExporter.copyForFullSend`) is only read from — so nothing
needs dropping anywhere and a concurrent search never hits `no such table`. Hot paths (feed refresh, sync merge) index new
rows incrementally via `FtsManager.indexMissing()` — never a full `'rebuild'`,
which is O(all indexed text) and would block/zero-out concurrent searches. The
whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass
(`maybeRebuildFtsIndex` in commonMain's `domain/StartupMaintenanceTasks.kt`, gated on
`lastFtsRebuiltAt` + `ActivityCenter` idle — no sync, no feed refresh, and no
refresh-then-sync cycle (`refreshCycleRunning`) in flight — reached at startup through `runStartupMaintenance` (desktop's `StartupTasks.kt`,
`AndroidStartupTasks.kt`), and from `BackgroundRefreshLoop` and Android's `FeedRefreshWorker`),
which re-indexes content that incremental indexing left stale.

`search_text` is device-local and not merged: a merge registers every article whose body it
filled in or changed in `ks_search_pending` (a new one starts with `''`), and
`indexMissing()` **skips rows still listed there**, so an article is never indexed with
`''`. `SearchTextBackfill.recomputePending()` must therefore run **before** `indexMissing()`
after every merge (`DeltaSync.mergeAndRefresh`), and before the daily `rebuildIndex()`
(`maybeRebuildFtsIndex`), since `'rebuild'` indexes every row from its current
`search_text`, pending or not. Startup maintenance resumes an interrupted pass
(`recomputePendingAndIndex`). On startup, `FtsManager.ensureIndexed()` creates the table on first
run and backfills any missing rows. The Apple SDK's `KeryxSdk.start` creates the table (best effort, logged on
failure) so it exists even when the scene never becomes active, and `prepareSearchIndex()` backfills the rows
once it is. Android's `KeryxApplication.onCreate` calls the
cheaper `FtsManager.ensureIndexedIfTableAbsent()` instead — it also runs on every
`WorkManager` wakeup, not just once per app launch like desktop's `main.kt`, so it
skips `indexMissing()`'s `O(articles)` scan entirely (a single `sqlite_master` check)
once the table has already been created and backfilled once; new articles keep
getting indexed as normal through the hot-path `indexMissing()` calls below. `busy_timeout` (set in
`DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief
write lock of an incremental insert or a rebuild.

`indexMissing()` and `rebuildIndex()` are **mutually exclusive** — `FtsManager`
serializes the two writers behind an internal mutex, which is why both are
`suspend`. The daily pass's idle gate is a lock-free `ActivityCenter` check, so
without the mutex a refresh starting just after it passes would overlap the
rebuild. Searches are deliberately *not* serialized (they rely on `'rebuild'`
being a single atomic statement plus the `busy_timeout` wait), and
`ensureIndexed()` is called from a `runBlocking` at startup — the mutex is
coroutine-based and held only briefly by an `.opml` import dispatched moments
earlier, so blocking the main thread on it cannot deadlock.

See also: `docs/sync-architecture.md` ("FTS5 Handling") and `docs/db-schema.md`
(`articles_fts` section).

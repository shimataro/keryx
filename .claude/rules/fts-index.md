---
paths:
  - "**/Fts*.kt"                   # FtsManager / FtsSearch (+ their tests)
  - "**/DatabaseDriverFactory*.kt" # busy_timeout is set here
  - "**/DatabaseSnapshot*.kt"      # VACUUM INTO snapshot drops articles_fts on the copy side
  - "**/SyncRepository.kt"         # indexMissing() after merge
  - "**/FeedRepository.kt"         # indexMissing() after refresh
  - "**/StartupTasks.kt"           # desktop orchestration that calls maybeRebuildFtsIndex
  - "**/StartupMaintenanceTasks.kt" # maybeRebuildFtsIndex itself (daily idle heal, commonMain)
  - "**/main.kt"                   # ensureIndexed() called at startup, before application {}
  - "**/KeryxApplication.kt"       # ensureIndexedIfTableAbsent() called on every Android process start
  - "**/*.sq"                      # do NOT add articles_fts to a .sq file
---

# Critical constraint: `articles_fts` / FTS index — DO NOT violate without explicit user approval

**`articles_fts` is never part of the SQLDelight-managed schema.** It is
created/maintained at runtime via `FtsManager` (raw SQL on the driver). Do not
add it to a `.sq` file. The **live table is never dropped**: the sync flow
excludes it from the uploaded file by building a `VACUUM INTO` snapshot copy
(`platform/DatabaseSnapshot`) and dropping it there, so a concurrent search
never hits `no such table`. Hot paths (feed refresh, sync merge) index new
rows incrementally via `FtsManager.indexMissing()` — never a full `'rebuild'`,
which is O(all indexed text) and would block/zero-out concurrent searches. The
whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass
(`maybeRebuildFtsIndex` in commonMain's `domain/StartupMaintenanceTasks.kt`, gated on
`lastFtsRebuiltAt` + `ActivityCenter` idle, called from desktop's `StartupTasks.kt`),
which re-indexes content that incremental indexing left stale. On startup, `FtsManager.ensureIndexed()` creates the table on first
run and backfills any missing rows. Android's `KeryxApplication.onCreate` calls the
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

See also: `docs/sync-architecture.md` ("FTS5 handling") and `docs/db-schema.md`
(`articles_fts` section).

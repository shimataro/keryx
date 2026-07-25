---
paths:
  - "**/Fts*.kt"                   # FtsManager / FtsSearch (+ their tests)
  - "**/DatabaseDriverFactory*.kt" # busy_timeout is set here
  - "**/DatabaseSnapshot*.kt"      # VACUUM INTO snapshot drops articles_fts on the copy side
  - "**/SyncRepository.kt"         # indexMissing() after merge
  - "**/FeedRepository.kt"         # indexMissing() after refresh
  - "**/main.kt"                   # maybeRebuildFtsIndex (daily idle heal)
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
in `main.kt` (`maybeRebuildFtsIndex`, gated on `lastFtsRebuiltAt` +
`ActivityCenter` idle), which re-indexes content that incremental indexing
left stale. On startup, `FtsManager.ensureIndexed()` creates the table on first
run and backfills any missing rows. `busy_timeout` (set in
`DatabaseDriverFactory`) lets a search wait out, rather than error on, the brief
write lock of an incremental insert or a rebuild.

See also: `docs/sync-architecture.md` ("FTS5 handling") and `docs/db-schema.md`
(`articles_fts` section).

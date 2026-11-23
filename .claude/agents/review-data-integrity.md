---
name: review-data-integrity
description: Reviews Keryx changes for DB correctness — deterministic IDs, storage conventions, per-field edit timestamps, transactional/FK safety, logical-deletion semantics — and for backward compatibility across versions (schema migrations, the parallel schema copies, local_settings.json, cloud file format, token storage format, OPML). Read-only.
tools: Read, Grep, Glob, Bash
model: opus
---

## Before you start

**Read `.claude/etc/review/common.md` now, before anything else.** It defines the finding schema, the
severity and confidence scales, the responsibility boundaries between the review agents, the output
language, and the display name for your perspective. It is mandatory and this file does not repeat
it. (It is referenced by path rather than imported because `@`-imports are not reliably expanded in
agent definition files.)

You review Keryx (a cross-platform RSS reader, Kotlin Multiplatform / Compose Multiplatform) for
**data integrity**, along two axes: correctness *now* (space) and compatibility *over time*.

Reference: `docs/db-schema.md`, already in your context (`.claude/CLAUDE.md` imports it at session
start). `docs/sync-architecture.md` is **not** — read it if a compatibility question turns on the
cloud file format.

## Not yours

- The merge SQL's own column lists, guards, statement order, and conflict semantics →
  `review-sync-merge`. You check only that the **parallel schema copies agree with each other**.
- Concurrent access and locking → `review-concurrency`.
- What the UI renders, selects, or focuses from a row → `review-state-consistency`. Your subject is
  the row in the database; theirs is whether the screen still agrees with it.
- Missing tests → `review-verification`.

## Checklist — correctness (space)

- Do IDs stay deterministic across devices? If not, sync merge can't converge —
  two devices that independently subscribe the same feed / fetch the same
  article get different ids and never match. New-row id generation lives in
  `domain/IdGenerator.kt` (currently UUIDv5); existing rows must keep their id.
  Flag random-UUID use for new feeds/articles.
- Are the storage conventions upheld — booleans as 0/1 `Long`, timestamps as
  Unix-millis `Long`, and `search_text` recomputed from content/summary on every
  insert/update path (after a merge, through `ks_search_pending`, recomputed by
  `FtsManager.indexMissing()` / `rebuildIndex()` before they index)? A stale
  `search_text` silently breaks search.
- Are per-field edit timestamps protected from content refresh? A feed refresh
  (`upsert`) must not touch the user-edited columns (folder, sort order, custom
  title, subscription state) or their per-field `*_updated_at` — otherwise a
  refresh on one device clobbers a manual edit on another at the next merge.
  Reorder/move should write only rows whose value actually changed. Every
  last-write-wins stamp moves only forward on its row (`MAX(now, previous + 1)`),
  and `articles.body_updated_at` moves only when a body column changes — never on
  a read, star or deletion — or an older body can beat a newer one in the merge.
- Is every local write to a synced row captured? The `sync_outbox` triggers must
  cover it (their column lists track the schema), device-local data
  (`search_text`, `feed_fetch_state`, `sync_*`, `ks_*`, `articles_fts`) must never
  reach a transport file, and only a receive's merge may suppress capture
  (`sync_merging`).
- Are multi-statement mutations transactional and FK-safe? e.g. deleting a
  folder must null out its child feeds' `folder_id` in the same transaction, so
  no feed is left pointing at a deleted folder; FKs (feed→folder, article→feed,
  feed_tags) must not dangle.
- Are logical-deletion semantics correct? `deleted_at IS NULL` = alive and
  `watch*` queries filter it. Locally, cache cleanup (`articles.softDeleteExpired`)
  is the only writer that creates a tombstone, and it never deletes a starred
  article; a feed refresh (`upsert`) must not touch `deleted_at` at all, or it
  would revive a deleted article. `MergeSql` also writes `deleted_at` during sync:
  the star and the deletion are one *retention* group (`is_starred`, `starred_at`,
  `deleted_at`, `deleted_updated_at`) ranked by `MAX(starred_at, deleted_updated_at)`
  — the newer operation wins the whole group. Locally a star clears `deleted_at`
  (`updateStarStatus`) and both a star and a cleanup stamp above both stamps, so a
  row is never both starred and deleted. Re-subscription must clear
  `feeds.deleted_at` and stamp its per-field timestamp so it wins over a
  concurrent refresh.

## Checklist — compatibility (time)

A change here breaks users who upgrade, or devices on two different versions syncing with each other.

- **The parallel schema copies must move together.** A column added to a `.sq` file must also land in
  `domain/MergeSchema.EXPECTED_SCHEMAS` (which `DatabaseMerger.validateSchema` checks), in
  `domain/TransportSchema` (the delta/base file schema — `ReduceSql` and the export are built from its
  table specs — with a migration for older files), in the capture triggers' column lists, and in
  `MergeSql`'s explicit column lists, or merge/validation drifts silently.
- **Schema version.** A schema change needs a `<from-version>.sqm` migration; SQLDelight derives the
  version from the highest migration file + 1, and `PRAGMA user_version` drives create/migrate in
  `DatabaseDriverFactory`. `MergeSchema.EXPECTED_SCHEMAS` must gain the new version in lockstep.
  A migration must be safe on a populated DB, not just an empty one.
- **On-disk formats read by an older or newer build**: `local_settings.json` (a new key must have a
  default; a removed key must not crash an older build), the cloud transport files (deltas, bases,
  the `keryx-sync.json` pointer) and the pre-delta `keryx.db.gz` / its stub, the token storage
  format, and OPML import/export.
- **Downgrade / mixed-version sync**: a device on the old version must fail safely rather than
  corrupt data — a newer transport file or pointer `format` aborts with `SchemaVersionException`
  rather than merging against unknown columns, and a pre-delta build refuses the stub before writing.

## Investigation

    ls shared/src/commonMain/sqldelight/works/merc/keryx/app/data/local/db/
    grep -rn "EXPECTED_SCHEMAS\|TransportSchema" shared/src --include=*.kt

When the diff touches FTS, read `.claude/rules/fts-index.md`.

---
name: perf-tune
description: Measurement-driven performance work on the Keryx source — reduce total work (CPU / SQL / IO / allocation), improve perceived speed (show what's ready, report progress, optimistic display), and fix concurrency/scheduling — while never weakening security, data integrity, or observable behavior. After a one-time gate approval (bulk for Green/Yellow, individual for each Red), each approved item is measured, verified, and committed independently (one commit per item) with no further per-item confirmation. Invoke explicitly with /perf-tune (optionally /perf-tune <path>), or when asked to "make it faster", "optimize performance", "speed up startup", "reduce memory", or "the app feels slow".
---

> This skill involves **high-accuracy judgments** — interpreting measurements, and
> deciding whether a change trades away durability, security, or observable behavior.
> **Run it in an opus session.** Skills inherit the caller session's model and cannot
> pin one in frontmatter, so verify the session model is opus before executing.

Make Keryx faster **without ever trading away durability, security, or observable
behavior**. Every change must be justified by a measurement taken before it, and
confirmed by the same measurement after it. This is the deliberate optimization
task that the `refactor` skill explicitly defers to (see its "Performance
opportunities (report-only, not applied)" hand-off) — so a `/refactor` report is
a good starting inventory, but never a substitute for measuring.

Work in small, independently-revertible steps: baseline → measure → propose →
gate → apply one at a time → re-measure. The existing test suite is the oracle
for behavior and must stay green throughout. After the Step 4 gate approval,
each approved item is applied, re-measured, verified, and **committed
independently — one commit per item** — with no further per-item approval,
mirroring the `evaluate-review` skill's Case B auto-commit flow, rather than
left for the user to commit.

> Judging whether an optimization crosses a durability, sync-merge, or FTS
> invariant is a **high-accuracy judgment**. **Run this in an opus session.**
> Skills inherit the caller session's model and cannot pin one in frontmatter,
> so verify the session model before executing.

## Scope

- Argument (`$ARGUMENTS`) is an optional path to narrow the sweep (e.g.
  `/perf-tune domain/`, `/perf-tune composeApp/src/commonMain/kotlin/.../ui/home`).
- **Default (no argument):** `composeApp/src/commonMain/kotlin`,
  `composeApp/src/desktopMain/kotlin`, and `composeApp/src/commonMain/sqldelight`
  — unlike `refactor`, `.sq` files **are** in scope; they are where most of the
  hot paths live.
- **Always excluded:** `build/` and generated code (SQLDelight, Compose
  Resources, `BuildConfig`) — regenerated, never hand-edited.
- Test code may be read as a measurement aid and extended, but **no existing
  assertion may be deleted or loosened**.

## Three axes

Fix what "faster" means before touching anything. Each axis has its own valid
measurement method (see Step 2); one candidate can span several axes.

1. **Reduce total work** — do less CPU, SQL, IO, or allocation.
2. **Improve perceived speed** — same total time, shorter wait *experience*:
   render what is ready, report progress, show results incrementally, update
   optimistically.
3. **Concurrency & scheduling** — stop blocking the UI thread and the DB write
   lock, overlap sequential IO, move blocking work to the right dispatcher.
   This axis also covers **auditing for concurrency hazards** — a shared
   mutable resource touched from more than one dispatcher/coroutine without the
   serialization the surrounding code assumes. Finding one is a correctness
   risk, not a speed one, so it is tiered and gated like any other candidate
   (see Optimization targets #16 and the Step 2 measurement note below).

Always label which axis a change belongs to. An axis-2 change is **not** a
speed-up and must never be reported as one.

## Non-negotiable invariants

Hard stops. See `.claude/CLAUDE.md` "Critical constraints" #1–#9 and `docs/*.md`.
Each entry says *why* it breaks — a bare prohibition list does not survive
contact with a tempting optimization.

### Data integrity

- **Never trade durability for speed.** No `synchronous=OFF|NORMAL`, no
  `journal_mode=MEMORY|OFF`. A crash-corrupted local DB is not a local problem:
  it rides the `VACUUM INTO` snapshot up to the cloud and propagates to every
  device.
- Do not turn off `PRAGMA foreign_keys=ON`, and do not lower `busy_timeout=5000`
  (both set in `DatabaseDriverFactory.desktop.kt`). A lower busy timeout turns a
  concurrent search into `SQLITE_BUSY` — which the repository absorbs as *zero
  hits*, so the user silently sees an empty result set rather than an error.
- `MergeSql` statement semantics, the `MergeSql.all` application order, and the
  last-write-wins timestamp meaning are fixed (#5, `docs/sync-architecture.md`).
- **FTS rules** (#1): never add `articles_fts` to a `.sq` file, never DROP the
  live index, never swap `FtsManager.indexMissing()` for a full `'rebuild'` on a
  hot path. "`indexMissing` is slow, let's just rebuild" is the exact move this
  rule exists to forbid — `'rebuild'` is O(all indexed text).
- The merge stays on `DatabaseMerger`'s dedicated JDBC connection (#2).
- **Do not loosen transaction boundaries for speed.** The `db.transaction {}` in
  `upsertParsed` / `markArticlesAsRead` / `moveFeed` exists to prevent partial
  application. Allowed directions: fewer statements *inside* a transaction, and
  moving *CPU work out of* a transaction. Forbidden direction: splitting one
  transaction into several.
- **No cache layer that can miss a write.** A dropped read/starred update is not
  just a stale pixel: `read_at` / `starred_at` last-write-wins propagates the
  wrong value to every other device on the next sync.
- **Optimistic UI must always be paired with the corresponding persisted write.**
  This is the central risk of axis 2. Existing `selectArticle` / `toggleRead` /
  `toggleStar` / `markSelectedUnread` update UI state first *and* always dispatch
  the write on `dbWriteDispatcher`. Any new optimistic display must keep that
  shape and must not swallow write failures. Display without persistence means
  the next sync reverts to the other device's value — the user sees an article
  they marked read come back unread.
- Do not change soft-delete / tombstone semantics (`deleted_at` /
  `deleted_updated_at`), and never drop the `deleted_at IS NULL` filter from a
  UI/list/search query to save a predicate.
- **Schema changes carry a sync-compatibility cost (mandatory Red-tier
  disclosure).** Even adding a single index means a new `.sqm` and
  `user_version` 2 → 3, which:
  1. makes **older app versions reject the newer cloud DB** with
     `SchemaVersionException` (`docs/sync-architecture.md` "Schema Version"); and
  2. breaks `DatabaseMerger.validateSchema`, whose `when` is an exact match
     (`2L -> EXPECTED_SCHEMA_V2; else -> return false`) — without adding the `3L`
     branch in the same change, a perfectly good cloud DB is misclassified as
     corrupt.

  Never add an index without stating both points in the report.

### Security

- HTTPS only. Never relax TLS or certificate validation for latency.
- Do not remove the `MAX_REDIRECTS=5` loop guard, nor the
  `followRedirects = false` / `expectSuccess = false` client config in
  `PlatformModule.desktop.kt` — `FeedFetcher`'s redirect classification (301/308
  vs 302/303/307 vs 410) depends on receiving those responses itself.
- Never skip OAuth PKCE or `state` verification to shave a round trip.
- Do not widen token-storage permissions or cache lifetimes (Keychain, or the
  `0600` `.{CloudStorageType.id}_tokens.json` fallback).
- **No string-concatenated SQL for speed.** Keep placeholders and binders
  (injection). `FtsSearch`'s per-term quoting/escaping is both MATCH-syntax
  protection and input neutralization — do not "simplify" it.
- `HtmlText.toPlainText` and `escapeHtml` are **sanitization paths**, not just
  formatting. Do not replace them with regex stripping to save a parse.
- Measurement instrumentation must never log article bodies, tokens, or URLs
  carrying credentials (`main.kt` already carries a "do not log the URI itself"
  comment on the OAuth callback). Do not add an external profiler or telemetry
  service — `docs/external-spec.md` §10 promises no data leaves the device.

### Behavior parity

- List ordering, search relevance order (`articles_fts.rank`), unread counts, and
  highlight markup must stay byte-identical. The test suite is the oracle.
- **Do not break an existing deliberate perceived-speed trade by looking at only
  one side of it.** `SharingStarted.Eagerly` plus the `HomeViewModel` pre-warm in
  `main.kt` deliberately delays the window in exchange for not flashing empty
  lists; the `searching` StateFlow deliberately holds rather than showing "no
  results" mid-keystroke. Both have their reasoning in code comments. Proposing
  the opposite trade is legitimate — but present **both sides** and get approval.
- New user-visible strings (e.g. a progress label) go through Compose Resources
  (#3). `expect`/`actual` boundaries (#4) and the layered architecture (#6) are
  unchanged.
- When a change touches `ui/`, read the **`ui-guidelines` skill** first.

## Risk tiers

Tier every candidate; the tier decides the gate.

| Tier | What it covers | Gate |
| --- | --- | --- |
| **Green** | Local, behavior-preserving, integrity-irrelevant (narrowing a projection, hoisting a loop invariant, moving CPU work out of a transaction) | Covered by the single Step 4 approval |
| **Yellow** | Semantics must be *proven* equal (batching an N+1, changing concurrency, changing dispatchers, reworking Flow operators, adding a progress indicator) | Step 4 approval **plus** a test and a measurement |
| **Red** | PRAGMA / `.sq` schema / migrations / merge / FTS / sync / OAuth paths / any **new** optimistic display / a **latent concurrency hazard** found by the #16 audit (a serialization-boundary bypass, Red even when no speed win is claimed — distinct from the Yellow row's *deliberate* concurrency/dispatcher change) | **Individually approved, one at a time**, with the integrity and sync-compatibility impact spelled out first |

## Optimization targets

Where to look, by axis. These are verified real hot spots, **not a mandate** —
only act on what you actually measured.

### Axis 1 — reduce total work

1. **Per-article HTML DOM parse.** `HtmlText.toPlainText` is
   `Ksoup.parse(html).text()`, called once per article in
   `ArticleRepository.upsertParsed` — and it sits **inside** the
   `db.transaction {}`, so CPU-heavy parsing holds the DB write lock (also axis
   3). Hoisting the parse out of the transaction is semantics-preserving.
   `ArticleWebViewHtml.extractLinks` runs another full `Ksoup.parse` per rendered
   article.
2. **Whole-list recomputation.** `HomeViewModel.articles` rebuilds a `HashSet`
   and runs `sortedWith` + `filter` + `reversed()` over *every* article on each
   emission — including a single read toggle. Same family: the linear scans in
   `HomeCommon.groupFeedsByFolder` / `feedListItemIndex`, and the per-character
   `AnnotatedString` build in `markedToAnnotatedString` for every search row.
3. **SQL N+1.** `ArticleRepository.search` issues a `getById` per FTS hit;
   `upsertParsed` issues a `getByFeedAndGuid` per article purely to count new
   ones; `markArticlesAsRead` loops single-row `UPDATE`s.
4. **Unbounded query / over-wide projection.** `articles.sq`'s `watchAll` has no
   `LIMIT` and selects `a.*`, pulling full `content` and `search_text` for every
   article into memory even though the list pane renders neither.
5. **Expensive statements.** `softDeleteExpired`'s per-row correlated subquery;
   `FtsManager.indexMissing`'s `NOT IN (SELECT id FROM articles_fts_docsize)`.
6. **Index design.** `idx_articles_is_read` is low-cardinality. The
   `is_read = 0 AND deleted_at IS NULL GROUP BY feed_id` shape used by
   `watchUnreadCountsByFeed` and friends is a composite-index candidate — **Red**,
   with the schema-compatibility cost above.
7. **Memory.** `SyncRepository` materializes the whole DB as a `ByteArray` on both
   the download and the snapshot-read side — a spike proportional to DB size.
8. **PRAGMA / connection settings.** WAL is unused; `cache_size` / `mmap_size` are
   untuned — **Red**. WAL introduces `-wal` / `-shm` side files, so it must not be
   adopted without verifying the interaction with the `VACUUM INTO` snapshot and
   with `DatabaseMerger`'s separate connection.
9. **Sync payload.** The whole DB is uploaded every time, tombstones included.
   Physical tombstone GC is documented as future work — do not implement it on
   your own judgment (#5).

### Axis 2 — perceived speed

Understand the perceived-speed work that already exists before proposing more:
optimistic read/star updates, `SharingStarted.Eagerly` + the `HomeViewModel`
pre-warm, the `searching` hold, and the favicon letter-avatar fallback. Remaining
headroom:

10. **Progress granularity.** `ActivityCenter` exposes only a boolean
    (`counter > 0`), and `FeedRepository.refreshAll` wraps the entire loop in one
    `trackFeedRefresh` — so an N-feed refresh shows one indeterminate spinner for
    the whole duration. The articles themselves already appear incrementally
    (each `upsertParsed` notifies `watchAll`), so an "M of N" progress readout
    improves the wait experience at **zero** change to total time. This is the
    model axis-2 change.
11. **Time to first window.** `FtsManager.ensureIndexed()` and the `HomeViewModel`
    pre-warm both run synchronously in `main.kt` *before* `application {}`,
    delaying the window itself. Showing the window first with a skeleton is the
    opposite trade — legitimate to propose, but the pre-warm is a deliberate
    choice against empty-list flashing, so present both sides and get approval.
12. **Incremental rendering.** Paginating the article list (the same change as
    axis-1 #4, but justified as "first screenful sooner"); rendering the article
    header before the body reaches the WebView.

### Axis 3 — concurrency & scheduling

13. **Sequential IO.** `FeedRepository.refreshAll` fetches feed data concurrently
    (bounded semaphore) but applies DB writes serially — **DB writes must stay
    serialized** — the JVM driver opens a fresh connection per statement, so
    concurrent writes hit `SQLITE_BUSY`.
14. **Dispatchers.** `Dispatchers.IO` is unused across the codebase; blocking JDBC
    and file IO run on `Dispatchers.Default` (bounded by CPU count). But
    `HomeViewModel.dbWriteDispatcher` and `SettingsRepository.writeDispatcher` are
    single-threaded **on purpose, for serialization** — never widen those.
15. **Lock hold time.** Axis-1 #1 is the known instance of CPU work inside a
    transaction; look for others.
16. **Concurrency hazard audit — Red.** Look for a new code path that bypasses a
    serialization boundary — `HomeViewModel.dbWriteDispatcher` (serializes the
    article read/star **DB** writes) or `SettingsRepository.writeDispatcher`
    (serializes the coalesced `local_settings.json` **file** write, **not** DB
    writes: `setGlobal` writes `global_settings` directly on the caller's
    thread, so a concurrent global-settings write is exactly the kind of
    unserialized path this audit should catch) — or a `Flow` that assumes a
    single collector (e.g. `SharingStarted` replay/state semantics) being
    collected from more than one place. A latent
    race is a correctness risk regardless of whether it has caused a visible
    bug yet, so treat any finding as **Red tier** — gate it individually even
    when no speed win is claimed.

> `SettingsRepository`'s `local_settings.json` write is **already** coalesced
> (`DROP_OLDEST`) and serialized on a single thread. Do not re-propose work that
> is already done.

## Steps

### Step 1 — Establish a green baseline

Check `git status --porcelain` before anything else: if the working tree is
dirty with changes unrelated to this run, warn and stop — proceeding would
contaminate the baseline below and risk an unrelated file getting swept into
(or clobbered by a revert during) a later item's commit. Then run the tests —
or invoke the **`build` skill**:

```bash
./gradlew :composeApp:desktopTest
```

Record the baseline **from this run itself**: both the passing count and the set
of test identities (class + method) from
`composeApp/build/test-results/desktopTest/*.xml` (or the HTML report under
`composeApp/build/reports/tests/desktopTest/`). Do not take a count from any doc.
If the baseline is **red**, stop and report — never optimize on top of failing
tests.

Also capture the branch's current commit as `BASE_SHA` (`git rev-parse
HEAD`) — Step 8 needs it to review the accumulated per-item commits, since
by then the working tree itself will be clean.

### Step 2 — Measure (never guess)

Each axis has its own method.

- **Axis 1, SQL — `EXPLAIN QUERY PLAN` first, then time it.** Run it against a
  copy of the DB (or a throwaway seeded one) in the scratch directory with
  `sqlite3` (present at `/usr/bin/sqlite3` on macOS), and record whether the plan
  shows `SCAN`, `SEARCH ... USING INDEX`, or `USE TEMP B-TREE`. The plan is
  deterministic and noise-free — a good **diagnostic signal** — but it is a
  *strategy*, not a *cost*: a flip to `USING INDEX` does not by itself prove less
  execution time, CPU, or work, and an added index amplifies every write on the
  `articles` insert / feed-refresh hot path, so a read-side plan win can regress
  the write side. **Pair the plan diff with a repeatable query-execution
  measurement** — the same seeded workload timed before and after with the same
  sampling discipline as the CPU/memory axis below (warm-up, several samples,
  median plus spread) — before proposing an SQL candidate. Where the CLI is
  unavailable, use a throwaway JDBC connection.
- **Axis 1, CPU / memory** — write disposable seeding code under the scratch
  directory and run the **same seeded workload** before and after, with warm-up
  runs to let the JIT settle, several repeated samples, and a reported **median
  plus spread** — a single before/after timing lets JIT / GC / scheduler noise
  pass the gate. Wall-clock time measures **elapsed** time, not CPU work — and
  neither reflects memory or allocation; when the claimed win is allocation or
  memory rather than speed, measure it **resource-specifically** with an explicit
  allocation/heap delta in that same disposable code (e.g. `System.gc()` +
  `Runtime.totalMemory() - Runtime.freeMemory()` sampled before and after — an
  **approximate retained-heap proxy, not a precise allocation count** — or a real
  allocation counter such as `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()`,
  the JDK-specific extension interface rather than the standard
  `java.lang.management.ThreadMXBean`, and only valid where
  `isThreadAllocatedMemorySupported()` / `isThreadAllocatedMemoryEnabled()` report
  it available) — never an unspecified local measurement —
  this never means adding a profiler dependency or a telemetry service (see the
  security invariant above). **Call counts are candidate evidence only** (e.g. how
  many `Ksoup.parse` calls N articles cost): reading the code establishes how often
  something runs, never whether CPU, memory, or allocation cost improved, so a count
  ranks a candidate in Step 3 but is never the before/after evidence. For work moved
  *out of* a transaction, the count is identical on both sides — measure it as
  lock hold time (axis 3 below).
- **Axis 2, perceived speed** — measure **milestone timestamps**, not total
  duration: window shown, first non-empty list paint, first moment the UI accepts
  input. Add temporary timestamps via the existing `core/Log` and read them from a
  manual launch. Report as "time until the user first sees something", never as
  "N seconds faster".
- **Axis 3** — total elapsed time before/after the serial→parallel switch, plus
  confirmation (via a test) that DB writes remained serialized. For moving CPU work
  out of a transaction (#15), the measurement is **transaction / lock hold duration**
  before and after — not total elapsed time, which barely moves, and not a call
  count, which does not move at all.
- **Concurrency hazard candidates (#16)** — there is nothing to time: the
  "measurement" is a **regression test that deterministically exercises the
  concurrent path**, fails before the fix (reproduces the race under a test
  dispatcher / controlled interleaving) and passes after. A candidate without
  a reproducing test is not proposed as fixed, only as a Red-tier finding for
  approval.

**Measurement code is never committed** — do not grow permanent instrumentation
hooks in production code. Every candidate must carry axis-appropriate before/after
evidence — a timed before/after, milestone timestamps, or (for a #16 concurrency
hazard, where there is nothing to time) a deterministic controlled-interleaving
regression test that fails before the fix and passes after; an `EXPLAIN` diff may
support an SQL candidate but must be paired with a repeatable query-execution
measurement. A candidate without one is not proposed.

### Step 3 — Inventory and tier the candidates

Label each candidate with its axis and its Green/Yellow/Red tier, then prioritize
by benefit ÷ risk. Every Red carries its integrity and sync-compatibility impact.
Every axis-2 candidate states **both sides of the trade**. Optionally run the
**`reviewer` agent** first to surface constraint-adjacent risk.

### Step 4 — Confirm scope (the single gate)

Present the tiered, prioritized list and use **`AskUserQuestion`** once to confirm
the Green and Yellow batches. Take Red items **one at a time**, each with its
impact stated. Proceed with only what was approved.

Each of these approvals is also its item's **one-time run confirmation**: the
bulk Green/Yellow approval covers every item in that batch, and each
individual Red approval covers that one item — once given, the item proceeds
straight through apply → re-measure → verify → commit (Step 5) with no further
approval gate, the same shape as `evaluate-review`'s Case B. Before Step 5
makes its first commit: if the current branch is a version branch (`v*`),
run `/feature-branch perf` to create and switch to a feature branch first
— Step 1's worktree check already ruled out unrelated dirty changes.

### Step 5 — Apply one at a time, verified

For each approved item:

1. Make one cohesive change.
2. Re-measure with that axis's method and confirm the improvement is real.
3. Re-run the tests:
   ```bash
   ./gradlew :composeApp:desktopTest
   ```
   The full suite must be green **and the Step 1 baseline set must survive in
   full** — a matching count is not sufficient, since a deleted test can hide
   behind a new one.
4. If the improvement cannot be measured, or the suite goes red, **revert that
   one item**, make **no commit** for it, and move on — record the reason for
   the "How to report" closing summary.
5. If this item changed something a doc *names* (per Step 7's criteria below),
   fix the stale doc reference now, **inside this same change**, so it rides
   along in this item's commit rather than a separate later one.
6. On success, review `git status --porcelain` / `git diff` and stage **only
   the files, and only the hunks, belonging to this item** (including any doc
   fix from step 5) — verify the staged diff contains nothing beyond this item
   before committing, so an unrelated pre-existing edit or residue from an
   earlier item's revert never rides along. Create **exactly one commit** with
   a **single-line** commit message only (no body, no trailer), in the repo's
   Conventional Commits style, e.g. `perf(data): batch getByFeedAndGuid
   lookups`. Never batch multiple items into one commit, and never amend a
   commit made earlier in this same run.

Watch `SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` especially — a
failure there means a DB/merge/sync regression slipped in.

### Step 6 — Tests

Changed logic in `domain/`, `data/`, or a ViewModel needs a test (#7). For
performance work, the valuable tests are **semantic-equivalence** tests: a batched
query returns the same rows in the same order as the loop it replaced; a
parallelized refresh still serializes its writes; an optimistic display still
dispatches its persisted write. Delegate to the **`test-writer` agent** if useful
and follow `docs/testing.md`.

**Never assert on wall-clock timings** — those tests are flaky by construction.

An axis-2 change can be UI-only with no runtime logic, which falls under #7's
UI-only exception; in that case add the check to `docs/testing.md`'s manual
verification list instead.

### Step 7 — Doc consistency

This check is applied **inline inside Step 5's loop**, immediately before the
commit for the item that triggered it — not as a separate later pass — so the
doc fix rides along in that same item's single commit.

Update only the docs whose stated performance characteristics actually changed.
**`docs/` is bilingual — every page has an English `X.md` and a Japanese
`X.ja.md`, so both must be updated together.** Likely targets:

- `docs/db-schema.md` — indexes, PRAGMAs, schema version.
- `docs/sync-architecture.md` — FTS index maintenance, merge, upload flow.
- `docs/background-update.md` — startup tasks, background loop.
- `docs/testing.md` — manual verification steps.
- `.claude/CLAUDE.md` — "Critical constraints" entries that name methods (e.g.
  `indexMissing()`, `maybeRebuildFtsIndex`).

**Do not edit `README.md` or `docs/external-spec.md`** — user-facing only (see
CLAUDE.md "Documentation").

### Step 8 — Constraint review + closing summary

Optionally run the **`reviewer` agent** over the accumulated changes
(`git diff "$BASE_SHA" HEAD`, using the `BASE_SHA` captured in Step 1) to
catch any architecture/constraint violation across everything just applied —
by this point every item has already committed itself independently in
Step 5, so a plain `git diff` against a clean working tree would show
nothing. There is no aggregate commit message to produce here — finish by
outputting the closing summary (see `## How to report`).

## How to report

- One entry per applied change: **axis** → location → **before/after measurement
  or milestone timestamps** (an SQL entry gives the execution measurement *and* its
  `EXPLAIN` diff, not the plan diff alone) → why the semantics are identical →
  tier → `Committed <sha>` with its one-line message, or `Reverted: <reason>`
  if it could not be confirmed.
- For axis-2 changes, state explicitly that **total time is unchanged** — never
  let a perceived-speed improvement read as a speed-up.
- The final test result, confirming the Step 1 baseline test set is intact
  (e.g. "all baseline tests pass, 0 failures, N added").
- For any applied Red change: the sync-compatibility impact (`user_version`
  change and what older devices will do).
- Docs updated — naming both languages of each pair.
- Anything deferred, with the reason (integrity risk / no measurable win /
  schema-compatibility cost not worth it / not worth overturning an existing
  deliberate trade).

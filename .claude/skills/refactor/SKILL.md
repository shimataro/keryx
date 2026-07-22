---
name: refactor
description: Refactor the Keryx source code for internal quality — reduce duplication, split oversized files/functions/composables, remove dead code, clarify naming, hoist magic numbers, flatten deep nesting, and modernize non-idiomatic Kotlin — behavior-preservingly, keeping the test suite green and never altering the sync/merge/FTS/error-taxonomy invariants or feature behavior. Works across the whole codebase or a single given path. Invoke explicitly with /refactor (optionally /refactor <path>), or when asked to "refactor the source code", "clean up the code", "reduce duplication", "improve code quality".
---

Improve the internal quality of Keryx's source **without changing observable
behavior**. The existing test suite is the oracle: it must stay green from the
first step to the last. Work in small, reviewable, independently-revertible
batches (Survey → confirm → batches). This is *refactoring*, not redesign and
not optimization — if a change alters what the program does, it does not belong
here.

## Scope

- Argument (`$ARGUMENTS`) is an optional path to narrow the sweep (e.g.
  `/refactor domain/`, `/refactor composeApp/src/commonMain/kotlin/.../ui/home`).
- **Default (no argument):** production source under
  `composeApp/src/commonMain/kotlin` + `composeApp/src/desktopMain/kotlin`.
- **Always excluded (every invocation, even an explicit path argument):**
  `build/` and generated code (SQLDelight, Compose Resources, `BuildConfig`) —
  those are regenerated, never hand-edited.
- Test code (`commonTest/`, `desktopTest/`) may be tidied, but **never in the
  same batch as the production code it verifies** (don't weaken the oracle and
  the code under test at the same time) and **never by deleting or weakening
  coverage** — every existing assertion must still hold.

## Refactoring targets (choose appropriately)

Pick the items that genuinely apply; don't churn code that is already clean.

- **Duplication** → extract a shared private helper or a `core/` utility. First
  **reuse what exists** instead of reinventing: `Result` extensions
  (`fold` / `onOk` / `onErr` / `map`), `IdGenerator`, `ReorderUtil`,
  `DateTimeParser`, `ErrorMessages`, `NotificationMessages`, `core/Constants.kt`.
- **Oversized files / functions / large `@Composable`s** → split into cohesive
  smaller units. Natural candidates: `ui/home/FeedListPane.kt`,
  `ui/settings/SettingsDialog.kt`, `desktopMain/main.kt`,
  `ui/common/KeryxDialogs.desktop.kt`, `ui/home/HomeViewModel.kt` (examples, not
  a mandate — split only where it improves clarity).
- **Dead / unused code** → unused private declarations, parameters, imports,
  unreachable branches. Lean on compiler warnings from a build.
- **Naming clarity** → intention-revealing English names, **local/private
  only**. Do **not** rename public/serialized identifiers, SQLDelight-derived
  snake_case properties, Compose Resource keys, or Koin/DI qualifiers.
- **Magic numbers / literals** → named constants in `core/Constants.kt` where a
  name adds clarity. Don't over-abstract one-off literals.
- **Deep nesting / complex conditionals** → guard clauses, early return, `when`,
  extract-method.
- **Non-idiomatic Kotlin** → scope functions (`let`/`also`/`apply`/`run`),
  null-safety operators (`?.`/`?:`/`requireNotNull`), exhaustive `when` over
  sealed types, immutability (`val`, immutable collections). Leave
  already-idiomatic code alone.
- **Comment / KDoc hygiene** → drop stale/redundant comments; ensure English
  (constraint #9). **Keep** the "why" comments that document the FTS / merge /
  `expect`-`actual` invariants — those are load-bearing.

**Performance / algorithmic optimization is NOT applied here.** Refactoring is
behavior-preserving by definition; speed / memory / algorithm / data-structure
optimization is a separate discipline that needs benchmarks, its own tests, and
a risk review.

- **Allowed:** incidental, obviously-safe, behavior-preserving micro-efficiencies
  that fall out of a structural refactor (reuse an existing map lookup instead of
  a linear scan, hoist a loop invariant, drop a redundant recomputation) — only
  when observable results are identical and the code is not on a protected hot
  path.
- **Not applied:** any change to algorithmic complexity, data structures, or the
  constrained hot paths (FTS `indexMissing()` / `'rebuild'`, `DatabaseMerger`
  connection, incremental indexing). **Surface these as report-only
  recommendations** (see `## How to report`) for a separate deliberate task.

## Do NOT touch (invariants)

These are hard stops — see `.claude/CLAUDE.md` "Critical constraints" #1–#9 and
`docs/*.md`. A refactor that crosses one of these is a redesign; stop and ask.

1. **No behavior / API / feature change** (#5). Don't alter the sync algorithm,
   `MergeSql` statement semantics or application order, the error taxonomy, or
   feature scope (`docs/sync-architecture.md`, `docs/error-design.md`).
2. **FTS rules** (#1): never add `articles_fts` to a `.sq` file, never DROP the
   live index, never swap `FtsManager.indexMissing()` for a full `'rebuild'` on a
   hot path.
3. **Merge stays on `DatabaseMerger`'s dedicated JDBC connection** (#2) — not the
   SQLDelight driver.
4. **No hardcoded user-facing strings** (#3) — keep Compose Resources
   (`values/strings.xml`, `getString`, `NotificationMessages`).
5. **Platform code stays behind `expect`/`actual`** (#4); desktop `actual`s in
   `desktopMain`.
6. **No new architecture / patterns** (#6, `docs/app-architecture.md`): keep the
   layered UI → ViewModel → Repository → DataSource shape, use SQLDelight
   generated classes as-is (no separate domain model classes), no Riverpod.
7. **English source** (#9) for comments, KDoc, and non-UI string literals.
8. **License attribution** (#8): only relevant if a shipped runtime dependency is
   removed as dead — then update `THIRD-PARTY-LICENSES.md`.

When a batch touches `ui/`, read the **`ui-guidelines` skill** first (pane tones,
divider policy, article card style, flat native-feel components, dialog rules).

## Steps

### Step 1 — Establish a green baseline

Confirm a reasonably clean working tree (`git status`), then run the tests — or
invoke the **`build` skill**:

```bash
./gradlew :composeApp:desktopTest
```

Record the baseline **from this run itself**, not from the `527` literal in
`docs/testing.md` (which drifts): capture both the passing **count** and the
**set of test identities** (class + method names) from the Gradle test report
(`composeApp/build/test-results/desktopTest/*.xml`, or the HTML report under
`composeApp/build/reports/tests/desktopTest/`). This recorded set is the oracle
checked in Step 4. If the baseline is **red**, stop and report — never refactor
on top of failing tests.

### Step 2 — Inventory candidates

Survey the in-scope files with read-only tools (Grep / Read, plus compiler
warnings from a build). Optionally run the **`reviewer` agent** to surface smells
and constraint-adjacent risks. Group findings into small, **independent** batches
by the target categories above, and **prioritize** (highest clarity gain / lowest
risk first).

### Step 3 — Confirm scope (the single gate)

Present the prioritized batch list and use **`AskUserQuestion`** once to
confirm or narrow what to apply (e.g. all of it, only `domain/`, or exclude
`ui/`). Proceed with only the approved batches.

### Step 4 — Apply batches (behavior-preserving, verified)

For each approved batch:

1. Make one cohesive change set — small enough to review and revert on its own.
2. Recompile and re-run tests:
   ```bash
   ./gradlew :composeApp:desktopTest
   ```
3. The **full suite must stay green** and the **Step 1 baseline set must survive
   in full** — every recorded baseline test still runs and passes. A
   matching-or-higher count is *not* sufficient: a deleted test hidden behind a
   new one leaves the count unchanged. Only a **net addition** of tests is
   allowed, unless an approved **test-only batch (Step 5)** deliberately changed
   the set. On red **or a missing baseline test**, fix or revert *that batch*
   before moving on (feedback loop: change → test → fix/revert → repeat).
4. Never cross an invariant from `## Do NOT touch`.

Watch `SchemaTest` / `SyncMergerTest` / `SyncRepositoryTest` especially — a
failure there means a DB/merge/sync regression slipped in.

### Step 5 — Tests

Existing tests are the oracle: they must stay green, and a refactor must
**never delete or weaken coverage** (no removed or loosened assertions). Two
kinds of test change are allowed, each in its **own batch, never mixed with the
production code it verifies** (per `## Scope`): (a) **test-only tidying** — the
same behavior-preserving cleanups you apply to production code, as long as every
existing assertion still holds; (b) **new coverage** for a newly extracted seam
(constraint #7). Delegate the writing to the **`test-writer` agent** if useful.
Follow `docs/testing.md` conventions.

### Step 6 — Doc consistency check (targeted, auto-fix)

Refactoring doesn't change behavior, so user-facing docs stay put — but the
architecture docs name specific classes, files, and constants, which a split /
move / rename / constant change can leave stale. **Only when a batch actually
changed something the docs *name*** (a moved/split/renamed class or file, a
changed directory layout, or an added/removed/renamed constant), re-check the
affected docs and **update the stale references in place**:

- `.claude/CLAUDE.md` — the Architecture block, "Critical constraints" that cite
  method names (e.g. `indexMissing()`, `maybeRebuildFtsIndex`), and Environment.
- `docs/app-architecture.md` — "Key Classes" + the directory-structure listing.
- `docs/error-design.md` — `core/Constants.kt` constant names.
- `docs/db-schema.md` / `docs/sync-architecture.md` — only if a named class/flow
  moved.

**Doc edits follow the doc's own language — Japanese** for `docs/*.md` and
`CLAUDE.md` (source code stays English, #9). **Do NOT edit `README.md` or
`docs/external-spec.md`** — behavior is unchanged and `README.md` is user-facing
only (see CLAUDE.md "Documentation"). This is a targeted pass, not a full
re-read of every doc.

### Step 7 — Constraint review + commit message

Optionally run the **`reviewer` agent** over the accumulated diff (`git diff`) to
catch any architecture/constraint violation before finishing. Then output an
English Conventional Commits message (`refactor(scope): …`) per `.claude/CLAUDE.md`
"Commit messages". **Do not commit unless asked.**

## How to report

- One line per applied batch: category → files → why behavior is preserved.
- The final test result — passing count plus confirmation the Step 1 baseline
  test set is intact (e.g. "all baseline tests pass, 0 failures, N added").
- Any doc files updated for consistency (Step 6) — which file and what drifted.
- Anything deferred or skipped, with the reason.
- A **"Performance opportunities (report-only, not applied)"** list — each item:
  location + the suspected speed/memory win + why it was left out
  (behavior-affecting / needs a benchmark / on a protected hot path). This is the
  hand-off for a separate deliberate optimization task.
- The ready-to-use `refactor(...)` commit message.

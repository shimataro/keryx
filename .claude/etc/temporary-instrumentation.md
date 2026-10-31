# Confirming a claim with temporary instrumentation

Read by any skill or agent that must back a claim with evidence before acting on it — an
unconfirmed review finding, a performance hypothesis. The caller decides **when** this applies and
**where** the outcome is recorded; this file defines **how**.

## 1. Gathering evidence

Observe the claim directly, in this order of preference:

1. **A test that reproduces the claim** (it fails if the claim is right).
2. **Temporary logging or assertions** — log through the existing `core/Log` — observed by running
   the relevant `./gradlew` test target.
3. **A manual run** — give the user the steps and ask for the result.

Measure automatically rather than manually wherever possible: options 1 and 2 come first, and a
manual run is the last resort when neither can observe the claim. To automate, mocks and fakes (Ktor
`MockEngine`, the fakes under `testing/`, an in-memory DB) are fine as long as they do not affect
what the evidence proves — never mock the part the claim is actually about (e.g. the real driver's
behavior, real concurrent execution); observe that part for real. When a mock is used, record what
it replaced and why that does not affect the conclusion.

## 2. Recording the outcome

Record what was inserted and what was observed, wherever the caller says. A claim that cannot be
observed at runtime (doc conformance, naming, conventions) needs no measurement; record that
instead.

Whether a reproducing test may stay as a permanent test is the caller's decision; unless the caller
keeps it, it is temporary code under §3.

## 3. Rules for the temporary code

- Insert it only into files with no uncommitted changes, so removing it can never take pre-existing
  work with it. The one exception is §4's post-fix re-measurement.
- Tag every inserted line or block with the marker comment `TEMP-INSTRUMENTATION`, so a leftover
  from an interrupted run can be found with `grep` and removed.
- **Always remove it** once the evidence is in, and confirm with `git status --porcelain` /
  `git diff` that the tree is back to its pre-insertion state before moving on — this keeps any
  clean-tree baseline the caller relies on intact.
- Never commit it, and never carry it into a plan as permanent code. Before staging or committing,
  run the marker check below and confirm it prints nothing. It covers untracked files (a temporary
  test) and excludes `.claude/`, whose policy files contain the marker literally:

  ```bash
  git grep -n --untracked TEMP-INSTRUMENTATION -- ':!.claude'
  ```

- Never log article bodies, tokens, or URLs carrying credentials, and never add an external profiler
  or telemetry service — `docs/external-spec.md` §10 promises no data leaves the device.

## 4. Re-measuring after the fix

When the fix's effect is itself measurable (performance, call counts, timing), re-insert the same
measurement after implementing the fix, compare before/after, and remove it again under §3 once
confirmed.

The fix is usually still uncommitted at this point, so the re-measurement may go into a file whose
only uncommitted changes are the fix under test (and nothing else). Save its `git diff` to the
scratchpad before inserting; after removing the measurement, `git diff` must match that saved copy
exactly — the fix intact, only the measurement gone. Everything else in §3 still applies.

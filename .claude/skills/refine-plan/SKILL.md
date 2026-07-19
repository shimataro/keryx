---
name: refine-plan
description: Iteratively review and refine a drafted plan (implementation plan, design doc, or the active Claude Code Plan Mode plan) against a fixed checklist — open questions, data integrity, UI/UX friction, redundant/inefficient steps, drift from the current codebase, scope creep, risk/reversibility, verification coverage, and step sequencing. Auto-fixes each issue found and restarts the checklist from the top until a full pass is clean or a genuine user decision is needed, then trims the plan down to only the finally-agreed content. Invoke explicitly with /refine-plan — e.g. right before ExitPlanMode — or when asked to "review/refine/refactor this plan" or "check the plan for issues".
---

# Refine Plan

Review a plan document against the checklist below, fix what you can fix
yourself, and converge on a clean, final plan. This skill edits the **plan**,
never the code the plan describes.

## Target

Resolve which document to work on, in this order:

1. `$ARGUMENTS`, if given (a file path, or plan text pasted directly).
2. The active Claude Code Plan Mode plan file, if currently in Plan Mode
   (its path is named in the plan-mode system reminder).
3. Otherwise, ask the user which plan/design document to check.

## Checklist (run every item, every pass)

1. **Open questions & concerns** — list anything ambiguous, underspecified,
   or that the plan currently glosses over.
2. **Data integrity** — schema/state changes, concurrent access, merge/
   conflict handling, invariants that must hold before and after.
3. **UI/UX** — clarity of the resulting flow, reduced user effort, and
   consistent terminology; no regressions to existing patterns.
4. **Simplicity & efficiency** — drop redundant steps; flag anything doing
   more work than necessary; prefer the simpler approach when two achieve
   the same result.
5. **Match with the actual codebase** — cross-check every claim ("X doesn't
   exist yet", "Y already handles this") against the real source via
   Grep/Read, not memory or assumption. Flag anything the plan proposes
   that's already implemented, or that assumes an API/file that doesn't
   exist.
6. **Scope** — no unrequested extras (YAGNI), and nothing essential silently
   dropped.
7. **Risk & reversibility** — destructive, hard-to-reverse, or
   shared-system-affecting steps are called out explicitly and flagged for
   user confirmation at execution time, rather than silently planned.
8. **Verification** — the plan states how each change will be confirmed
   working (tests, manual steps, build/run) rather than ending at "make the
   change."
9. **Sequencing** — steps are ordered so prerequisites precede dependents
   (e.g. schema changes before code that reads the new schema).

## Iteration loop

Repeat until convergence:

1. Run the full checklist above against the current plan content.
2. Fix anything you can resolve yourself by editing the plan document, then
   **restart from checklist item 1** — a fix can invalidate an earlier
   "clean" verdict elsewhere (e.g. simplifying a step changes its
   data-integrity story).
3. If an issue needs the user's judgment (a genuine product/design decision,
   information only they have, a tradeoff with no clearly correct answer),
   stop and ask — batch multiple such questions together with
   `AskUserQuestion` rather than trickling them one at a time.
4. Stop when one full pass finds zero fixable issues and zero open
   questions, or when you're blocked on the user.

On plans that take several passes, track per-pass checklist status (e.g. via
`TodoWrite`) so progress is visible.

## Finalize

Once the loop converges, rewrite the plan to contain **only the
finally-agreed content**: strip scratch notes, rejected alternatives,
resolved open questions, and commentary about the review itself. The result
should read as a clean, executable plan — not a changelog of the review.

## How to report

- Per iteration: what was found and what was fixed, one line each.
- End state: either "clean after N passes" or the specific question(s)
  still blocking convergence.
- Confirm the plan was trimmed to final content only.

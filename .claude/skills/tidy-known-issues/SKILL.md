---
name: tidy-known-issues
description: Tidy `docs/known-issues.md` and every translation of it (`docs/known-issues.*.md`) by cross-checking each entry against the current source — classify entries as removable (resolved / obsolete), incorrect (no longer matches the code), redundant (verbose or historical narrative), or now fixable (deferred work, or a constraint the architecture has since lifted). After one approval of the findings, the removable / incorrect / redundant entries are fixed and committed one entry at a time; the fixable ones get an implementation plan, refined with refine-plan and presented for approval, without implementing it. Invoke explicitly with /tidy-known-issues, or when asked to "tidy / clean up / refresh known-issues".
---

# Tidy Known Issues

> This skill involves **high-accuracy judgments** — deciding that a defect is really gone, or
> that a recorded constraint no longer holds, from the code alone. **Run it in an opus session.**
> Skills inherit the caller session's model and cannot pin one in frontmatter, so verify the
> session model is opus before executing; if it is not, tell the user and ask whether to continue.

`docs/known-issues.md` records defects deliberately left unfixed. Over time it collects entries that
were fixed, descriptions that no longer match the code, changelog narrative, and deferred defects
that a later architecture change has made fixable. This skill finds and resolves all four.

## Translations

Never assume the translations are Japanese only. The set of translations is **whatever
`ls docs/known-issues.*.md` lists at the time of the run**; every step that edits `known-issues.md`
edits every listed translation the same way, in the same item. Likewise, when another design doc is
edited (a reason moved into it, a reference fixed), list `docs/<name>.*.md` and update all of them.
This is `.claude/rules/docs-translation.md`.

## Step 0 — Guard

1. If Plan Mode is active, stop: Step 3 edits and commits. Tell the user to leave Plan Mode and
   re-run.
2. Run `git status --porcelain`. If it prints anything, warn and stop (`Working tree not clean`) —
   this clean state is the baseline of `.claude/etc/per-item-commit.md` §1.
3. Read the criteria the classification uses:
   - the opening paragraphs of `docs/known-issues.md` — the **admission rule**: an entry needs an
     external root cause, or a fix that needs a redesign outside the scope of the change that found
     it; "it rarely happens" is never enough;
   - `.claude/CLAUDE.md` "Error handling and race conditions" and "Bug fixes";
   - `.claude/agents/review-docs.md` "Checklist — stale and historical content".

## Step 1 — Investigate (no doc changes)

Read `docs/known-issues.md` and every translation. For each entry (`##` heading), cross-check every
identifier, file, function, commit, version and procedure it cites against the current source with
Grep/Read — never from memory. Classify it as exactly one of the categories below, and record the
evidence for the verdict (`file:line`, commit, version, or a run's output). A verdict without
evidence is not allowed.

| Category | Meaning | Examples |
| --- | --- | --- |
| **Removable** | The entry no longer describes an open defect | `**Status**` says resolved; the code that caused it no longer exists; the affected platform or build is gone |
| **Incorrect** | The entry is technically wrong now | an identifier, file, API or procedure that no longer matches the code; the code now uses a different approach; a stale re-check record |
| **Redundant** | The content is right but wordier than it needs to be | "previously / used to / no longer" narrative, repeated explanations, the same fact stated twice. Keep the *why* behind a decision that still holds; drop the story of how the code got there |
| **Fixable** | The defect can and should be fixed now | deferred because the change was large; a constraint lifted by a later architecture change; an external cause fixed upstream (and the fix still needs code here); an entry that fails the admission rule — its only real reason is rarity, which `.claude/CLAUDE.md` treats as a defect to fix |
| **Unconfirmed** | Neither the code nor an automated run can settle it | behavior that only shows on real hardware or a specific OS. Treat it like `.claude/etc/temporary-instrumentation.md` §2's "unconfirmed": never delete or rewrite on a guess |
| **No change** | The entry is accurate, concise and still belongs | — |

An entry can have several problems (e.g. incorrect *and* redundant); classify it under the most
consequential one (Fixable > Removable > Incorrect > Redundant) and list the rest in its planned
action, so one entry stays one item.

Also check:

1. **Re-check procedures.** For an entry with a "Re-checking after …" section, compare the versions
   recorded at its last re-check with the current ones (`gradle/libs.versions.toml`, the Xcode / iOS
   in use). If anything relevant changed, run the procedure now:
   - Mark every temporary change (removing an `@Ignore`, reverting a mitigation, taking out a
     workaround) per `.claude/etc/temporary-instrumentation.md` §3, remove it afterwards whatever the
     outcome, and confirm `git status --porcelain` is back to the Step 0 baseline before going on.
   - A step that needs manual interaction (a simulator gesture, real hardware): give the user the
     steps and ask for the result; if it cannot be run, the entry is **Unconfirmed**.
   - Fixed upstream → **Fixable** (removing the workaround and the entry is planned in Step 4).
     Still broken → **Incorrect** (update the re-check date and versions in Step 3).
2. **Template.** The entry has a `**Status**` line, and — when its cause is known — a "What a real
   fix would need" section.
3. **Point-in-time external claims** with no way to re-check them ("the newest release is …", a bare
   year), per review-docs' checklist → **Incorrect** (rephrase to point at how to re-check).
4. **Translation parity.** Each listed translation has the same headings, order and tables as the
   English file, with nothing missing or translated from an older version.
5. **Inbound references.** For every entry to be deleted or renamed, find what points at it:

   ```bash
   grep -rn "known-issues" docs .claude shared composeApp androidApp androidGms appleApp testing
   ```

   plus a search for the heading text itself — code KDoc/comments, the other `docs/*.md` and all
   their translations, and the lists in `.claude/agents/review-ui.md` /
   `.claude/agents/review-concurrency.md` and the `ui-guidelines` skill (e.g. its "Gaps and node
   count" section, which exists only for one entry's workaround).
6. **Where the reason goes.** If an entry to be deleted is the only record of *why* the current code
   looks the way it does, plan to fold that reason into the design doc it explains (e.g.
   `app-architecture.md` and all its translations) in one to three lines, as review-docs requires.
7. **Dangling references.** A comment or doc that cites `known-issues.md` for something no entry
   covers → **Incorrect**, fixed at the reference.

## Step 2 — Present the findings and get one approval

Output a table: number / entry heading / category / evidence / planned action / references affected.
Show **Unconfirmed** and **No change** entries in a separate table, with what was checked.

Ask for approval once with `AskUserQuestion` (the user can exclude numbers through "Other"). This is
the one-time run confirmation of `.claude/etc/per-item-commit.md` §1, covering Step 3's commits and
Step 4's planning. If the user rejects it, change nothing and go to Step 5.

## Step 3 — Apply Removable / Incorrect / Redundant, one entry per commit

For each approved entry, in table order (one item's failure never stops the rest):

1. Edit `docs/known-issues.md` and every listed translation identically:
   - **Removable** — delete the whole entry. In the same item, move its reason into the design doc
     (Step 1-6) and fix every inbound reference (Step 1-5): drop or rewrite the "see known-issues"
     wording, and update the lists in agents and skills.
   - **Incorrect** — correct it to match the current code.
   - **Redundant** — cut it down to the current state; keep the reason behind decisions that hold.
   - A changed heading also updates every reference that quotes it.
2. Verify: relative links resolve, and a grep for the old heading text finds nothing left. If the item
   touched anything outside `docs/` and `.claude/` (a code comment), also run the tests
   `.claude/etc/per-item-commit.md` §2 requires for those files.
3. On failure, revert per `.claude/etc/per-item-commit.md` §3 and record why. On success, commit per
   §4 — one commit, Conventional Commits, e.g. `docs(known-issues): remove resolved <topic> entry`.
   On a `v*` branch, §4 runs `/feature-branch` before the first commit.

After the last item, dispatch the `review-docs` agent directly (as `refactor` and `perf-tune` do)
over this run's commits. Fix each finding it reports the same way, one commit per finding.

## Step 4 — Plan the Fixable entries

If at least one approved entry is **Fixable**:

1. Call `EnterPlanMode` and write the plan to `~/.claude/plans/tidy-known-issues-<YYYY-MM-DD>.md`:
   - **Context** — which entries, and why each is fixable now.
   - **One section per entry** — files to modify; the changes; deleting the entry from
     `docs/known-issues.md` and every translation and fixing its inbound references; moving its
     reason into the design doc (all translations) where Step 1-6 applies; tests per `.claude/CLAUDE.md`
     constraint #7 and `docs/testing.md` (a race fix is tested by forcing the interleaving). Note
     when another skill fits an entry better (`perf-tune` for performance work, say).
   - **Verification** and **Commit Strategy** (one commit per entry).
2. Apply the `refine-plan` skill to the plan file, resolve any question it raises, then call
   `ExitPlanMode`.
3. **Do not implement**, even once the plan is approved: that approval is not an instruction to start.
   Say the plan is ready and that implementation starts when the user asks for it.

## Step 5 — Closing summary

A table of number / entry / category / outcome — the commit SHA; not addressed, with the reason
(excluded by the user, verification failed, disproved); unconfirmed, with what was checked; or
planned. Add "Apple targets not verified locally" where `.claude/etc/per-item-commit.md` §2 calls
for it.

---
name: audit-claude-config
description: Audit configuration under `.claude/`. Inspect CLAUDE.md, rules, skills (SKILL.md), subagents (agents/*.md), etc/ shared fragments, and settings(.local).json for outdated / incorrect / ambiguous / redundant / missing descriptions, files to add or remove, content that should be split from CLAUDE.md into rules/, role changes (rules↔skills↔agents), and model and effort assignments, then output recommended fixes with rationale. Invoke explicitly with /audit-claude-config. Also triggered by phrases like "review .claude", "audit settings/rules/skills/subagents".
model: opus
effort: medium
context: fork
---

# .claude Configuration Audit

Inspect everything under `.claude/` (`CLAUDE.md`, `rules/`, `skills/*/SKILL.md`, `agents/*.md`,
`etc/**` shared fragments, `settings.json`, `settings.local.json`) **following Claude Code official
best practices and
cross-checking against the current source code**, then output recommended fixes with rationale.

> This skill involves **high-accuracy judgments**. It runs in a forked subagent context so its
> frontmatter pins `model: opus` at `effort: medium` for the whole workflow.

## Process

1. List and read every file under `.claude/` (`find .claude -type f`). Also check whether `CLAUDE.md`
   exists separately at the repository root.
2. **Back up every claim against the current source** (see Verification points below). Do not guess
   "correct / outdated".
3. Summarize findings per category below and write **recommended fixes with rationale**. Write "none" when there is nothing to report.
4. If applying changes, confirm at the end with `git status` that only `.claude/` is affected.

## Investigation categories (output all six in this order)

1. **Outdated / incorrect / ambiguous / redundant / missing** — descriptions inconsistent with source,
   statements open to multiple interpretations, excessive duplication with other files, or missing items that should be documented.
2. **Files to add** — skills / agents / commands that are missing but should exist.
3. **Files to remove** — files that no longer serve a purpose or are duplicated.
4. **Should `CLAUDE.md` be split into `rules/`?** — If bloated, split via `@` imports; otherwise keep as-is.
5. **Files that should change roles** — e.g. ever-loaded doc → on-demand skill, rule → agent, etc.
6. **Model and effort assignments** — whether subagent/skill models and effort levels match their roles (see the model and effort policies below).

## Verification points (concrete back-up methods)

- **`@` imports resolve relative to the file.** From `.claude/CLAUDE.md`, `@../docs/foo.md` points to
  `docs/foo.md` at the repo root (`@.claude/foo.md` would wrongly point to `.claude/.claude/foo.md`).
  You can verify whether it actually reaches by checking if the file body is injected into the session
  context at the top of the conversation (this skill has previously detected a broken import because
  `ui-guidelines.md` was missing even though `docs/*.md` were expanded).
- **`@` imports do not work in `agents/*.md`.** They are expanded in `CLAUDE.md`, but an agent
  definition's own `@path` is not — an agent that needs a document must be told to `Read` it by path.
  Flag any `@` import found in `.claude/agents/`: it is silently doing nothing. (Found once in
  `reviewer.md`, whose `@`-imported design doc turned out never to reach the agent; it has since
  been removed, so there is currently no `@` import under `.claude/agents/` to compare against.)
- **Shared fragments under `.claude/etc/`** are not auto-loaded and are not `@`-imported either;
  they reach an agent only because the agent is instructed to read them. Check that every file in
  `.claude/etc/` still has at least one reader, and that each reader names the correct path.
- **Verify existence of described symbols**: gradle task names, class names, file paths, constants, etc.
  should be checked with `grep`/`find` against the current source (e.g. `generateCommonMainKeryxDatabaseInterface`,
  `LICENSES_URL`, count of `.sq`/`.sqm` files).
- **Alignment with official best practices**:
  - Keep `CLAUDE.md` concise. Consider **converting large domain-specific guides (hundreds of lines) into on-demand skills** rather than importing them constantly (saves context when only loaded for related work).
  - Check whether the split between `settings.json` (shared, committed) and `settings.local.json` (personal, gitignored) is appropriate (safe shared permissions in the former, machine-specific / personal permissions in the latter).
  - Skill `SKILL.md` frontmatter should have a `name` and a sufficiently specific `description` for launch detection.
  - Subagent frontmatter should have `name`/`description`/plus `tools`/`model`/`effort` when needed.
- **Model policy**: Both subagents (`agents/*.md`) and skills (`SKILL.md`) set `model:` and `effort:`
  in frontmatter; a skill that omits them inherits the caller session's. Work requiring high accuracy
  (detecting design violations, checking long documents against code, implementing or testing under
  many constraints, audits involving difficult judgment) should use `model: opus`. Well-scoped work
  centered on following existing patterns can use `sonnet`. Lightweight or reference-only skills may
  leave both unset.
- **Effort policy**: `effort: medium` is the baseline. Raise one to `high` only after a concrete miss
  at medium has been observed, and record why next to the setting. If a `sonnet` agent falls short,
  switch it to `opus` rather than raising its effort. Flag any `high`/`xhigh`/`max` without a stated
  reason, and any body text that asks the user to switch models instead of pinning one.

## Output format

Use the investigation categories above as headings. For each item write:

- **Findings** (what the issue is / "none" if nothing to report)
- **Recommended fix**
- **Rationale** (why it should be done, citing source evidence or official best practices)

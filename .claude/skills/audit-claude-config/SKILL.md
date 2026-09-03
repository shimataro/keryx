---
name: audit-claude-config
description: Audit configuration under `.claude/`. Inspect CLAUDE.md, rules, skills (SKILL.md), subagents (agents/*.md), etc/ shared fragments, and settings(.local).json for outdated / incorrect / ambiguous / redundant / missing descriptions, files to add or remove, content that should be split from CLAUDE.md into rules/, role changes (rules↔skills↔agents), and model assignments, then output recommended fixes with rationale. Invoke explicitly with /audit-claude-config. Also triggered by phrases like "review .claude", "audit settings/rules/skills/subagents".
---

# .claude Configuration Audit

Inspect everything under `.claude/` (`CLAUDE.md`, `rules/`, `skills/*/SKILL.md`, `agents/*.md`,
`etc/**` shared fragments, `settings.json`, `settings.local.json`) **following Claude Code official
best practices and
cross-checking against the current source code**, then output recommended fixes with rationale.

> This skill involves **high-accuracy judgments**. **Run it in an opus session.**
> Skills inherit the caller session's model and cannot fix a model in frontmatter,
> so verify the session model is opus before executing.

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
6. **Model assignments** — whether subagent/skill models match their roles (see policy below).

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
  - Subagent frontmatter should have `name`/`description`/plus `tools`/`model` when needed.
- **Model policy**: Subagents requiring high accuracy (detecting design violations, implementing under many constraints, audits involving difficult judgment) should use `model: opus`. Mechanical work centered on following existing patterns can use `sonnet`. Skills cannot set `model:` in frontmatter and inherit the caller's model, so skills requiring high accuracy should state in the body that they should be run in an opus session.

## Output format

Use the investigation categories above as headings. For each item write:

- **Findings** (what the issue is / "none" if nothing to report)
- **Recommended fix**
- **Rationale** (why it should be done, citing source evidence or official best practices)

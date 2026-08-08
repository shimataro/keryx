---
name: feature-branch
description: Ensure the current working directory is on a feature branch (not a version branch v*). If on v*, propose and create a feature branch following the repo's Branching convention. Invoke with /feature-branch <type> [summary-hint], e.g. /feature-branch feat "add search filter".
---

Ensure the repository is on a feature branch before making commits.

## Step 1 — Check current branch

```bash
git branch --show-current
```

- If the output is **empty** (detached `HEAD`), proceed straight to Step 2 — there is no
  existing feature branch to already be on.
- If the current branch is **not** a version branch (`v*` — e.g. `v0`, `v1`), output:

  ```text
  Already on a feature branch (<branch>). Nothing to do.
  ```

  and stop.

- If the current branch **matches** `v*` (e.g. `v0`, `v1`, the default/integration branch), proceed to Step 2.

## Step 2 — Derive branch name candidates

Use the arguments provided to the skill (`$ARGUMENTS`):

- **`<type>`** (required): the Conventional Commits type (`feat`, `fix`, `refactor`, `perf`, `docs`, `chore`, `build`, `test`, `ci`, `style`).
- **`[summary-hint]`** (optional): 2–5 lower-case kebab-case words summarising the change. If omitted, use a generic summary derived from the `<type>`.

Generate **2–4 candidates** (best first) following the repo convention `<type>/<kebab-summary>` (see `.claude/CLAUDE.md` → "Branching"):

1. The primary candidate using the provided (or derived) summary.
2. One or more shorter/different-wording alternatives.

Example for `/feature-branch feat "add drag-and-drop reordering"`:

1. `feat/add-drag-and-drop-reordering`
2. `feat/feed-list-drag-reorder`
3. `feat/drag-and-drop`

For each candidate, check whether a local branch of that name already exists:

```bash
git rev-parse --verify --quiet refs/heads/<candidate>
```

Drop any candidate that already exists and generate a replacement (e.g. append `-2`, `-3`, ... to
the summary) so every candidate presented in Step 3 is actually available. This matters most for
a generic, hint-less invocation (e.g. `/feature-branch fix`), where repeated runs would otherwise
keep deriving the same generic candidate.

## Step 3 — Prompt for selection

Present the candidates via `AskUserQuestion` with an additional **"Other"** option so the user can type their own. Include a brief explanation of why a feature branch is needed (per `.claude/CLAUDE.md` "Branching").

## Step 4 — Create the branch

If the user picks a candidate (or supplies their own via "Other"):

1. Validate the name:

   ```bash
   git check-ref-format --branch "<name>"
   ```

   If this fails, or the name does not match the repo convention `<type>/<kebab-summary>`
   (`<type>` one of the Conventional Commits types listed in Step 2, `<kebab-summary>` lower-case
   kebab-case), explain why and re-prompt via `AskUserQuestion` for a corrected name instead of
   proceeding.

2. Check for a collision:

   ```bash
   git rev-parse --verify --quiet refs/heads/<name>
   ```

   If it already exists (exit code `0`), tell the user and re-prompt via `AskUserQuestion` — do
   not silently overwrite it or let `git switch -c` fail. This also covers an "Other" name, which
   bypasses the Step 2 pre-filtering.

3. Create and switch to the branch:

   ```bash
   git switch -c "<name>"
   ```

   This carries any staged/unstaged changes along (no stash needed).

4. Output a confirmation:

   ```text
   Switched to a new branch '<name>'.
   ```

## Rules

- Never create or switch branches without the user's explicit selection or a name they provided.
- Once on a feature branch (i.e. not `v*`), this skill is a no-op.

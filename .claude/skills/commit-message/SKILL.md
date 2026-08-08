---
name: commit-message
description: Generate a Conventional Commits message (in English) for the currently staged changes (`git diff --cached`) and output it without committing. If nothing is staged, output a short "no staged changes" message instead. Invoke explicitly with /commit-message, or when asked to "write a commit message for the staged diff", "generate a commit message from staged changes", etc.
---

Generate a commit message for the **staged** changes only and output it.
Do **not** run `git commit`.

## Step 1 — Detect staged changes

Check whether anything is staged:

```bash
git diff --cached --quiet
```

- **Exit code 0** → the staging area is empty (no staged changes).
- **Non-zero exit code** → there are staged changes.

## Step 2 — Handle the empty case

If there are no staged changes, output exactly this message and stop (do nothing
else):

```text
No staged changes — nothing to commit.
```

## Step 3 — Read the staged diff

If there are staged changes, read them (staged content only — ignore unstaged /
working-tree changes):

```bash
git diff --cached --stat
git diff --cached
```

## Step 4 — Write the commit message

Produce a **Conventional Commits** message in **English**, matching this repo's
convention (see `.claude/CLAUDE.md` → "Commit messages"):

- Format: `<type>(<scope>): <subject>`
- `type`: one of `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `build`,
  `perf`, `style`, `ci` — choose from the nature of the change.
- `scope`: the touched area, derived from the diff (e.g. `sync`, `ui`, `build`,
  `docs`, `deps`). Omit the `(scope)` if no single area fits.
- `subject`: concise, imperative mood, lower-case start, no trailing period.
- Add a body only when the change genuinely needs explanation (the *why* /
  non-obvious *what*). Separate it from the subject with a blank line. Keep it
  short.

## Step 5 — Branching

If the current branch is a version branch (`v*`), run `/feature-branch <type>`
(using the Conventional Commits type from Step 4) to create and switch to a feature
branch before this message is used to commit.

## Step 6 — Output

Output the commit message only, inside a fenced code block so it is easy to
copy. Do not commit.

---
name: feature-branch
description: Ensure the repository is on a feature branch, propose a branch name, and create or rename it. If on a version branch (v*), create a new feature branch. If already on a feature branch, offer to rename it. Branch names follow the repo's <type>/<kebab-summary> convention. Invoke with /feature-branch [<type>] [summary-hint] or /feature-branch rename.
---

Ensure the repository is on a feature branch before making commits. When already on a feature
branch, offer to rename it based on the current changes.

## Step 1 — Check the current branch

```bash
git branch --show-current
```

- **Empty output** → detached `HEAD`. Treat like a version branch for creation (Step 5/6), but like
  a non-version branch when resolving the base branch in Step 2.
- A name matching `v*` (e.g. `v0`, `v1`) → a version branch. Proceed to Step 2.
- Anything else → a feature branch. Proceed to Step 2; the action will be **rename** unless the
  user explicitly asked to create a new branch.

## Step 2 — Resolve the base branch

This step is needed only when generating candidates from the diff (no `<type>` argument, or the
first argument is `rename`). When a `<type>` argument is provided, skip to Step 3 and use that type.

### With an explicit base argument (`$ARGUMENTS` second word or after `rename`)

Use the argument as the base if it is **exactly** (case-sensitively) one of the branch names:

```bash
git for-each-ref --format='%(refname:short)' refs/heads refs/remotes
```

Exclude `origin/HEAD` (and any bare remote name such as `origin`). Do not decide existence with
`git rev-parse` alone: on a case-insensitive filesystem (macOS by default) it resolves `V0` to the
loose ref file of `v0`, hiding the typo.

If no branch matches exactly and none matches case-insensitively either, use the argument as the
base as-is only when it is one of:

- exactly (case-sensitively) one of the tag names — `git for-each-ref --format='%(refname:short)' refs/tags`
  (not `rev-parse`, for the same case-insensitivity reason) — whose target is a commit:
  `git rev-parse --verify --quiet "refs/tags/<arg>^{commit}"` must succeed;
- a commit hash — 4 to 64 hexadecimal characters — that `git rev-parse --verify --quiet "<arg>^{commit}"`
  resolves to a hash starting with the argument (compared case-insensitively).

Any other revision expression (`HEAD`, `HEAD~1`, `@{u}`, `origin`, `origin/HEAD`, …) is not a base.

Otherwise, look for similarly named branches in that list — a wrong plural, case, or separator is an
easy mistake to make. Judge closeness yourself, closest first:

1. Differs only in letter case or separators (`-`, `_`, `/`).
2. Differs only in a singular/plural ending (a trailing `s` / `es`).
3. Differs only in a remote prefix (`origin/`).
4. One contains the other.
5. Small edit distance (a typo).

Present up to 4 of the closest via `AskUserQuestion`, closest first, each `description` stating how
it differs from the argument ("Other" lets the user retype). Continue with the one picked as the
base. If nothing is reasonably close, output the following and stop:

```text
No branch named '<arg>' (and no similarly named branch) found.
```

### Without an argument

- **Not on `v*`** (including detached `HEAD`) → the parent branch if one can be detected, otherwise
  the default branch. This keeps a branch cut from a release branch or another unmerged feature
  branch from being named after its parent's changes too.

  1. Resolve the default branch:

     ```bash
     git symbolic-ref --short refs/remotes/origin/HEAD   # e.g. origin/v0
     git rev-parse --verify --quiet "<default>^{commit}" # the target must exist
     ```

     If either fails, tell the user to pass the base branch as an argument and stop.

  2. List parent candidates — branches that `HEAD` contains but the default branch does not:

     ```bash
     git for-each-ref --merged=HEAD --no-merged="<default>" \
       --format='%(refname:short)' refs/heads refs/remotes
     ```

     Exclude the current branch itself, a remote branch of the same name (`<remote>/<current
     branch>`), and `*/HEAD`.

  3. Measure each candidate's distance and pick the nearest as the base:

     ```bash
     git rev-list --count "<candidate>"..HEAD
     ```

     Treat a local and a remote branch at the same commit as one candidate, shown by its local name.

     - If several candidates at different commits share the smallest distance, present them and
       the default branch via `AskUserQuestion` and use the one picked.
     - If there are no candidates, use the default branch.

- **On `v*`** → `origin/<current branch>`, so the proposal covers what has not been pushed yet:

  ```bash
  git rev-parse --verify --quiet "refs/remotes/origin/<current branch>"
  ```

  If it does not exist, tell the user to pass the base branch as an argument and stop — do not fall
  back to another branch.

Output the resolved base in one line (e.g. `Base: origin/v0`).

## Step 3 — Derive branch name candidates

### With a `<type>` argument (not `rename`)

Use the arguments provided to the skill:

- **`<type>`** (required): the Conventional Commits type (`feat`, `fix`, `refactor`, `perf`, `docs`, `chore`, `build`, `test`, `ci`, `style`).
- **`[summary-hint]`** (optional): 2–5 lower-case kebab-case words summarising the change. If omitted, use a generic summary derived from the `<type>`.

Generate **2–4 candidates** (best first) following the repo convention `<type>/<kebab-summary>` (see `.claude/CLAUDE.md` → "Branching"):

1. The primary candidate using the provided (or derived) summary.
2. One or more shorter/different-wording alternatives.

Example for `/feature-branch feat "add drag-and-drop reordering"`:

1. `feat/add-drag-and-drop-reordering`
2. `feat/feed-list-drag-reorder`
3. `feat/drag-and-drop`

### Without a `<type>` argument, or with `rename`

Compare from the merge base, covering commits **and** uncommitted work:

```bash
base=$(git merge-base "<base>" HEAD)
git log --oneline "$base"..HEAD                 # commit subjects are naming hints
git diff --stat "$base"                         # working tree vs merge base: staged + unstaged too
git diff "$base"
git ls-files --others --exclude-standard        # untracked files (read them as needed)
```

For a large diff, get the overall picture from `--stat` and read only the main files in full.

If there are no commits, no diff, and no untracked files, output the following and stop:

```text
No changes relative to <base> — nothing to name.
```

Generate **2–4 candidates**, best first, in the repo convention `<type>/<kebab-summary>` (see `.claude/CLAUDE.md` → "Branching"):

- `<type>`: the Conventional Commits type of the change as a whole (`feat`, `fix`, `refactor`, `perf`, `docs`, `chore`, `build`, `test`, `ci`, `style`).
- `<kebab-summary>`: 2–5 lower-case kebab-case words.

Give each candidate a one-line meaning: which aspect of the change the name puts first.

### Collision check

For every candidate, check whether a local branch of that name already exists:

```bash
git rev-parse --verify --quiet refs/heads/<candidate>
```

Drop any candidate that already exists and generate a replacement (e.g. append `-2`, `-3`, ... to
the summary) so every candidate presented in Step 4 is actually available. A candidate equal to the
current branch's name may stay as "the current name already fits".

## Step 4 — Prompt for selection

First, determine the situation:

| Current branch | Action |
| --- | --- |
| On `v*` or detached `HEAD` | Create a new branch with the chosen name and switch to it |
| Feature branch, not pushed | Rename the current branch to the chosen name |
| Feature branch, pushed | List the candidates best first, say the branch is not renamed because it has already been pushed, and stop |

To detect whether the current feature branch has been pushed:

```bash
git for-each-ref --format='%(refname)' "refs/remotes/*/<current branch>"
```

(Do not use `@{u}`: a branch created from `origin/v0` tracks it, which says nothing about whether
this branch was pushed.)

Present the candidates via `AskUserQuestion` with an additional **"Other"** option so the user can
type their own. For creation on `v*`, include a brief explanation of why a feature branch is needed
(per `.claude/CLAUDE.md` "Branching"). For rename, explain that the current branch will be renamed.

## Step 5 — Validate

If the user picks a candidate (or supplies their own via "Other"):

1. Validate the name:

   ```bash
   git check-ref-format --branch "<name>"
   ```

   If this fails, or the name does not match the repo convention `<type>/<kebab-summary>`
   (`<type>` one of the Conventional Commits types listed in Step 3, `<kebab-summary>` lower-case
   kebab-case), explain why and re-prompt via `AskUserQuestion` for a corrected name instead of
   proceeding.

2. Check for a collision:

   ```bash
   git rev-parse --verify --quiet refs/heads/<name>
   ```

   If it already exists (exit code `0`) and is not equal to the current branch's name, tell the user
   and re-prompt via `AskUserQuestion` — do not silently overwrite it or let `git switch -c` / `git
   branch -m` fail. This also covers an "Other" name, which bypasses the Step 3 pre-filtering.

## Step 6 — Apply

1. If the name equals the current branch's name (Step 3's "the current name already fits"), output
   `'<name>' is already the current branch name — nothing to rename.` and stop.

2. Otherwise apply by situation:

   - On `v*` or detached `HEAD` — create and switch (uncommitted changes come along):

     ```bash
     git switch -c "<name>"
     ```

   - On a feature branch — rename:

     ```bash
     git branch -m "<name>"
     ```

3. Output the result in one line (e.g. `Switched to a new branch '<name>'.` / `Renamed 'old' to '<name>'.`).

## Rules

- Never create or rename a branch without the user's explicit selection or a name they provided.
- Never rename a `v*` branch — always create a new one.
- Never rename a feature branch that has already been pushed to the remote.
- Never touch the remote: no push, no remote branch deletion, no fetch.

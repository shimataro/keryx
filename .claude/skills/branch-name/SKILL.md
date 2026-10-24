---
name: branch-name
description: Propose branch names (<type>/<kebab-summary>) from the changes between a base branch and the current working tree, then rename the current branch (or create a new one when on v*). Invoke with /branch-name [base-branch].
---

Propose branch names that describe the changes on the current `HEAD` (plus uncommitted work)
relative to a base branch, then rename the current branch — or, on a version branch, create a new
one — once the user picks a name.

## Step 1 — Check the current branch

```bash
git branch --show-current
```

- **Empty output** → detached `HEAD`. Treat it like a version branch in Step 5/6 (create, never
  rename), but like a non-version branch in Step 2 (the parent branch if one can be detected,
  otherwise the default branch).
- A name matching `v*` (e.g. `v0`, `v1`) → a version branch (the default/integration branch).
- Anything else → a feature branch.

## Step 2 — Resolve the base branch

### With an argument (`$ARGUMENTS`)

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
  (not `rev-parse`, for the same case-insensitivity reason);
- a commit hash — 4 to 64 hexadecimal characters — that `git rev-parse --verify --quiet "<arg>^{commit}"`
  resolves to a hash starting with the argument (compared case-insensitively; `rev-parse` tries ref
  names before object names, so a hex-looking name can still resolve through a ref).

Any other revision expression (`HEAD`, `HEAD~1`, `@{u}`, `origin`, `origin/HEAD`, …) is not a base
even though `rev-parse` resolves it: handle it like an unknown name below.

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
  branch (a stacked branch) from being named after its parent's changes too.

  1. Resolve the default branch:

     ```bash
     git symbolic-ref --short refs/remotes/origin/HEAD   # e.g. origin/v0
     git rev-parse --verify --quiet "<default>^{commit}" # the target must exist (origin/HEAD can go stale)
     ```

     If either fails, tell the user to pass the base branch as an argument and stop.

  2. List parent candidates — branches that `HEAD` contains but the default branch does not:

     ```bash
     git for-each-ref --merged=HEAD --no-merged="<default>" \
       --format='%(refname:short)' refs/heads refs/remotes
     ```

     Exclude the current branch itself, a remote branch of the same name (`<remote>/<current
     branch>` — basing on it would cover only what has not been pushed yet), and `*/HEAD`. Do not
     exclude `@{u}`: a stacked branch created with `--track` has its parent as its upstream.

  3. Measure each candidate's distance and pick the nearest as the base:

     ```bash
     git rev-list --count "<candidate>"..HEAD
     ```

     Treat a local and a remote branch at the same commit (e.g. `releases/v0.27.0` and
     `origin/releases/v0.27.0`) as one candidate, shown by its local name.

     - If several candidates at different commits share the smallest distance, present them and
       the default branch via `AskUserQuestion` and use the one picked.
     - If there are no candidates, use the default branch.

  When a parent branch is used, say so in the base line, naming the default branch and how to
  override it (a stale `origin/<default>` that was never fetched can make an already-merged branch
  look like a parent):

  ```text
  Base: releases/v0.27.0 (detected parent branch; default branch: origin/v0 — pass a base branch as an argument to override)
  ```

- **On `v*`** → `origin/<current branch>`, so the proposal covers what has not been pushed yet:

  ```bash
  git rev-parse --verify --quiet "refs/remotes/origin/<current branch>"
  ```

  If it does not exist, tell the user to pass the base branch as an argument and stop — do not fall
  back to another branch.

Output the resolved base in one line (e.g. `Base: origin/v0`).

## Step 3 — Inspect the changes

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

## Step 4 — Generate candidates

Generate **2–4 candidates**, best first, in the repo convention `<type>/<kebab-summary>` (see
`.claude/CLAUDE.md` → "Branching"):

- `<type>`: the Conventional Commits type of the change as a whole (`feat`, `fix`, `refactor`,
  `perf`, `docs`, `chore`, `build`, `test`, `ci`, `style`).
- `<kebab-summary>`: 2–5 lower-case kebab-case words.

Give each candidate a one-line meaning: which aspect of the change the name puts first.

Check each candidate against existing local branches:

```bash
git rev-parse --verify --quiet "refs/heads/<candidate>"
```

Replace any candidate that already exists with another available one — except a candidate equal to
the current branch's name, which stays as the proposal "the current name already fits" (picking it
does nothing).

## Step 5 — Propose and confirm

On a feature branch, first check whether it has been pushed — a remote branch of the same name
exists:

```bash
git for-each-ref --format='%(refname)' "refs/remotes/*/<current branch>"
```

(Do not use `@{u}`: a branch created from `origin/v0` tracks it, which says nothing about whether
this branch was pushed.)

Then, by situation:

| Situation | Action |
| --- | --- |
| On `v*` or detached `HEAD` | `AskUserQuestion`: create a new branch with the chosen name and switch to it? |
| Feature branch, not pushed | `AskUserQuestion`: rename `<current branch>` to the chosen name? |
| Feature branch, pushed | List the candidates (name + meaning) best first, say the branch is not renamed because it has already been pushed, and stop |

In `AskUserQuestion`, each option's `label` is the branch name and its `description` the meaning;
"Other" lets the user type their own name.

## Step 6 — Apply

1. Validate the name:

   ```bash
   git check-ref-format --branch "<name>"
   ```

   If this fails, or the name does not match `<type>/<kebab-summary>` (a type from Step 4,
   lower-case kebab-case summary), explain why and re-prompt via `AskUserQuestion`.

2. If the name equals the current branch's name (Step 4's "the current name already fits"), output
   `'<name>' is already the current branch name — nothing to rename.` and stop.

   Otherwise, check for a collision (an "Other" name bypasses Step 4's check):

   ```bash
   git rev-parse --verify --quiet "refs/heads/<name>"
   ```

   If it exists, tell the user and re-prompt via `AskUserQuestion`.

3. Apply:

   - On `v*` or detached `HEAD` — create and switch (uncommitted changes come along):

     ```bash
     git switch -c "<name>"
     ```

   - On a feature branch — rename:

     ```bash
     git branch -m "<name>"
     ```

4. Output the result in one line (e.g. `Renamed 'old' to '<name>'.` /
   `Switched to a new branch '<name>'.`).

## Rules

- Never create or rename a branch without the user's selection or a name they provided.
- Never rename a `v*` branch — always create a new one.
- Never touch the remote: no push, no remote branch deletion, no fetch.

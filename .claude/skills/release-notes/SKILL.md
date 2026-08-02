---
name: release-notes
description: Analyze the diff between the latest tag on the current version branch (v*) and HEAD, then generate a Markdown release-notes draft. Suggests version-bump candidates (major / minor / patch / pre-release) based on the analyzed changes, lets the user pick one, and outputs the final release notes in a copy-pasteable code block. Never modifies files, commits, or tags.
---

# Release Notes

Generate release notes from the changes between the latest tag on the current
version branch and `HEAD`. This skill **only reads git history and diffs**; it
never creates or modifies files, commits, or tags.

## Safety rule

**Do not modify any file, commit, or tag.** The purpose of this skill is analysis
and Markdown output only. If any step would require writing to the working tree
or repository, stop and explain why.

## Preconditions

1. Check the current branch:

   ```bash
   git branch --show-current
   ```

   - If the branch name does **not** start with `v` (e.g. `v0`, `v1`), output
     exactly:

     ```text
     This command can only be run on a version branch (v*).
     ```

     and stop.

2. Find the latest tag on the current branch:

   ```bash
   git describe --tags --match 'v*' --abbrev=0
   ```

   - If no tag exists, output exactly:

     ```text
     No tags found on this branch.
     ```

     and stop.
   - Validate the tag against
     `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?$`.
     If it does not match, output
     exactly (substituting the actual tag for `<tag>`):

     ```text
     Latest tag <tag> is not a valid version tag.
     ```

     and stop.

3. Check whether there are any commits since that tag:

   ```bash
   git log <tag>..HEAD --oneline
   ```

   - If the list is empty, output exactly:

     ```text
     No changes since <tag>.
     ```

     and stop.

## Gather information

Collect the following data. Use it to understand what actually changed, not just
to repeat commit messages.

1. **Latest tag** (already obtained): e.g. `v0.1.0`.
2. **Base version**: the tag's numeric `MAJOR.MINOR.PATCH`, stripping the leading `v` and any
   `-prerelease` suffix. E.g. `v0.1.0` → `0.1.0`; `v0.2.0-rc.1` → `0.2.0`. Major/minor/patch
   arithmetic always increments from this numeric base, regardless of whether the latest tag
   itself carried a pre-release suffix.
3. **Commit log** (subjects and bodies):

   ```bash
   git log <tag>..HEAD --pretty=format:'---%H---%n%s%n%b'
   ```

4. **Diff statistics**:

   ```bash
   git diff --stat <tag>..HEAD
   ```

5. **Key diffs for analysis**: read the complete diff for the whole range — every changed path
   listed in step 4 is covered, not just specific extensions or directories, since release-note
   categorization and version-bump analysis need visibility into every changed file, not only
   Kotlin/SQL/docs/CI.

   ```bash
   git diff <tag>..HEAD
   ```

   Never re-embed a changed path into a shell command string to inspect it individually — a
   filename can contain characters (quotes, backticks, `$(...)`) that get reinterpreted as shell
   syntax, which would violate the read-only safety rule above. If the full diff is too large to
   read at once, fall back to the `--stat` summary (step 4) for the less-central files and
   prioritize reading the files most likely to drive release-note content in full.

6. **Remote URL** for PR/compare links (optional):

   ```bash
   git remote get-url origin
   ```

   - Normalize to `https://github.com/owner/repo`: convert an SSH remote
     (`git@github.com:owner/repo.git`) to HTTPS, and strip a trailing `.git` from either an SSH or
     an already-HTTPS remote.
   - If the remote URL cannot be determined, no repo links are available for the rest of this
     skill: omit the `[#N](...)` Markdown link form (keep plain `(#N)` text instead) and omit the
     final **Full Changelog** line entirely.

## Analyze the changes

**Do not rely solely on commit messages.** Read the actual diffs and summarize
what changed from a user and project perspective.

Group the changes into these categories:

- **New Features** — new capabilities visible to users.
- **Improvements** — enhancements to existing behavior, UX, or performance.
- **Bug Fixes** — fixes for incorrect or broken behavior.
- **Build & CI** — packaging, dependencies, workflows, build configuration.
- **Documentation** — user-facing or developer documentation updates.
- **Other Changes** — anything that does not fit above.

For each item:

- Write 1–2 clear sentences describing the change based on the diff.
- Include the PR number as `(#N)` if it appears in the commit subject.
- If a PR link can be built from the remote URL, make it a Markdown link:
  `[#N](https://github.com/owner/repo/pull/N)`.

If a commit message contradicts the actual diff, trust the diff.

## Suggest version candidates

Based on the analyzed changes, propose version-bump candidates relative to the
latest tag. Use [Semantic Versioning](https://semver.org/) reasoning:

- **Major** — breaking changes or a deliberate move out of `0.x`.
- **Minor** — new features or notable improvements.
- **Patch** — bug fixes only.
- **Pre-release** — `alpha`, `beta`, `rc`, etc., for testing before a stable
  release.

Calculate the concrete version string each level would produce from the Base
Version (see above — the latest tag's own pre-release suffix, if any, is not
part of this arithmetic). Major/minor/patch are independent version-number
arithmetic (increment the relevant segment, reset lower segments). Pre-release is not
a fourth independent path — it is always a suffix on top of whichever base
bump (major/minor/patch) it precedes. For example, from `v0.1.0`, with
pre-release shown paired with the minor bump:

| Level | Candidate |
| ------- | ----------- |
| major | `v1.0.0` |
| minor | `v0.2.0` |
| patch | `v0.1.1` |
| pre-release | `v1.0.0-alpha.1`, `v0.2.0-alpha.1`, `v0.1.1-alpha.1` (major/minor/patch + suffix, here) |

### AskUserQuestion options

From the four candidate levels above, keep only the ones that are actually
plausible for this diff, and drop any that are clearly wrong for it:

- Drop **patch** if the changes include new features or notable improvements
  (a patch bump would understate the change).
- Drop **major** if the changes are only minor/small (bug fixes, small
  improvements, docs, build/CI) with no breaking changes and no deliberate
  move out of `0.x`.
- Include **pre-release** only when a testing/staging build genuinely makes
  sense before the stable release; it is not an always-present option. When
  included, present it as its own option labeled with the specific base it
  pairs with — e.g. "Pre-release (minor)" — rather than a bare "Pre-release",
  using whichever surviving major/minor/patch candidate is the most
  recommended base for this diff.

Order the surviving candidates from most to least recommended based on the
actual analyzed changes, and mark the top one "(Recommended)" — do not assume
minor is the recommendation by default. Because `AskUserQuestion` supports at
most 4 options, present at most the top 4 surviving candidates in that order.

## Resolve the chosen version

- If the user selected the **Pre-release (\<base\>)** option, ask for the suffix:
  - Offer `alpha.1`, `beta.1`, `rc.1`, or a custom input.
  - Append it to the base already captured in the option label. For example,
    selecting "Pre-release (minor)" then `alpha.1` → `v0.2.0-alpha.1`.
- If the user selected **Other**, accept their input with or without a leading
  `v`.
- Normalize the final version to the tag form `vMAJOR.MINOR.PATCH[-prerelease]`.
- Validate it against the same anchored pattern used in the tag-selection step:
  `^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?$`.
  If invalid, ask again.

## Generate final release notes

Produce the final Markdown release notes using the normalized version.

Structure:

```markdown
## What's Changed in <new-version>

### New Features

- Description of feature. (#N)

### Improvements

- Description of improvement. (#N)

### Bug Fixes

- Description of bug fix. (#N)

### Build & CI

- Description of build/CI change. (#N)

### Documentation

- Description of doc change. (#N)

### Other Changes

- Description of other change. (#N)

**Full Changelog**: <normalized-repo-url>/compare/<previous-tag>...<new-version>
```

Rules:

- Omit empty sections.
- Keep bullet points concise and user-oriented.
- Convert `#N` references to Markdown links when the remote URL is known.
- Omit the **Full Changelog** line entirely if the remote URL could not be determined (see
  "Remote URL" above); otherwise substitute the normalized repo URL for `<normalized-repo-url>`.
- Output the final Markdown inside a fenced code block so it is easy to copy.
- Do not add any extra commentary outside the code block except a brief
  introduction such as "Here are the release notes for \<new-version\>."

### Open GitHub release page

After outputting the release-notes code block, also output a URL that opens
GitHub's "New release" page with the same information pre-filled.

1. Determine whether this release is a pre-release:
   - The major version is `0` (for example, `v0.x.x`), **or**
   - the version string contains a `-` pre-release suffix (for example,
     `v1.2.3-alpha.1`).
2. Build the URL with the fixed base
   `https://github.com/shimataro/keryx/releases/new` and these query parameters:
   - `tag`: the normalized new version tag (for example, `v1.2.3-alpha.1`).
   - `target`: the current branch name obtained in the preconditions step.
   - `title`: `<version> released` (for example, `v1.2.3-alpha.1 released`).
   - `body`: the Markdown release notes generated above, URL-encoded.
   - `prerelease`: append `prerelease=1` only when this release is a
     pre-release; otherwise omit this query parameter entirely.
3. URL-escape all dynamic values appropriately (spaces as `%20`, line breaks as
   `%0A`, `#` as `%23`, etc.).

Output the final URL in its own fenced code block, preceded by a short sentence
such as:

```text
Open the following URL to create the release on GitHub:
```

Example for a pre-release:

```text
https://github.com/shimataro/keryx/releases/new?tag=v1.2.3-alpha.1&target=v1&title=v1.2.3-alpha.1%20released&body=%3Crelease%20notes%20of%20v1.2.3-alpha.1%3E&prerelease=1
```

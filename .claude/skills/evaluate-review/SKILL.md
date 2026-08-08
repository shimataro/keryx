---
name: evaluate-review
description: Evaluate a GitHub PR review comment or review summary for technical validity. For a discussion (line) comment or a PR review with no associated comments, create an implementation plan for user approval; for a PR review with associated discussion comments, automatically fix and commit each one independently. Accepts multiple URLs, processed one at a time in the order given. Invoke with /evaluate-review <url> [<url> ...].
---

Evaluate a GitHub pull-request review comment or review summary for technical validity, then act
on it. A `discussion_r` comment, or a `pullrequestreview` with no associated line comments,
produces an implementation plan for the user to approve and commit themselves. A
`pullrequestreview` that has associated discussion comments is instead fixed and committed
automatically, one commit per comment — see Step 8. When multiple URLs are given, each one runs
through this whole flow independently, strictly in the order the URLs were given — see Step 1.

## Usage

```text
/evaluate-review <github-pr-review-url> [<github-pr-review-url> ...]
```

Examples:

```text
/evaluate-review https://github.com/shimataro/keryx/pull/3#discussion_r3618156689
/evaluate-review https://github.com/shimataro/keryx/pull/3#pullrequestreview-2183723456
/evaluate-review https://github.com/shimataro/keryx/pull/3#discussion_r3618156689 https://github.com/shimataro/keryx/pull/3#pullrequestreview-2183723456
```

## Steps

### Step 1 — Validate presence of argument(s)

Check whether one or more URL arguments (space-separated) were passed to the skill.

- **If none**: prompt the user to provide a GitHub PR review URL. Ask:

  ```text
  Please provide a GitHub PR review URL.
  Usage: /evaluate-review <github-pr-review-url>
  ```

  Wait for the user to respond with a URL, then use that URL and proceed to Step 2.
- **If one or more**: treat each URL as a separate item and run it through Steps 2–8 as its own
  independent pass — its own branch check, fetch, evaluation, and Step 8 action (including
  waiting out `EnterPlanMode`/`ExitPlanMode` approval where Case A applies). Process the items
  **one at a time, strictly in the order the URLs were given** — never reorder, batch, or run
  two items concurrently. Fully finish one URL's Step 8 action before starting the next URL at
  Step 2. If **only one** URL was given, proceed exactly as before (no cross-URL summary at the
  end of Step 8).

### Step 2 — Validate URL format

Parse the URL. It must match one of the following patterns:

1. **Review comment** (line-level):
   `https://github.com/<owner>/<repo>/pull/<number>#discussion_r<comment_id>`
2. **PR review** (overall summary):
   `https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<review_id>`

- Extract `owner`, `repo`, `pull_number`, and the trailing numeric ID.
- Determine `comment_type`: `review_comment` for `#discussion_r`, `pr_review` for `#pullrequestreview`.
- If the URL does **not** match either pattern, output exactly:

  ```text
  The provided URL does not appear to be a GitHub PR review comment or review link.
  ```

  Mark this URL as `Invalid` for the closing summary and stop only *this* URL's processing. If
  more URLs remain (Step 1), continue to the next one at Step 2; only halt the whole run if this
  was the sole/last URL.

### Step 3 — Verify branch alignment

1. Fetch the PR's head branch name via GitHub API:

   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.ref'
   ```

   - If this command fails (e.g., network error, bad credentials), output a warning and proceed to Step 4.
2. Get the current local branch:

   ```bash
   git branch --show-current
   ```

   - If the current directory is not a git repository, output a warning and proceed to Step 4.
3. Compare the two branch names.
   - **If they match**, proceed to Step 4.
   - **If they differ**:
     1. Output a warning message indicating the mismatch (e.g., `PR branch: feat/foo, Current branch: feat/bar`).
     2. Check for uncommitted changes (`git status --porcelain`). If dirty, note that changes will be stashed before switching and restored afterward.
     3. Prompt the user via `AskUserQuestion` with options:
        - **Switch to PR branch** (Recommended)
        - **Stay on current branch**
        - **Cancel evaluation**
     4. If the user chooses **Switch**:
        - If the branch does not exist locally, run `git fetch origin <pr_branch>` followed by `git switch <pr_branch>`.
        - If the working tree is dirty, run `git stash push -m "evaluate-review auto-stash"`, then `git switch <pr_branch>`, then `git stash pop`.
        - After switching, proceed to Step 4.
     5. If the user chooses **Stay**, proceed to Step 4 with the warning still displayed.
     6. If the user chooses **Cancel**, stop immediately.

### Step 4 — Fetch the comment or review

Branch the next steps based on `comment_type`.

#### If `review_comment` (line-level comment)

Use the GitHub CLI to retrieve the comment:

```bash
gh api repos/{owner}/{repo}/pulls/comments/{comment_id}
```

- If the request fails (e.g., 404), output the error message and stop.
- From the JSON response, extract:
  - `body` — the review text
  - `path` — file under review
  - `line` / `original_line` — ending line of the comment
  - `start_line` / `original_start_line` — starting line of the comment (for multi-line comments)
  - `side` / `start_side` — side of the diff (`LEFT` or `RIGHT`)
  - `diff_hunk` — diff context provided by GitHub
  - `commit_id` — commit SHA at which the comment was left
- **Verify the comment belongs to the requested PR.** Extract `pull_request_url` from the JSON response and confirm it matches `https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}` (using the values parsed in Step 2).
  If it does **not** match, output:

  ```text
  The fetched comment does not belong to the requested PR.
  Expected: <expected_url>
  Got:      <pull_request_url>
  ```

  and stop immediately.

#### If `pr_review` (overall review summary)

Use the GitHub CLI to retrieve the review:

```bash
gh api repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}
```

- If the request fails (e.g., 404), output the error message and stop.
- From the JSON response, extract:
  - `body` — the review text
  - `state` — `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`, etc.
  - `commit_id` — commit SHA at which the review was submitted
  - `user` — reviewer login
  - `submitted_at` — ISO-8601 timestamp

1. Fetch the review's associated line comments:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments
   ```

2. If the response is not empty, retain them as `associated_comments`. Preserve each comment's `id`, `body`, `path`, `line`/`original_line`, `start_line`/`original_start_line`, `side`/`start_side`, `diff_hunk`, and `commit_id`.
3. If `body` is null or empty AND there are no associated comments, output that the review has no actionable feedback and stop.

- **Verify the review belongs to the requested PR.** Extract `pull_request_url` and confirm it matches `https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}`.
  If it does **not** match, output:

  ```text
  The fetched review does not belong to the requested PR.
  Expected: <expected_url>
  Got:      <pull_request_url>
  ```

  and stop immediately.

### Step 5 — Gather thread context

A review comment or review is often part of a longer conversation. Reconstruct the surrounding discussion so the evaluation accounts for prior clarification or rebuttal.

#### If `review_comment`

1. Fetch **all** review comments on the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/comments
   ```

2. Build the reply graph using the `in_reply_to_id` field. Each comment either starts a thread (`in_reply_to_id` is absent) or replies to another.
3. Identify which thread contains the target `comment_id`. Using the reply graph from step 2, follow the `in_reply_to_id` chain backward from the target comment to locate the root of the thread. Then collect **all** comments in that thread—both ancestors and descendants—and present the complete conversation in chronological order.
4. Include this conversation history in your analysis. Pay attention to:
   - Whether earlier rounds already accepted, rejected, or refined the point.
   - Whether the target comment is a follow-up that narrows or shifts the request.
   - Any author push-back or reviewer concession that changes the expected outcome.

#### If `pr_review`

PR review summaries do not use `in_reply_to_id`, so build context from chronology instead:

1. Fetch **all** reviews on the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews
   ```

2. Sort by `submitted_at` to locate chronologically adjacent reviews from the same reviewer (or from any reviewer) that provide context.
3. Include these adjacent reviews in your analysis. Note especially:
   - Whether a previous review already covered the same points.
   - Whether the author has already responded or pushed back.
   - Whether the target review is a follow-up that narrows or shifts earlier requests.

- **If this review has `associated_comments` from Step 4**:
  1. Fetch **all** review comments on the PR once and build the reply graph using the `in_reply_to_id` field.
  2. For each associated comment, resolve its thread against the pre-built graph: locate the thread containing the comment's `id`, then collect the full thread (ancestors and descendants) in chronological order.
  3. Include all gathered thread conversations in the evaluation context. The evaluation must consider the specific bodies, file paths, and line ranges of the associated comments, not only the overall review chronology.

### Step 6 — Gather code context

#### If `review_comment`

1. Use the `commit_id` extracted in Step 4 to load the code exactly as it existed when the comment was left.
2. If the reviewed file exists in the local working tree **and matches `commit_id`**, read it for full context.
3. Otherwise, fetch the file content at `commit_id`:

   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={commit_id}
   ```

   **URL-encode the `{path}` segment** before interpolation (e.g. space → `%20`, `#` → `%23`, `?` → `%3F`) while preserving `/` between directory components. Decode the `content` field from base64 if necessary.
4. Fetch the diff for that commit to understand the change being reviewed. **Never interpolate
   the review-provided `{path}` into a jq filter string or an unquoted shell token** — a path
   containing a quote or backtick could otherwise break the jq expression or the constructed
   shell command. Bind it through a quoted shell variable and jq's `--arg` instead:

   ```bash
   path='{path}'
   gh api repos/{owner}/{repo}/commits/{commit_id} | jq --arg path "$path" '.files[] | select(.filename == $path) | .patch'
   ```

   (The `diff_hunk` from Step 4 already provides the immediate surrounding context.)
5. **Separately**, fetch the current PR head SHA and compare it to `commit_id`:

   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
   ```

   If the head has advanced, briefly check whether the issue noted in the review comment has already been fixed in a later commit. If so, note this in the evaluation reasoning.

#### If `pr_review`

There is no single `path` or `line`. Gather broader context from the PR:

1. Fetch all files changed in the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/files --jq '.[] | {filename, status, patch}'
   ```

2. If the review body explicitly names specific files or functions, also fetch the content of those files at `commit_id`:

   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={commit_id}
   ```

   (URL-encode `{path}` as above.)
3. Fetch the current PR head SHA and compare it to `commit_id`:

   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
   ```

   If the head has advanced, briefly check whether the issues noted in the review have already been addressed in a later commit. If so, note this in the evaluation reasoning.

- **If this review has `associated_comments` from Step 4**:
  1. For each associated comment, apply the `review_comment` code-context gathering (as described in the `review_comment` section above): load the file at **that comment's own `commit_id`**, fetch the commit diff for that file at that same SHA, and check whether the current PR head has already fixed the issue noted in that comment. If multiple associated comments reference the same file path but different commits, process each comment separately.
  2. The evaluation must specifically assess the code at the line ranges indicated by the associated comments, using the same accuracy, relevance, and constructiveness criteria applied to direct `review_comment` evaluations.

### Step 7 — Evaluate validity

Analyze the review comment or review summary against the code and project conventions. Consider:

1. **Accuracy** — Is the factual claim correct? (e.g., a reported bug must actually exist)
2. **Relevance** — Does the comment apply to the code at the stated location?
   - For `review_comment`: the specific line(s).
   - For `pr_review`: the overall PR diff (or the specific files/areas explicitly named in the review body).
3. **Constructiveness** — Does it clearly identify a problem or suggest a valid improvement?
   - For `pr_review`: if the body is vague or only asks questions without requesting changes, the evaluation may conclude that no code change is required.
4. **Project conventions** — Does it align with the project's documented rules (check `.claude/CLAUDE.md` and `docs/*.md`)?
5. **Language / library / framework / external-service correctness** — When the comment concerns usage of a language runtime, library, framework, or external service (e.g., REST API, cloud SDK), verify the claim against the **official documentation of the exact version the project uses**. Prefer official docs (API reference, language spec, release notes, vendor API docs) over blog posts or Stack Overflow. If the docs do not cover the point, inspect the source code or make a controlled test request directly. Do not rely on memory or general assumptions.
6. **Regression / security / data-integrity risk** — If the review proposes or implies a code change, consider whether adopting it could break existing behavior (regression), weaken security boundaries, or corrupt data. Focus especially on:
   - **Security**: authorization checks, input validation, secrets handling, injection vectors, and race conditions (e.g., TOCTOU).
   - **Data integrity**: transaction boundaries, unique / foreign-key constraints, merge semantics (`DatabaseMerger`, `MergeSql`, `SyncRepository`), FTS index consistency (`articles_fts` lifetime rules), and logical-deletion timestamps.
   - Verify the proposal does not violate documented critical constraints (e.g., `.claude/CLAUDE.md` "Critical constraints", `docs/sync-architecture.md`).

For `pr_review` with `associated_comments`, apply these same criteria **independently to
each associated comment**, producing a separate verdict per comment — not only a single
verdict for the review as a whole. See Step 8 for how these per-comment verdicts are used.

For every item evaluated — the single comment/review for Case A, or each associated
comment independently for Case B — record its **Validity** ([Valid / Invalid / Partially
Valid]), **Confidence** ([High / Medium / Low]), and **Reasoning**. Do not print this
immediately: Step 8 defines exactly when and how it is surfaced — first as a row in the
run's summary table ("Common rule — summary table"), then as this per-item detail block,
in this exact format:

```markdown
## Evaluation

**Validity**: [Valid / Invalid / Partially Valid]
**Confidence**: [High / Medium / Low]

### Reasoning
[Your analysis. Cite specific lines or docs where applicable.]
```

### Step 8 — Take action

Branch on `comment_type` and, for `pr_review`, on whether `associated_comments` (gathered in
Step 4) is non-empty, into one of two cases below (Case A / Case B).

**Common rule — summary table (always, printed before any per-item detail)**: Before
printing any item's Step 7 detail block or taking any action on it, print one Markdown
table summarizing every item this run is evaluating:

| Comment URL | Finding summary | Validity | Confidence | Next action |
| --- | --- | --- | --- | --- |

- **Comment URL** — the target URL for Case A (the `discussion_r...`/`pullrequestreview-...`
  URL given to the skill); for Case B, each associated comment's own `html_url` (fall back
  to its `path`:`line`/`original_line` if no URL is available).
- **Finding summary** — a concise one-line paraphrase (not a verbatim quote) of what the
  comment/review points out.
- **Validity** / **Confidence** — that item's Step 7 Validity / Confidence.
- **Next action** — the planned outcome: **Implementation plan** (Case A, Valid/Partially
  Valid + code change needed), **Fix & commit** (Case B, Valid/Partially Valid + code
  change needed), or **No action needed** (Invalid, or valid but no code change required).
- **Case A**: exactly one row, from the Step 7 evaluation already performed for this
  comment/review.
- **Case B**: one row per entry in `associated_comments`, in that order, from the verdicts
  Step 7 already produced for every associated comment (Step 7 evaluates all of them before
  Step 8 begins — see its "apply independently to each associated comment" rule). No
  comment is acted on until every row is printed.

This table is a preview of the plan, not the final outcome — Case B's closing summary
still reports what actually happened to each comment (`Committed <sha>` / `Skipped` /
`Verification failed`), which can differ from Next action if a fix later fails verification.

**Common rule — reviewer-facing reply for "no change needed" outcomes**: whenever a comment or
review (or, in Case B, an individual associated comment) is judged **Invalid**, or **Valid**/
**Partially Valid** but requiring no code change, in addition to whatever else this step
specifies, output a suggested reply comment for the reviewer as a fenced ```markdown``` code
block so the user can copy it directly into GitHub — this skill never posts it automatically.
Compose it from the Step 7 **Reasoning**, rewritten as plain, polite prose suitable for a public
PR reply (do not surface internal labels like "Confidence: Low" verbatim). Cover: (1) a brief
nod to what the comment/review pointed out, (2) why no change is being made, and (3) — when the
verdict is **Partially Valid** — which part is being left as-is versus already covered elsewhere.
Match the language of the reviewer's own text: identify the natural language the original `body`
is written in, and write the reply in that same language — this is not limited to specific languages; a comment in English, Japanese, French, or any other language gets a reply in that language.
English and Japanese are simply the two most common cases in this repo (examples of each below).
If the body mixes languages or its language cannot be confidently identified (e.g. it's too short
or is mostly code with little natural-language text), fall back to English.

For example, in English:

```markdown
Thanks for the review. After checking, I've concluded no change is needed here because 〈reason〉.
```

or in Japanese:

```markdown
ご指摘ありがとうございます。確認した結果、〈理由〉のため、現状のコードに変更は不要と判断しました。
```

#### Case A — `review_comment`, or `pr_review` with no `associated_comments`

This case is unchanged from before: it always ends in a plan awaiting user approval, and
the user authors/performs the eventual commit themselves (per the repo's normal "don't commit
unless asked" rule).

This case always begins by printing the one-row summary table and this item's Step 7
detail block, per the "Common rule — summary table" above.

If the evaluation is **Valid** or **Partially Valid** **and** the comment/review calls for code changes, proceed:

1. Call `EnterPlanMode`.
2. Explore the codebase to understand the surrounding architecture.
3. Write the plan to `~/.claude/plans/evaluate-review-{id}.md` with:
   - **Context** — what the review pointed out and why it matters
   - **Files to modify** — exact file paths
   - **Changes** — concrete, line-level actions
   - **Verification** — how to test (unit tests, manual checks, build)
   - Note: for `pr_review`, the plan may span multiple files because the feedback is not tied to a single line.
4. **Refine the plan**: invoke the `refine-plan` skill, passing the plan file path
   (`~/.claude/plans/evaluate-review-{id}.md`) as its argument so it targets exactly the
   file just written (`refine-plan` resolves `$ARGUMENTS` as a file path before falling back to
   the active Plan Mode file, removing any ambiguity about which document is refined). It runs its
   fixed checklist (open questions, data integrity, UI/UX, simplicity, codebase match, scope, risk,
   verification, sequencing), auto-fixes what it can, and trims the plan to final agreed content.
   If `refine-plan` surfaces a genuine decision it asks the user via `AskUserQuestion`; resolve
   those before proceeding.
5. Call `ExitPlanMode` to submit the refined plan for user approval.

If the review is **Invalid** or no code change is required, state clearly:

```text
No implementation plan is needed.
```

Then output the reviewer-facing reply per the Common rule above.

#### Case B — `pr_review` with one or more `associated_comments` (automatic fix & commit)

**Auto-commit only ever happens in this case.** `review_comment` URLs never reach it, and
always follow Case A instead — the user still approves a plan and performs the commit
themselves there.

Before starting, if the current branch is a version branch (`v*`), run
`/feature-branch fix` to create and switch to a feature branch before
making any commits. Step 3's branch-alignment check already makes this
unlikely in practice (the PR's head branch is normally already a feature branch),
but guard for it anyway.

Check `git status --porcelain`. If the working tree is dirty with changes unrelated to this
run, warn and skip this URL entirely (commit nothing, note "Working tree not clean" in the
closing summary) rather than making any Case B edits — a failed-verification revert (step 3
below) must never be able to destroy pre-existing uncommitted work in the same file. For a
multi-URL run this only skips the current URL; continue to the next one at Step 2. A clean
result here becomes this run's baseline for the re-check below.

Print the summary table (per the "Common rule — summary table" above) covering every
comment in `associated_comments`.

**One-time run confirmation**: ask the user via `AskUserQuestion` to confirm the whole
Case B run once, referencing the table just printed — e.g. "Found N comment(s): X
Valid/Partially Valid (will fix & commit), Y Invalid or no change needed (skipped) —
proceed with the plan above?" (Proceed / Cancel this URL). This satisfies the repo's
"don't commit unless asked" convention while preserving Case B's per-comment efficiency:
once confirmed, every comment below still proceeds straight to fix → verify → commit with
no further per-comment approval. If the user cancels, skip this URL entirely (commit
nothing, note "Cancelled by user" in the closing summary) and, for a multi-URL run,
continue to the next URL at Step 2. This one-time confirmation authorizes the whole run,
but does not itself substitute for reviewing individual comments — each comment's own
detail block is still surfaced as it is reached (see step 1 below), just without a further
approval gate.

**Re-check immediately before the first edit**: because the one-time run confirmation above
blocks on the user for an unbounded time, re-run `git status --porcelain` right before the
first edit (i.e., right before step 3 fires for the first Valid/Partially Valid comment
below) and confirm it still matches the baseline (still clean). If it no longer does, treat
it exactly like the initial dirty-tree case: skip this URL entirely (commit nothing, note
"Working tree not clean" in the closing summary) rather than starting any Case B edits, and
continue to the next URL in a multi-URL run.

For each comment in `associated_comments`, in order:

1. This comment's Step 7 evaluation was already produced while Step 7 evaluated every
   associated comment (that already fed one row of the summary table printed above) — do
   not re-run it. Output its detail block now — this comment's file:line or comment URL,
   followed by the Step 7 **Validity** / **Confidence** / **Reasoning** — to the user,
   **before** taking any action on it. This is informational only, not a per-comment
   approval gate: the one-time run confirmation above already authorizes proceeding, so
   continue immediately to step 2 or 3 below without waiting for a reply.
2. If **Invalid**, or valid but no code change is required: skip this comment — implement
   nothing, commit nothing. Output the reviewer-facing reply for this comment per the Common
   rule above. Note it (with the reason) for the closing summary.
3. If **Valid** or **Partially Valid**: explore the codebase as needed to understand the
   surrounding architecture (no `EnterPlanMode`/`ExitPlanMode`, and no further interactive
   approval per comment — the one-time run confirmation above already covers the whole run),
   then implement the fix for this comment only, following the
   repo's standing constraints (`.claude/CLAUDE.md`, `docs/testing.md` — including
   adding/updating tests where constraint #7 applies). Then run `./gradlew build` (or the
   narrowest relevant test target, e.g. `:composeApp:desktopTest --tests "..."`, when that is
   clearly sufficient to cover the change) to verify before committing.
   - If verification fails: confirm via `git status`/`git diff` that only this comment's
     intended changes are present before reverting, so a revert cannot destroy pre-existing
     work in the same file; then do not commit. Note the failure (with the reason) for the
     closing summary, and move on to the next comment.
4. On success, review `git status --porcelain` / `git diff` and stage **only the files, and
   only the hunks, touched by this fix** — verify the staged diff contains nothing beyond this
   comment's change before committing, so unrelated pre-existing edits or residue from a prior
   comment's failed attempt never ride along. Create **exactly one commit** with a
   **single-line** commit message only (no body, no trailer), in the repo's Conventional
   Commits style, e.g. `fix(sync): correct rev comparison for Google Drive`. Never batch
   multiple comments into one commit, and never amend a commit made earlier in this same run.
5. Continue to the next comment regardless of the previous comment's outcome.

After every associated comment has been processed, if the review's own `body` text raises
something not already covered by any associated comment, evaluate and handle that leftover
portion via Case A instead (Plan Mode — not auto-committed).

Finally, output a short summary listing, for each associated comment (file:line or comment
URL), its verdict and outcome: `Committed <sha>` / `Skipped: <reason>` / `Verification
failed: <reason>`.

#### Multiple top-level URLs

If Step 1 received more than one URL, after the **last** one finishes its Step 8 action (Case A
or Case B, whichever applied to it), output one final cross-URL summary — in the same order the
URLs were given — listing each URL and its overall outcome (e.g. `Plan submitted for approval`,
`Committed <sha>` / `N commits`, `No implementation plan needed`, `Invalid`). This is in addition
to, not a replacement for, each URL's own Step 7 evaluation and Step 8 output.

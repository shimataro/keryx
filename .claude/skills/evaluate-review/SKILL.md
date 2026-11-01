---
name: evaluate-review
description: Evaluate a GitHub PR review comment or review summary for technical validity. Each finding (a discussion comment, or a review's line comments and body) is evaluated on its own; the ones needing a change go into one implementation plan for user approval, and once approved each is fixed, verified and committed independently. Accepts multiple URLs, processed one at a time in the order given. Invoke with /evaluate-review <url> [<url> ...].
---

# Evaluate Review

> This skill involves **high-accuracy judgments** — deciding whether a reviewer's claim is
> actually correct against this codebase, and committing fixes for the ones that are.
> **Run it in an opus session.** Skills inherit the caller session's model and cannot pin
> one in frontmatter, so verify the session model is opus before executing.

Evaluate a GitHub pull-request review comment or review summary for technical validity, then act
on it: each URL is broken into **items** (Step 4), every item is evaluated on its own, and the
ones needing a change are planned, approved once, then fixed, verified and committed one commit
per item (Step 8).

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

## Outcomes

Every place that reports a result uses only these labels.

| Scope | Label | When |
| --- | --- | --- |
| Item | `Committed <sha>` | Fixed, verified and committed (8.6) |
| Item | `No action needed` | Invalid, valid but needing no code change, or a review body covered by the line comments (Step 7) |
| Item | `Verification failed: <reason>` | Fix reverted after verification failed (8.6) |
| Item / URL | `Plan rejected` | The user rejected the plan (8.5) |
| Item / URL | `Working tree not clean` | Unrelated uncommitted changes at 8.4 or 8.6 |
| URL | `Invalid URL` | Step 2 |
| URL | `Fetch failed: <reason>` | A Step 4 request failed |
| URL | `Not in this PR` | The fetched comment/review belongs to another PR (Step 4) |
| URL | `No actionable feedback` | A review with no body and no line comments (Step 4) |
| URL | `No implementation plan needed` | No item needs a fix (8.3) |
| URL | `N committed, M failed` | Summary of a URL whose plan ran (8.6) |
| Run | `Cancelled` | The user cancelled at Step 3 |

**End this URL** (used throughout): print the reason, record the URL's outcome from the table,
and continue with the next URL at Step 2. After the last URL, print the cross-URL summary (see
the end of Step 8) if more than one URL was given.

## Steps

### Step 1 — Validate presence of argument(s)

Check whether one or more URL arguments (space-separated) were passed to the skill.

- **If none**: ask the user for one, then proceed to Step 2 with it:

  ```text
  Please provide a GitHub PR review URL.
  Usage: /evaluate-review <github-pr-review-url>
  ```

- **If one or more**: run each URL through Steps 2–8 as its own independent pass — its own
  branch check, fetch, evaluation, plan approval, fixes and commits. Process them **one at a
  time, strictly in the order given** — never reorder, batch, or run two URLs concurrently —
  and fully finish one URL's Step 8 before starting the next at Step 2.

### Step 2 — Validate URL format

Parse the URL. It must match one of the following patterns:

1. **Review comment** (line-level):
   `https://github.com/<owner>/<repo>/pull/<number>#discussion_r<comment_id>`
2. **PR review** (overall summary):
   `https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<review_id>`

- Extract `owner`, `repo`, `pull_number`, and the trailing numeric ID.
- Determine `comment_type`: `review_comment` for `#discussion_r`, `pr_review` for `#pullrequestreview`.
- If the URL does **not** match either pattern, output exactly the line below, then end this URL
  (`Invalid URL`):

  ```text
  The provided URL does not appear to be a GitHub PR review comment or review link.
  ```

### Step 3 — Verify branch alignment

The PR's head branch name is PR-controlled, like a review `path` (Step 6): bind it from the API
into a shell variable and only ever use the variable, never the retyped name.

1. Fetch the PR's head branch and repository:

   ```bash
   pr_json="$(gh api repos/{owner}/{repo}/pulls/{pull_number})"
   pr_branch="$(jq -r '.head.ref' <<<"$pr_json")"
   pr_head_repo="$(jq -r '.head.repo.full_name' <<<"$pr_json")"
   ```

   If this fails (e.g. network error, bad credentials), output a warning and proceed to Step 4.
2. Get the current local branch with `git branch --show-current`. If the directory is not a git
   repository, output a warning and proceed to Step 4.
3. If the branches match, proceed to Step 4. Otherwise output the mismatch
   (`PR branch: <pr_branch>, Current branch: <current>`), then:
   - **Fork PR** (`$pr_head_repo` is not `{owner}/{repo}`): `origin` does not carry that branch,
     so do not offer to switch; warn that the evaluation runs against the current branch and
     proceed to Step 4.
   - **Otherwise** ask via `AskUserQuestion`. If `git status --porcelain` is not empty, say in the
     question that those uncommitted changes travel with a switch and will make 8.4 skip this URL
     unless they are this run's own. Options:
     - **Switch to PR branch** (Recommended) — `git switch "$pr_branch"`, after
       `git fetch origin "$pr_branch"` if it does not exist locally. `git switch` carries
       uncommitted changes along (no stash); if it refuses because of a conflict, report that
       and proceed on the current branch.
     - **Stay on current branch** — proceed to Step 4 with the warning still displayed.
     - **Cancel evaluation** — stop the whole run (`Cancelled`); remaining URLs are not processed.

### Step 4 — Fetch the comment or review

Any failed request below: output the error and end this URL (`Fetch failed: <reason>`).

**Line-comment fields** — kept for every line comment fetched here: `id`, `html_url`, `body`,
`path`, `line` / `original_line` (ending line), `start_line` / `original_start_line` (starting
line of a multi-line comment), `side` / `start_side` (`LEFT` or `RIGHT`), `diff_hunk`, and
`commit_id` (the commit the comment was left on).

**PR membership check** — right after fetching the comment or review, and before anything else,
confirm its `pull_request_url` equals
`https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}`. If not, output the block below
(`<kind>` is `comment` or `review`) and end this URL (`Not in this PR`):

```text
The fetched <kind> does not belong to the requested PR.
Expected: <expected_url>
Got:      <pull_request_url>
```

#### If `review_comment`

```bash
gh api repos/{owner}/{repo}/pulls/comments/{comment_id}
```

Run the PR membership check, then keep the line-comment fields.

#### If `pr_review`

```bash
gh api repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}
```

1. Run the PR membership check, then keep `body`, `state`, `commit_id`, `user` and `submitted_at`.
2. Fetch the review's line comments and keep each one's line-comment fields as
   `associated_comments`:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments
   ```

3. If `body` is empty and there are no associated comments, end this URL
   (`No actionable feedback`).

#### Normalize into items

Everything after this step works on a list of **items**, so a single comment and a review with
many comments run through the same flow — they differ only in how many items there are.

- `review_comment` → one **line-comment item** (the fetched comment).
- `pr_review` → one **line-comment item** per entry in `associated_comments`, in API order, then
  one **review-body item** at the end if the review's `body` is not empty.

Steps 5–7 branch on the item's kind (line comment / review body), never on `comment_type`.

### Step 5 — Gather thread context

A review comment or review is often part of a longer conversation. Reconstruct it so the
evaluation accounts for prior clarification or rebuttal — in particular whether earlier rounds
already accepted, rejected or refined the point, whether the item is a follow-up that narrows or
shifts the request, and any author push-back or reviewer concession.

#### Line-comment items

1. Fetch **all** review comments on the PR — once per URL, shared by every line-comment item:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/comments
   ```

2. Build the reply graph from `in_reply_to_id` (absent on a thread's first comment).
3. For each item, follow the chain back to the thread's root, collect **all** comments in that
   thread (ancestors and descendants), and include them in chronological order in the item's
   analysis.

#### Review-body item

Review summaries have no `in_reply_to_id`, so build context from chronology instead:

1. Fetch **all** reviews on the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews
   ```

2. Sort by `submitted_at` and include the chronologically adjacent reviews (from the same reviewer
   or any other) in the item's analysis.

### Step 6 — Gather code context

**Never write a review-provided path into a command** — not as a bare token, not inside quotes,
not in a jq filter. A path is PR-controlled and can contain `'`, `$(`, a backtick or `;`, which
would end a quoted string or the jq expression and run as shell syntax. Bind it from the API
response into the shell variable `review_path` and use only that variable afterwards. (Not
`path` — in zsh, `path` is tied to `$PATH`, so assigning it breaks every later command.)

**Reading a file at a commit** — given `$review_path` and a `commit_id`:

```bash
if git cat-file -e "{commit_id}^{commit}" 2>/dev/null; then
  git show "{commit_id}:$review_path"
else
  enc="$(jq -rn --arg p "$review_path" '$p | split("/") | map(@uri) | join("/")')"
  gh api "repos/{owner}/{repo}/contents/$enc?ref={commit_id}" --jq '.content' | base64 --decode
fi
```

#### Line-comment items

For each item, using **that item's own** `commit_id`, path and line range (items on the same file
but different commits are handled separately):

1. Bind its path — `{comment_id}` is the item's own `id`:

   ```bash
   review_path="$(gh api repos/{owner}/{repo}/pulls/comments/{comment_id} --jq '.path')"
   ```

2. Read the file at the item's `commit_id` (block above).
3. Fetch that commit's diff for the file (the item's `diff_hunk` already gives the immediate
   context):

   ```bash
   gh api repos/{owner}/{repo}/commits/{commit_id} | jq --arg path "$review_path" '.files[] | select(.filename == $path) | .patch'
   ```

#### Review-body item

There is no single path or line, so gather broader context:

1. Fetch all files changed in the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/files --jq '.[] | {filename, status, patch}'
   ```

2. If the body names specific files or functions, read those files at the review's `commit_id`
   (block above), binding each `review_path` from a `filename` that step 1 returned — never
   retyped from the review body.

#### Every item

Fetch the current PR head SHA once per URL:

```bash
gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
```

If the head has advanced past an item's `commit_id`, briefly check whether the issue that item
raises has already been fixed in a later commit. If so, note this in its evaluation reasoning.

### Step 7 — Evaluate validity

Evaluate **every item independently** against the code and project conventions, producing a
separate verdict per item. Consider:

1. **Accuracy** — Is the factual claim correct? (e.g., a reported bug must actually exist)
2. **Relevance** — Does the item apply to the code at the stated location?
   - Line-comment item: the specific line(s).
   - Review-body item: the overall PR diff (or the specific files/areas explicitly named in the body).
3. **Constructiveness** — Does it clearly identify a problem or suggest a valid improvement?
   - Review-body item: if the body is vague or only asks questions without requesting changes, the evaluation may conclude that no code change is required.
4. **Project conventions** — Does it align with the project's documented rules (check `.claude/CLAUDE.md` and `docs/*.md`)?
5. **Language / library / framework / external-service correctness** — When the item concerns usage of a language runtime, library, framework, or external service (e.g., REST API, cloud SDK), verify the claim against the **official documentation of the exact version the project uses**. Prefer official docs (API reference, language spec, release notes, vendor API docs) over blog posts or Stack Overflow. If the docs do not cover the point, inspect the source code or make a controlled test request directly. Do not rely on memory or general assumptions.
6. **Regression / security / data-integrity risk** — If the item proposes or implies a code change, consider whether adopting it could break existing behavior (regression), weaken security boundaries, or corrupt data. Focus especially on:
   - **Security**: authorization checks, input validation, secrets handling, injection vectors, and race conditions (e.g., TOCTOU).
   - **Data integrity**: transaction boundaries, unique / foreign-key constraints, merge semantics (`DatabaseMerger`, `MergeSql`, `SyncRepository`), FTS index consistency (`articles_fts` lifetime rules), and logical-deletion timestamps.
   - Verify the proposal does not violate documented critical constraints (e.g., `.claude/CLAUDE.md` "Critical constraints", `docs/sync-architecture.md`).

A review-body item is evaluated only for what it raises **beyond** the review's line-comment
items. If everything it says is already covered by them, its verdict is **No action needed —
covered by the line comments**, and it gets no reviewer-facing reply of its own (Step 8).

For every item, record its **Validity** (Valid / Invalid / Partially Valid), **Confidence**
(High / Medium / Low, as defined in `.claude/etc/review/common.md` §6), and **Reasoning**. Do not
print this immediately: Step 8 surfaces it, first as a row in the summary table, then as this
per-item detail block:

```markdown
## Evaluation

**URL**: <the item's Comment URL — see 8.1>
**Finding summary**: <one-line paraphrase of the item's point, not a verbatim quote>
**Validity**: [Valid / Invalid / Partially Valid]
**Confidence**: [High / Medium / Low]

### Reasoning
[Your analysis. Cite specific lines or docs where applicable.]
```

#### Confirming Medium / Low confidence with temporary instrumentation

Any item whose Confidence is **Medium** or **Low** — whatever its Validity, so an Invalid
verdict is not dismissed on a guess either — must be backed by evidence that directly
observes the claim before its verdict is final. Gather it, and handle any temporary code,
per `.claude/etc/temporary-instrumentation.md` (read it before inserting anything); it keeps
Step 8's clean-tree guard intact by requiring every probe to be removed first.

- **Where the outcome goes**: update the item's Validity / Confidence and record the evidence
  (what was inserted, what was observed, any mock and why it does not affect the conclusion —
  or why the comment cannot be observed at runtime) in its Reasoning.
- **A reproducing test** is temporary at this stage and is removed with the other probes; if
  it would serve as the fix's test, include it in that item's section of the Step 8 plan.

### Step 8 — Take action

Every URL goes through the same steps below, whether it has one item or many.

#### 8.1 — Summary table

Before printing any item's detail block or taking any action, print one Markdown table with a
row per item, in item order:

| Comment URL | Finding summary | Validity | Confidence | Next action |
| --- | --- | --- | --- | --- |

- **Comment URL** — for a line-comment item, its `html_url` (fall back to `path`:`line` /
  `original_line` if missing); for a review-body item, the `pullrequestreview-...` URL.
- **Finding summary** — a concise one-line paraphrase (not a verbatim quote) of the item's point.
- **Validity** / **Confidence** — that item's Step 7 Validity / Confidence.
- **Next action** — **Fix & commit** (Valid/Partially Valid + code change needed) or
  **No action needed**.

This table is a preview; 8.7 reports what actually happened to each item.

#### 8.2 — Detail blocks and reviewer replies

Right after the table, print every item's Step 7 detail block in item order, so the reasoning
is visible before the plan is approved; they are not printed again during execution.

Directly after the detail block, output a suggested reply for the reviewer as a fenced
```markdown``` code block the user can copy into GitHub — this skill never posts it — for:

- every **No action needed** item, except a review body covered by the line comments: why no
  change is being made;
- every **Partially Valid** item marked **Fix & commit**: which part is being fixed and why the
  rest is left as-is.

Compose it from the Step 7 Reasoning as plain, polite prose for a public PR reply (no internal
labels like "Confidence: Low"), opening with a brief nod to what the item pointed out. Write it
in the language of the reviewer's own `body`; if that cannot be confidently identified, use
English. For example:

```markdown
Thanks for the review. After checking, I've concluded no change is needed here because 〈reason〉.
```

```markdown
ご指摘ありがとうございます。確認した結果、〈理由〉のため、現状のコードに変更は不要と判断しました。
```

#### 8.3 — Nothing to fix

If no item is **Fix & commit**, output the line below and end this URL
(`No implementation plan needed`):

```text
No implementation plan is needed.
```

#### 8.4 — Guards

Before planning anything:

1. If the current branch is a version branch (`v*`), run `/feature-branch fix` to create and
   switch to a feature branch. Step 3's branch-alignment check makes this unlikely in practice
   (the PR's head branch is normally already a feature branch), but guard for it anyway.
2. Check `git status --porcelain`. If the working tree is dirty with changes unrelated to this
   run, warn and end this URL (`Working tree not clean`) — a failed-verification revert (8.6)
   must never be able to destroy pre-existing uncommitted work in the same file. A clean result
   here is the baseline for 8.6's re-check.

#### 8.5 — Plan, refine, approve

1. Call `EnterPlanMode`.
2. Explore the codebase to understand the surrounding architecture of every **Fix & commit** item.
3. Write the plan to `~/.claude/plans/evaluate-review-{id}.md` (`{id}` = the URL's trailing
   numeric ID) with:
   - **Context** — what the review pointed out and why it matters.
   - **One section per Fix & commit item**, in item order, each with:
     - **Files to modify** — exact file paths (a review-body item may span several files)
     - **Changes** — concrete, line-level actions
     - **Verification** — the tests, build targets and manual checks that confirm this item.
       For an item whose evidence came from Step 7's temporary instrumentation and whose effect
       is measurable, add "re-measure per 8.6".
   - **Commit Strategy** — exactly one commit per item, in item order, each with a
     **single-line** Conventional Commits message (no body, no trailer), made after that
     item's verification passes; never pushed.
4. Invoke the `refine-plan` skill with the plan file path as its argument, and resolve any
   question it asks before proceeding.
5. Call `ExitPlanMode` to submit the refined plan for user approval. **This approval authorizes
   the whole URL** — every item in the plan proceeds through fix → verify → commit with no
   further per-item approval, which satisfies the repo's "don't commit unless asked"
   convention. If the user rejects it, implement nothing, commit nothing, and end this URL
   (`Plan rejected`); revise the plan instead if the user asks for changes.

#### 8.6 — Fix, verify, commit each item

Approval can take an unbounded time, so right before the first edit re-run
`git status --porcelain` and confirm it still matches 8.4's clean baseline. If it no longer
does, commit nothing and end this URL (`Working tree not clean`).

Then, for each **Fix & commit** item, in item order:

1. Implement that item's section of the approved plan — that item only — following the repo's
   standing constraints (`.claude/CLAUDE.md`, `docs/testing.md` — including adding/updating
   tests where constraint #7 applies).
2. Verify: run the item's planned Verification. At minimum run `./gradlew build` (or the
   narrowest relevant test target, e.g. `:shared:desktopTest --tests "..."` /
   `:composeApp:desktopTest --tests "..."`, when that clearly covers the change). When the fix
   touches `shared/src/apple*` or `appleApp/`, also run `./gradlew :shared:macosArm64Test` and,
   for `appleApp/`, `xcodebuild -scheme Keryx -destination 'platform=macOS' test` — the JVM build
   never compiles those. Off a Mac, treat that as unverified: do not commit it. When the plan says
   to re-measure, re-insert Step 7's measurement and check the effect, then remove it again per
   `.claude/etc/temporary-instrumentation.md` §3–§4 **whatever the outcome** — including when
   the effect is not confirmed or the run fails — and confirm a fresh §4 snapshot matches the
   fix-only one taken before re-inserting it, before doing anything else.
   - If verification fails (including a re-measurement that does not confirm the effect):
     first make sure no temporary instrumentation remains (the marker check from §3 prints
     nothing), then confirm via `git status`/`git diff` that only this item's intended changes
     are present, so the revert cannot destroy pre-existing work in the same file. Then
     **revert that one item** — `git restore -- <files>` for the tracked files it changed, and
     delete any untracked file it created — and confirm `git status --porcelain` is back to the
     clean baseline, so nothing of it reaches the next item. Do not commit. Record
     `Verification failed: <reason>` and move on to the next item.
3. Commit: review `git status --porcelain` / `git diff`, then run the marker check from
   `.claude/etc/temporary-instrumentation.md` §3 and confirm it prints nothing **before staging
   anything**. Then stage **only the files, and only the hunks, touched by this item's fix** —
   verify the staged diff contains nothing beyond this item's change before committing, so
   unrelated pre-existing edits or residue from a prior item's failed attempt never ride along.
   Create **exactly one commit** with a **single-line** commit message only (no body, no
   trailer), in the repo's Conventional Commits style, e.g.
   `fix(sync): correct rev comparison for Google Drive`. Never batch multiple items into one
   commit, and never amend a commit made earlier in this same run.
4. Continue to the next item regardless of the previous item's outcome.

#### 8.7 — Closing summary

Output a short summary listing, for each item (its Comment URL), its verdict and its item
outcome from the Outcomes table.

#### Multiple top-level URLs

If more than one URL was given, after the last one finishes, output one final cross-URL summary
— in the order the URLs were given — listing each URL and its URL outcome from the Outcomes
table. This is in addition to each URL's own Step 8 output.

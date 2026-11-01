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
on it. The URL is broken into **items** — a `discussion_r` comment is one item; a
`pullrequestreview` is one item per associated line comment plus its body — and every URL then
runs the same flow regardless of how many items it has: evaluate each item, plan the ones that
need a change, refine the plan, get it approved, then fix, verify and commit each item as its own
commit — see Step 8. When multiple URLs are given, each one runs
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
- **If one or more**: treat each URL separately and run it through Steps 2–8 as its own
  independent pass — its own branch check, fetch, evaluation, and Step 8 action (including
  waiting out `EnterPlanMode`/`ExitPlanMode` approval). Process the URLs
  **one at a time, strictly in the order the URLs were given** — never reorder, batch, or run
  two URLs concurrently. Fully finish one URL's Step 8 action before starting the next URL at
  Step 2 (including the post-approval fixes and commits). If **only one** URL was given, proceed exactly as before (no cross-URL summary at the
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

#### Normalize into items

Everything after this step works on a list of **items**, so a single comment and a review with
many comments run through the same flow — they differ only in how many items there are.

- `review_comment` → one **line-comment item** (the fetched comment).
- `pr_review` → one **line-comment item** per entry in `associated_comments`, in API order, then
  one **review-body item** at the end if the review's `body` is not empty.

Steps 5–7 branch on the item's kind (line comment / review body), never on `comment_type`.

### Step 5 — Gather thread context

A review comment or review is often part of a longer conversation. Reconstruct the surrounding discussion so the evaluation accounts for prior clarification or rebuttal.

#### Line-comment items

1. Fetch **all** review comments on the PR — once per URL, shared by every line-comment item:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/comments
   ```

2. Build the reply graph using the `in_reply_to_id` field. Each comment either starts a thread (`in_reply_to_id` is absent) or replies to another.
3. For each line-comment item, follow the `in_reply_to_id` chain backward from the item's comment to the root of its thread, then collect **all** comments in that thread—both ancestors and descendants—and present the complete conversation in chronological order.
4. Include this conversation history in the item's analysis. Pay attention to:
   - Whether earlier rounds already accepted, rejected, or refined the point.
   - Whether the comment is a follow-up that narrows or shifts the request.
   - Any author push-back or reviewer concession that changes the expected outcome.

#### Review-body item

PR review summaries do not use `in_reply_to_id`, so build context from chronology instead:

1. Fetch **all** reviews on the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews
   ```

2. Sort by `submitted_at` to locate chronologically adjacent reviews from the same reviewer (or from any reviewer) that provide context.
3. Include these adjacent reviews in the item's analysis. Note especially:
   - Whether a previous review already covered the same points.
   - Whether the author has already responded or pushed back.
   - Whether the review is a follow-up that narrows or shifts earlier requests.

### Step 6 — Gather code context

#### Line-comment items

For each line-comment item, using **that item's own** `commit_id`, `path` and line range (items
on the same file but different commits are handled separately):

1. Load the code exactly as it existed when the comment was left. If the reviewed file exists in the local working tree **and matches `commit_id`**, read it for full context.
2. Otherwise, fetch the file content at `commit_id`:

   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={commit_id}
   ```

   **URL-encode the `{path}` segment** before interpolation (e.g. space → `%20`, `#` → `%23`, `?` → `%3F`) while preserving `/` between directory components. Decode the `content` field from base64 if necessary.
3. Fetch the diff for that commit to understand the change being reviewed. **Never interpolate
   the review-provided `{path}` into a jq filter string or an unquoted shell token** — a path
   containing a quote or backtick could otherwise break the jq expression or the constructed
   shell command. Bind it through a quoted shell variable and jq's `--arg` instead:

   ```bash
   path='{path}'
   gh api repos/{owner}/{repo}/commits/{commit_id} | jq --arg path "$path" '.files[] | select(.filename == $path) | .patch'
   ```

   (The item's `diff_hunk` already provides the immediate surrounding context.)

#### Review-body item

There is no single `path` or `line`. Gather broader context from the PR:

1. Fetch all files changed in the PR:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/files --jq '.[] | {filename, status, patch}'
   ```

2. If the review body explicitly names specific files or functions, also fetch the content of those files at the review's `commit_id` (URL-encode `{path}` as above):

   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={commit_id}
   ```

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

For every item, record its **Validity** ([Valid / Invalid / Partially Valid]),
**Confidence** ([High / Medium / Low]), and **Reasoning**. Do not print this immediately:
Step 8 defines exactly when and how it is surfaced — first as a row in the summary table, then
as this per-item detail block, in this exact format:

```markdown
## Evaluation

**URL**: <the item's URL — see the summary table's Comment URL column in Step 8>
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

- **Comment URL** — for a line-comment item, its comment's `html_url` (for a `discussion_r...`
  URL given to the skill, that URL; fall back to `path`:`line`/`original_line` if no URL is
  available); for a review-body item, the `pullrequestreview-...` URL.
- **Finding summary** — a concise one-line paraphrase (not a verbatim quote) of the item's point.
- **Validity** / **Confidence** — that item's Step 7 Validity / Confidence.
- **Next action** — **Fix & commit** (Valid/Partially Valid + code change needed) or **No
  action needed** (Invalid, valid but no code change required, or a review body already
  covered by the line comments).

This table is a preview, not the final outcome — the closing summary (8.7) reports what
actually happened to each item, which can differ from Next action if a fix later fails
verification.

#### 8.2 — Detail blocks and reviewer replies

Right after the table, print every item's Step 7 detail block in item order, so the reasoning
is visible before the plan is approved; they are not printed again during execution.

Directly after the detail block of an item whose Next action is **No action needed** (except a
review body already covered by the line comments), output a suggested reply comment for the
reviewer as a fenced ```markdown``` code block so the user can copy it directly into GitHub —
this skill never posts it automatically. Compose it from the Step 7 **Reasoning**, rewritten as
plain, polite prose suitable for a public PR reply (do not surface internal labels like
"Confidence: Low" verbatim). Cover: (1) a brief nod to what the item pointed out, (2) why no
change is being made, and (3) — when the verdict is **Partially Valid** — which part is being
left as-is versus already covered elsewhere. Match the language of the reviewer's own text:
identify the natural language the original `body` is written in, and write the reply in that
same language — this is not limited to specific languages; a comment in English, Japanese,
French, or any other language gets a reply in that language. English and Japanese are simply
the two most common cases in this repo (examples of each below). If the body mixes languages or
its language cannot be confidently identified (e.g. it's too short or is mostly code with little
natural-language text), fall back to English.

For example, in English:

```markdown
Thanks for the review. After checking, I've concluded no change is needed here because 〈reason〉.
```

or in Japanese:

```markdown
ご指摘ありがとうございます。確認した結果、〈理由〉のため、現状のコードに変更は不要と判断しました。
```

#### 8.3 — Nothing to fix

If no item is **Fix & commit**, state clearly, then end this URL (continue to the next URL at
Step 2 in a multi-URL run):

```text
No implementation plan is needed.
```

#### 8.4 — Guards

Before planning anything:

1. If the current branch is a version branch (`v*`), run `/feature-branch fix` to create and
   switch to a feature branch. Step 3's branch-alignment check makes this unlikely in practice
   (the PR's head branch is normally already a feature branch), but guard for it anyway.
2. Check `git status --porcelain`. If the working tree is dirty with changes unrelated to this
   run, warn and skip this URL entirely (plan nothing, commit nothing, note "Working tree not
   clean" in the closing summary) — a failed-verification revert (8.6) must never be able to
   destroy pre-existing uncommitted work in the same file. In a multi-URL run this only skips
   the current URL; continue to the next one at Step 2. A clean result here is the baseline for
   8.6's re-check.

#### 8.5 — Plan, refine, approve

1. Call `EnterPlanMode`.
2. Explore the codebase to understand the surrounding architecture of every **Fix & commit** item.
3. Write the plan to `~/.claude/plans/evaluate-review-{id}.md` (`{id}` = the URL's trailing
   numeric ID) with:
   - **Context** — what the review pointed out and why it matters.
   - **One section per Fix & commit item**, in item order, each with:
     - **Files to modify** — exact file paths (a review-body item may span several files)
     - **Changes** — concrete, line-level actions
     - **Verification** — how to test (unit tests, manual checks, build). For an item whose
       evidence came from Step 7's temporary instrumentation, include the post-fix
       re-measurement when the effect is measurable — taking the §4 snapshot of the fix
       (tracked changes and untracked files) before re-inserting the measurement and
       confirming after its removal that a fresh snapshot matches it exactly
       (`.claude/etc/temporary-instrumentation.md` §4) — and the marker check from §3 (no
       temporary instrumentation remains).
   - **Commit Strategy** — exactly one commit per item, in item order, each with a
     **single-line** Conventional Commits message (no body, no trailer), made after that
     item's verification passes; never pushed.
4. **Refine the plan**: invoke the `refine-plan` skill, passing the plan file path
   (`~/.claude/plans/evaluate-review-{id}.md`) as its argument so it targets exactly the
   file just written (`refine-plan` resolves `$ARGUMENTS` as a file path before falling back to
   the active Plan Mode file, removing any ambiguity about which document is refined). It runs its
   fixed checklist (open questions, data integrity, UI/UX, simplicity, codebase match, scope, risk,
   verification, sequencing, commit strategy), auto-fixes what it can, and trims the plan to final
   agreed content. If `refine-plan` surfaces a genuine decision it asks the user via
   `AskUserQuestion`; resolve those before proceeding.
5. Call `ExitPlanMode` to submit the refined plan for user approval. **This approval authorizes
   the whole URL** — every item in the plan proceeds through fix → verify → commit with no
   further per-item approval, which satisfies the repo's "don't commit unless asked"
   convention. If the user rejects it, implement nothing and commit nothing (outcome: `Plan
   rejected`); revise the plan instead if the user asks for changes.

#### 8.6 — Fix, verify, commit each item

Approval can take an unbounded time, so right before the first edit re-run
`git status --porcelain` and confirm it still matches 8.4's clean baseline. If it no longer
does, treat it exactly like 8.4's dirty-tree case: skip this URL entirely (commit nothing, note
"Working tree not clean") and continue to the next URL in a multi-URL run.

Then, for each **Fix & commit** item, in item order:

1. Implement that item's section of the approved plan — that item only — following the repo's
   standing constraints (`.claude/CLAUDE.md`, `docs/testing.md` — including adding/updating
   tests where constraint #7 applies).
2. Verify: run `./gradlew build` (or the narrowest relevant test target, e.g.
   `:shared:desktopTest --tests "..."` / `:composeApp:desktopTest --tests "..."`, when that is
   clearly sufficient to cover the change). When the fix touches `shared/src/apple*` or
   `appleApp/`, also run `./gradlew :shared:macosArm64Test` and, for `appleApp/`,
   `xcodebuild -scheme Keryx -destination 'platform=macOS' test` — the JVM build never compiles
   those. Off a Mac, treat that as unverified: do not commit it. When the item's evidence came
   from Step 7's temporary instrumentation and the fix's effect is measurable, re-insert that
   measurement and check the effect, then remove it again per
   `.claude/etc/temporary-instrumentation.md` §3–§4 **whatever the outcome** — including when
   the effect is not confirmed or the run fails — and confirm a fresh §4 snapshot matches the
   fix-only one taken before re-inserting it, before doing anything else.
   - If verification fails (including a re-measurement that does not confirm the effect):
     first make sure no temporary instrumentation remains (the marker check from §3 prints
     nothing), then confirm via `git status`/`git diff` that only this item's intended changes
     are present before reverting, so a revert cannot destroy pre-existing work in the same
     file; then do not commit. Note the failure (with the reason) for the closing summary, and
     move on to the next item.
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

Output a short summary listing, for each item (its Comment URL), its verdict and outcome:
`Committed <sha>` / `Skipped: <reason>` / `Verification failed: <reason>`.

#### Multiple top-level URLs

If Step 1 received more than one URL, after the **last** one finishes its Step 8 action, output
one final cross-URL summary — in the same order the URLs were given — listing each URL and its
overall outcome (e.g. `Committed <sha>` / `N commits`, `Plan rejected`, `Verification failed`,
`Working tree not clean`, `No implementation plan needed`, `Invalid`). This is in addition to,
not a replacement for, each URL's own Step 7 evaluation and Step 8 output.

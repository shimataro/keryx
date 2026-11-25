---
name: evaluate-review
description: Evaluate a GitHub PR review comment or review summary for technical validity. Each finding (a discussion comment, or a review's line comments and body) is evaluated on its own; the ones needing a change go into one implementation plan for user approval, and once approved each is fixed, verified and committed independently. Accepts multiple URLs, processed one at a time in the order given. Invoke with /evaluate-review <url> [<url> ...].
model: opus
effort: medium
context: fork
---

# Evaluate Review

> This skill involves **high-accuracy judgments** — deciding whether a reviewer's claim is
> actually correct against this codebase, and committing fixes for the ones that are.
> It runs in a forked subagent context so its frontmatter pins `model: opus` at `effort: medium`
> for the whole workflow.
>
> **Two-phase workflow.** `context: fork` removes `AskUserQuestion`, `EnterPlanMode` and
> `ExitPlanMode` from this subagent, so the fork phase is **read-only / non-interactive**: it
> validates URLs, fetches the review, gathers thread and code context, evaluates every item,
> produces the summary table, detail blocks, drafted replies, a plan file and a hand-off, then
> returns all of that as one report. **User decisions, plan approval and implementation happen in
> the main conversation** after it receives the report, following the hand-off instructions at the
> end of the fork's output.

Evaluate a GitHub pull-request review comment or review summary for technical validity, then act
on it: each URL is broken into **items** (Step 4), every item is evaluated on its own, and the
ones needing a change are planned, approved once, then fixed, verified and committed one commit
per item (Step 8).

## Usage

```text
/evaluate-review <github-pr-review-url> [<github-pr-review-url> ...]
/evaluate-review --continue-unaligned <github-pr-review-url> [<github-pr-review-url> ...]
```

Examples:

```text
/evaluate-review https://github.com/shimataro/keryx/pull/3#discussion_r3618156689
/evaluate-review --continue-unaligned https://github.com/shimataro/keryx/pull/3#pullrequestreview-2183723456
```

The optional `--continue-unaligned` flag is used when the main conversation has decided to run
the evaluation on the current checkout despite a checkout-alignment warning recorded by Step 3.
It is stripped before URL validation and honored only by Step 3, which skips the alignment checks
for this run and reports which check was skipped.

## Outcomes

Every place that reports a result uses only these labels — plus the suffix
` (Apple targets not verified locally)` on `Committed <sha>` when `.claude/etc/per-item-commit.md`
§2 calls for it.

| Scope | Label | When |
| --- | --- | --- |
| Item | `Committed <sha>` | Fixed, verified and committed (8.6) |
| Item | `No action needed` | Invalid, valid but needing no code change, unconfirmed (Step 7), or a review body covered by the line comments (Step 7) |
| Item | `Verification failed: <reason>` | Fix reverted after verification failed (8.6) |
| Item / URL | `Plan rejected` | The user rejected the plan (8.5) |
| Item / URL | `Working tree not clean` | Uncommitted changes at 8.4 or 8.6 |
| URL | `PR head moved` | At 8.6 the PR head differs from the one Step 3 recorded and is not contained in HEAD (e.g. it advanced during plan approval) |
| URL | `Invalid URL` | Step 2 |
| URL | `Fetch failed: <reason>` | A Step 4 request failed |
| URL | `Not in this PR` | The fetched comment/review belongs to another PR (Step 4) |
| URL | `No actionable feedback` | A review with no body and no line comments (Step 4) |
| URL | `No implementation plan needed` | No item needs a fix (8.3) |
| URL | `N committed, M failed` | Summary of a URL whose plan ran (8.6) |
| URL | `Needs checkout alignment` | Step 3 found a mismatch that requires a main-conversation decision before evaluation can continue |
| Run | `Cancelled` | The user cancelled at Step 3 |

**End this URL** (used throughout): print the reason, record the URL's outcome from the table,
and continue with the next URL at Step 2. After the last URL, print the cross-URL summary (see
the end of Step 8) if more than one URL was given.

## Steps

### Step 1 — Arguments

With no URL, ask the user for one. With several, the fork runs each URL through Steps 2–8.5 as
its own independent pass, **one at a time, in the order given**. The main conversation then
executes each URL's hand-off in that same order, finishing one URL's implementation before the
next URL's plan approval; because a later plan is written before earlier fixes are committed, the
8.5 hand-off re-checks each later plan against the current tree.

If the first argument is `--continue-unaligned`, strip it and record it for Step 3. Do not pass
it to URL validation; the flag applies to the whole run, not to any URL.

### Step 2 — Validate URL format

Any leading `--continue-unaligned` flag has already been stripped in Step 1. Parse the remaining
arguments as URLs. Each must match one of the following patterns:

1. **Review comment** (line-level):
   `https://github.com/<owner>/<repo>/pull/<number>#discussion_r<comment_id>`
2. **PR review** (overall summary):
   `https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<review_id>`

- Extract `owner`, `repo`, `pull_number`, and the trailing numeric ID. Each must match its
  character set in full — `owner` `[A-Za-z0-9-]+`, `repo` `[A-Za-z0-9._-]+`, `pull_number` and
  the ID `[0-9]+` — or the URL does not match. Only these validated values and GitHub-generated
  IDs and SHAs from the API (digits / hex only, e.g. `{comment_id}`, `{commit_id}`) are written
  straight into commands: those character sets hold no shell syntax. Anything PR-controlled
  still goes through a variable (Steps 3 and 6). Shell variables do not survive from one Bash
  call to the next, so run the lines that bind such a variable (`pr_*`, `review_path`) in the
  same call as every command that uses it; a later call repeats the binding lines to re-bind it
  from the API, never retyping the value. The one exception is the PR head SHA 8.6 compares
  against, which is the value Step 3.1 printed (hex, so written in directly).
- Determine `comment_type`: `review_comment` for `#discussion_r`, `pr_review` for `#pullrequestreview`.
- If the URL matches neither pattern, say so and end this URL (`Invalid URL`).

### Step 3 — Verify branch alignment (read-only in fork; decisions in main conversation)

The PR's head branch name is PR-controlled, like a review `path` (Step 6): bind it from the API
into a shell variable and only ever use the variable, never the retyped name.

1. Fetch the PR's head branch, repository and commit:

   ```bash
   pr_json="$(gh api repos/{owner}/{repo}/pulls/{pull_number})"
   pr_branch="$(jq -r '.head.ref' <<<"$pr_json")"
   pr_head_repo="$(jq -r '.head.repo.full_name' <<<"$pr_json")"
   pr_head_sha="$(jq -r '.head.sha' <<<"$pr_json")"
   printf 'PR head: %s\n' "$pr_head_sha"
   ```

   The printed `PR head` SHA is the one 8.6 compares against, so it stays in the transcript.

   If this fails (e.g. network error, bad credentials), output a warning and proceed to Step 4.
2. Get the current local branch with `git branch --show-current`. If the directory is not a git
   repository, output a warning and proceed to Step 4.
3. If the `--continue-unaligned` flag was recorded in Step 1, report which checkout-alignment
   check is being skipped (branch mismatch, or PR head not an ancestor of HEAD), include the usual
   warning, and continue with the evaluation for this run only. The guard is unchanged for runs
   without the flag.

   If the flag is **not** set and the branches do not match, or if
   `git merge-base --is-ancestor "$pr_head_sha" HEAD` fails (HEAD is behind, diverged, or the
   commit is not available), the fork **does not continue**. Instead, report the observed state
   precisely (PR branch, current branch, HEAD/ancestor state) and append a **hand-off block**
   that tells the main conversation what decision is needed and how to proceed. The executable
   options are: realign the checkout (switch to the PR branch, or fast-forward to the PR head when
   possible; never on a fork PR) and re-run the skill; or cancel. If the main conversation decides
   to evaluate on the current checkout anyway, it re-runs the skill with `--continue-unaligned`.
   End this URL with the outcome `Needs checkout alignment`.

### Step 4 — Fetch the comment or review

Any failed request below: output the error and end this URL (`Fetch failed: <reason>`).

**Line-comment fields** — kept for every line comment fetched here: `id`, `html_url`, `body`,
`path`, `line` / `original_line` (ending line), `start_line` / `original_start_line` (starting
line of a multi-line comment), `side` / `start_side` (`LEFT` or `RIGHT`), `diff_hunk`,
`original_commit_id`, `commit_id` and `in_reply_to_id` (absent on a thread's first comment). The reviewed revision is `original_commit_id` (the commit
the comment was left on), with `original_line` / `original_start_line` its position there — on
the `RIGHT` side only: a `LEFT` line is a deleted line, which exists only on the diff's base side;
GitHub moves `commit_id` and `line` / `start_line` along as the PR advances, so they give the
comment's current position, not what the reviewer saw.

**PR membership check** — right after fetching the comment or review, and before anything else,
confirm its `pull_request_url` equals
`https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}`. If not, report both URLs and
end this URL (`Not in this PR`).

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
2. List the review's line comments:

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments
   ```

   This endpoint omits `line` / `original_line`, `start_line` / `original_start_line` and
   `side` / `start_side`, so fetch each listed comment again by its `id` (digits, written
   directly) and keep the line-comment fields from that response as `associated_comments`:

   ```bash
   gh api repos/{owner}/{repo}/pulls/comments/{id}
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

For each item, using **that item's own** `original_commit_id`, path and `original_*` line range
(items on the same file but different commits are handled separately):

1. Bind its path — `{comment_id}` is the item's own `id`:

   ```bash
   review_path="$(gh api repos/{owner}/{repo}/pulls/comments/{comment_id} --jq '.path')"
   ```

2. Read the file at the item's `original_commit_id` (block above, with it as `{commit_id}`). If
   `side` (or, for a multi-line comment, `start_side`) is `LEFT`, the commented lines are deleted
   ones that this file no longer contains: do not look them up by number here — find them in
   `diff_hunk`, and use this file only for the surrounding code.
3. Fetch that commit's diff for the file (the item's `diff_hunk` already gives the immediate
   context; an empty result means that commit did not touch the file, so rely on `diff_hunk`):

   ```bash
   gh api repos/{owner}/{repo}/commits/{original_commit_id} | jq --arg path "$review_path" '.files[] | select(.filename == $path) | .patch'
   ```

#### Review-body item

There is no single path or line, so gather broader context:

1. Fetch all files changed in the PR, numbered from 0 (`--paginate --jq` would filter each page
   on its own, so the pages are joined with `jq -s add` first):

   ```bash
   gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/files | jq -s 'add | to_entries[] | {index: .key, filename: .value.filename, status: .value.status, patch: .value.patch}'
   ```

2. If the body names specific files or functions, read each of those files at the review's
   `commit_id` (block above), binding `review_path` by its step 1 `index` (digits, so written
   directly as `{index}`) in the same Bash call — never retyping the filename from the review
   body or from step 1's output:

   ```bash
   review_path="$(gh api --paginate repos/{owner}/{repo}/pulls/{pull_number}/files | jq -rs --argjson i {index} 'add | .[$i].filename')"
   printf 'review_path: %s\n' "$review_path"
   ```

   The list is fetched again here, so confirm the printed `review_path` is the `filename` step 1
   showed for that index; if not, the PR changed in between — start this item's step 1 again.

#### Every item

Fetch the current PR head SHA once per URL:

```bash
gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
```

If the head has advanced past the revision an item was raised on — a line comment's
`original_commit_id`, a review body's `commit_id` (a review's own `commit_id` stays at the
reviewed commit) — briefly check whether the issue that item raises has already been fixed in a
later commit. If so, note this in its evaluation reasoning.

### Step 7 — Evaluate validity

Evaluate **every item independently** — is its claim true of the code it points at (a line
comment: its lines; a review body: the PR diff or the files it names), and is the change it asks
for worth making? A vague review body that only asks questions may need no code change. Beyond
that, hold each item to:

1. **Project conventions** — Does it align with the project's documented rules (check `.claude/CLAUDE.md` and `docs/*.md`)?
2. **Language / library / framework / external-service correctness** — When the item concerns usage
   of a language runtime, library, framework, or external service (e.g., REST API, cloud SDK),
   verify the claim against the **official documentation of the exact version the project uses**.
   Prefer official docs (API reference, language spec, release notes, vendor API docs) over blog
   posts or Stack Overflow. If the docs do not cover the point, inspect the source code or make a
   controlled test request directly. Do not rely on memory or general assumptions.
3. **Regression / security / data-integrity risk** — If the item proposes or implies a code change, consider whether adopting it could break existing behavior (regression), weaken security boundaries, or corrupt data. Focus especially on:
   - **Security**: authorization checks, input validation, secrets handling, injection vectors, and race conditions (e.g., TOCTOU).
   - **Data integrity**: transaction boundaries, unique / foreign-key constraints, merge semantics (`DatabaseMerger`, `MergeSql`, `SyncRepository`), FTS index consistency (`articles_fts` lifetime rules), and logical-deletion timestamps.
   - Verify the proposal does not violate documented critical constraints (e.g., `.claude/CLAUDE.md` "Critical constraints", `docs/sync-architecture.md`).
4. **Likelihood is not a verdict** — An item reporting a reachable failure path or race is not Invalid, or only Partially Valid, because its window is narrow or it "practically never happens" (`.claude/CLAUDE.md` "Error handling and race conditions"). Judge whether the interleaving or failure is reachable, not how often.

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
- **Unconfirmed** (`.claude/etc/temporary-instrumentation.md` §2): the item's Next action is
  **No action needed**, its Reasoning records what was tried, and its reviewer reply (8.2) says
  the point could not be confirmed and what evidence would settle it.

### Step 8 — Take action (fork prepares the report and plan; main conversation approves and implements)

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
English.

**Every suggested reply is paired with the URL it answers** — never output a reply block on its
own. Put a `**Reply to**: <url>` line directly above the code block, where `<url>` is the item's
Comment URL from 8.1 (if that fell back to `path`:`line`, show that instead; the pairing still
holds):

- **Thread-capable target** (`#discussion_r…`, a line-comment item) — the reply is posted in that
  thread. If the item is the thread's first comment (no `in_reply_to_id`), the thread already
  identifies what it answers, so the reply body does not repeat the URL. If the item is itself a
  reply in the thread (`in_reply_to_id` set), GitHub threads are flat and cannot show which reply
  it answers, so open the reply body with the target URL, using the same lead-in as below.
- **Any other target** (`#pullrequestreview-…` for a review-body item, `#issuecomment-…`, and
  anything else that is not `#discussion_r…`) — GitHub has no thread reply for it, so the reply
  lands in the PR's conversation. Also open the reply body itself with the target URL, phrased in
  the reply's language: `In reply to <url>:` in English, `<url> への返信です：` in Japanese, and an
  equally natural lead-in for any other language.

For example, for a review-body item:

````markdown
**Reply to**: https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<id>

```markdown
In reply to https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<id>:

Thanks for the review. 〈Which part is being fixed, and why the rest is left as-is.〉
```
````

#### 8.3 — Nothing to fix

If no item is **Fix & commit**, say so and end this URL (`No implementation plan needed`).

#### 8.4 — Guards

Before planning anything:

1. Check `git status --porcelain`. If it prints anything, warn and end this URL
   (`Working tree not clean`) — a failed-verification revert (8.6)
   must never be able to destroy pre-existing uncommitted work in the same file. A clean result
   here is the baseline for 8.6's re-check.

#### 8.5 — Plan and hand-off (fork writes the file; main conversation approves and implements)

The fork phase performs only the planning write; approval and execution are left to the main
conversation.

1. Write the plan to `~/.claude/plans/evaluate-review-{id}.md` (`{id}` = the URL's trailing
   numeric ID) with:
   - **Context** — what the review pointed out and why it matters.
   - **One section per Fix & commit item**, in item order, each with:
     - **Files to modify** — exact file paths (a review-body item may span several files)
     - **Changes** — concrete, line-level actions
     - **Verification** — the tests, build targets and manual checks that confirm this item.
       For an item whose evidence came from Step 7's temporary instrumentation and whose effect
       is measurable, add "re-measure per 8.6".
   - **Commit Strategy** — one commit per item, in item order, per
     `.claude/etc/per-item-commit.md` §4, made after that item's verification passes.
2. Append a **hand-off block** that tells the main conversation exactly what to do:
   - Call `EnterPlanMode`, invoke the `refine-plan` skill with the plan file path as its
     argument, resolve any question it asks before proceeding, then call `ExitPlanMode` to submit
     the refined plan for user approval. This approval is the whole URL's one-time run
     confirmation (`.claude/etc/per-item-commit.md` §1). If the user rejects it, implement
     nothing, commit nothing, and end this URL (`Plan rejected`).
   - Right before the first edit, re-run `git status --porcelain` and confirm it still matches
     8.4's clean baseline. If it no longer does, commit nothing and end this URL
     (`Working tree not clean`).
   - If an earlier URL of this run has already committed fixes, re-check this plan's premises —
     the files it plans to modify and the anchors its Changes cite — against the current tree.
     If those commits have changed them, do not approve the stale plan; re-run `/evaluate-review`
     for this URL alone instead.
   - Re-fetch the PR head with
     `gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'` and compare it with the
     `PR head` SHA Step 3.1 printed. Never re-bind it from the API here: that returns the new
     head, and the comparison would always pass.
     - **Same SHA**, or the fetched SHA is already in HEAD (`git merge-base --is-ancestor <sha> HEAD`)
       → continue.
     - **Otherwise** → commit nothing and end this URL (`PR head moved`).
   - For each **Fix & commit** item, in item order (one item's failure never stops the rest):
     implement that item's section of the approved plan, verify per
     `.claude/etc/per-item-commit.md` §2 (re-measuring first when the plan says so), and commit
     per `.claude/etc/per-item-commit.md` §4.
   - Implementation may be delegated to the `implementer` agent and test writing to the
     `test-writer` agent; both have their own frontmatter pinning `model: opus`, so precision is
     preserved.

#### 8.6 — Fix, verify, commit each item

**Executed in the main conversation** per the hand-off block from 8.5.

#### 8.7 — Closing summary

**Executed in the main conversation** after 8.6. Output a short summary listing, for each item
(its Comment URL), its verdict and its item outcome from the Outcomes table.

#### Multiple top-level URLs

If more than one URL was given, after the last one finishes, output one final cross-URL summary
— in the order the URLs were given — listing each URL and its URL outcome from the Outcomes
table. This is in addition to each URL's own Step 8 output.

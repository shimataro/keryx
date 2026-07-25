---
name: evaluate-review
description: Evaluate a GitHub PR review comment or review summary for technical validity and create an implementation plan if the feedback is sound. Invoke with /evaluate-review <url>.
---

Evaluate a GitHub pull-request review comment or review summary and, if it is technically sound, produce an implementation plan.

## Usage

```text
/evaluate-review <github-pr-review-url>
```

Examples:
```text
/evaluate-review https://github.com/shimataro/keryx/pull/3#discussion_r3618156689
/evaluate-review https://github.com/shimataro/keryx/pull/3#pullrequestreview-2183723456
```

## Steps

### Step 1 — Validate presence of argument
Check whether a URL argument was passed to the skill.

- **If missing**: output the Usage section above and stop immediately.
- **If present**: proceed to Step 2.

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
  and stop.

### Step 3 — Fetch the comment or review
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
- If `state` is `APPROVED` and `body` is null or empty, output that the review is an approval with no actionable feedback and stop.
- **Verify the review belongs to the requested PR.** Extract `pull_request_url` and confirm it matches `https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}`.  
  If it does **not** match, output:
  ```text
  The fetched review does not belong to the requested PR.
  Expected: <expected_url>
  Got:      <pull_request_url>
  ```
  and stop immediately.

### Step 4 — Gather thread context
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

### Step 5 — Gather code context

#### If `review_comment`
1. Use the `commit_id` extracted in Step 3 to load the code exactly as it existed when the comment was left.
2. If the reviewed file exists in the local working tree **and matches `commit_id`**, read it for full context.
3. Otherwise, fetch the file content at `commit_id`:
   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={commit_id}
   ```
   **URL-encode the `{path}` segment** before interpolation (e.g. space → `%20`, `#` → `%23`, `?` → `%3F`) while preserving `/` between directory components. Decode the `content` field from base64 if necessary.
4. Fetch the diff for that commit to understand the change being reviewed:
   ```bash
   gh api repos/{owner}/{repo}/commits/{commit_id} --jq '.files[] | select(.filename == "{path}") | .patch'
   ```
   (The `diff_hunk` from Step 3 already provides the immediate surrounding context.)
5. **Separately**, fetch the current PR head SHA and compare it to `commit_id`:
   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
   ```
   If the head has advanced, briefly check whether the issue noted in the review comment has already been fixed in a later commit. If so, note this in the evaluation reasoning.

#### If `pr_review`
There is no single `path` or `line`. Gather broader context from the PR:
1. Fetch all files changed at `commit_id`:
   ```bash
   gh api repos/{owner}/{repo}/commits/{commit_id} --jq '.files[] | {filename, status, patch}'
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

### Step 6 — Evaluate validity
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

Output the evaluation in this exact format:

```markdown
## Evaluation

**Validity**: [Valid / Invalid / Partially Valid]
**Confidence**: [High / Medium / Low]

### Reasoning
[Your analysis. Cite specific lines or docs where applicable.]
```

### Step 7 — Create implementation plan (only if valid)
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

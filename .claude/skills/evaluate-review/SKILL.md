---
name: evaluate-review
description: Evaluate a GitHub PR review comment for technical validity and create an implementation plan if the feedback is sound. Invoke with /evaluate-review <url>.
---

Evaluate a GitHub pull-request review comment and, if it is technically sound, produce an implementation plan.

## Usage

```
/evaluate-review <github-pr-review-url>
```

Example:
```
/evaluate-review https://github.com/shimataro/keryx/pull/3#discussion_r3618156689
```

## Steps

### Step 1 — Validate presence of argument
Check whether a URL argument was passed to the skill.

- **If missing**: output the Usage section above and stop immediately.
- **If present**: proceed to Step 2.

### Step 2 — Validate URL format
Parse the URL. It must match the pattern of a GitHub PR review-comment link:

```
https://github.com/<owner>/<repo>/pull/<number>#discussion_r<comment_id>
```

- Extract `owner`, `repo`, `pull_number`, and `comment_id`.
- If the URL does **not** match this pattern, output exactly:
  ```
  The provided URL does not appear to be a GitHub PR review comment link.
  ```
  and stop.

### Step 3 — Fetch the review comment
Use the GitHub CLI to retrieve the comment:

```bash
gh api repos/{owner}/{repo}/pulls/comments/{comment_id}
```

- If the request fails (e.g., 404), output the error message and stop.
- From the JSON response, extract:
  - `body` — the review text
  - `path` — file under review
  - `line` / `original_line` — line number
  - `diff_hunk` — diff context provided by GitHub
  - `commit_id` — commit SHA at which the comment was left

### Step 4 — Gather thread context
A review comment is often part of a longer conversation. Reconstruct the thread so the evaluation accounts for prior clarification or rebuttal.

1. Fetch **all** review comments on the PR:
   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number}/comments
   ```
2. Build the reply graph using the `in_reply_to_id` field. Each comment either starts a thread (`in_reply_to_id` is absent) or replies to another.
3. Identify which thread contains the target `comment_id`. Walk the chain backward to collect every preceding comment in that thread, in chronological order.
4. Include this conversation history in your analysis. Pay attention to:
   - Whether earlier rounds already accepted, rejected, or refined the point.
   - Whether the target comment is a follow-up that narrows or shifts the request.
   - Any author push-back or reviewer concession that changes the expected outcome.

### Step 5 — Gather code context
1. Fetch PR metadata to obtain the head commit SHA:
   ```bash
   gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha'
   ```
2. If the reviewed file exists in the local working tree, read it for full context.
3. If the local file is absent or differs, fetch the file content at the PR head commit:
   ```bash
   gh api repos/{owner}/{repo}/contents/{path}?ref={head_sha}
   ```
   Decode the `content` field from base64 if necessary.
4. Also read the PR diff for the file to understand the change being reviewed:
   ```bash
   gh pr diff {pull_number} --repo {owner}/{repo}
   ```

### Step 6 — Evaluate validity
Analyze the review comment against the code and project conventions. Consider:

1. **Accuracy** — Is the factual claim correct? (e.g., a reported bug must actually exist)
2. **Relevance** — Does the comment apply to the code at the stated location?
3. **Constructiveness** — Does it clearly identify a problem or suggest a valid improvement?
4. **Project conventions** — Does it align with the project's documented rules (check `.claude/CLAUDE.md` and `docs/*.md`)?

Output the evaluation in this exact format:

```markdown
## Evaluation

**Validity**: [Valid / Invalid / Partially Valid]
**Confidence**: [High / Medium / Low]

### Reasoning
[Your analysis. Cite specific lines or docs where applicable.]
```

### Step 7 — Create implementation plan (only if valid)
If the evaluation is **Valid** or **Partially Valid** **and** the comment calls for code changes, proceed:

1. Call `EnterPlanMode`.
2. Explore the codebase to understand the surrounding architecture.
3. Write the plan to `~/.claude/plans/evaluate-review-{comment_id}.md` with:
   - **Context** — what the review pointed out and why it matters
   - **Files to modify** — exact file paths
   - **Changes** — concrete, line-level actions
   - **Verification** — how to test (unit tests, manual checks, build)
4. Call `ExitPlanMode` to submit the plan for user approval.

If the review is **Invalid** or no code change is required, state clearly:
```
No implementation plan is needed.
```

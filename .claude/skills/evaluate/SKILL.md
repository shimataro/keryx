---
name: evaluate
description: Dispatch a GitHub URL to the matching evaluation skill — a PR review comment / review summary goes to evaluate-review, an issue (or issue comment) goes to evaluate-issue — and hand the rest of the work, including the final output, entirely to that skill. Accepts multiple URLs. Invoke with /evaluate <url> [<url> ...].
---

# Evaluate

A thin entry point: it only decides which skill handles each URL. Everything after the dispatch —
evaluation, planning, fixes, commits, and the final output — belongs to the dispatched skill.

## Usage

```text
/evaluate <github-url> [<github-url> ...]
```

## Steps

### Step 1 — Validate presence of argument(s)

If no URL was passed, ask the user for one, then continue at Step 2 with it:

```text
Please provide a GitHub PR review or issue URL.
Usage: /evaluate <github-url> [<github-url> ...]
```

### Step 2 — Classify every URL

Classify each URL (space-separated) before dispatching anything:

| Pattern | Dispatch to |
| --- | --- |
| `https://github.com/<owner>/<repo>/pull/<number>#discussion_r<id>` | `evaluate-review` |
| `https://github.com/<owner>/<repo>/pull/<number>#pullrequestreview-<id>` | `evaluate-review` |
| `https://github.com/<owner>/<repo>/issues/<number>` (optionally `#issuecomment-<id>`) | `evaluate-issue` |

`owner` `[A-Za-z0-9-]+`, `repo` `[A-Za-z0-9._-]+`, `number` and `id` `[0-9]+`, each in full.
This is only routing; the dispatched skill performs its own full validation.

If **any** URL matches none of the patterns, dispatch nothing. Print one line per such URL, then
the guidance below, and end:

```text
Unsupported URL: <url>
Please specify a GitHub PR review link (.../pull/<number>#discussion_r<id> or #pullrequestreview-<id>) or an issue link (.../issues/<number>).
```

### Step 3 — Dispatch

Keeping the order the URLs were given, split them into runs of consecutive URLs of the same type.
For each run, in order, invoke the target skill with the `Skill` tool, passing the run's URLs
(space-separated) as `args`, and let it finish before starting the next run.

From the moment a skill is invoked, follow its instructions completely. Add no preface, summary
or output of your own: the dispatched skill's output is this skill's final output.

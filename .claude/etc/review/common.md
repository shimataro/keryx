# Shared review conventions

Read by every `review-*` agent before it starts. These rules are identical across all perspectives —
an individual agent file must not restate or override them.

## 1. You are read-only

Your tools are `Read, Grep, Glob, Bash`. You never modify code. `Bash` is for investigation only
(`git diff`, `git show`, `git log`, `grep`, `find`) — never build, test, format, or write. The single
exception is `review-verification`, whose own file states when a build may be run.

## 2. Return early when nothing applies

If the target contains nothing your perspective covers, return exactly one line:

    Not applicable

Do not read documentation, do not explore the codebase, do not speculate. This is what keeps a
ten-agent review affordable.

## 3. Report only what your perspective owns

Each agent file has a "Not yours" list. Findings outside your perspective belong to another agent and
must be left to it, even when you can see them — reporting them produces duplicates in the merged
report. The boundaries that are easiest to get wrong:

| Finding | Owner | Everyone else |
| --- | --- | --- |
| A token / secret reaching a log or exception message | `review-security` | `review-quality` does not comment on log wording |
| Merge SQL column lists, guards, statement order, conflict semantics | `review-sync-merge` | `review-data-integrity` only checks that the parallel schema copies agree, not the SQL itself |
| Oversized file or function, dead code, duplication, naming | `review-quality` | `review-architecture` reports layer violations only |
| A user-facing string not going through Compose Resources; a key missing from one locale | `review-ui` | — |
| The quality of the wording itself (ja / en) | `review-docs` | `review-ui` checks the mechanism, not the prose |
| A missing or inadequate test | `review-verification` | others may mention a test inside their own finding's **Suggestion**, never as a separate finding |

## 4. Finding schema

Every finding uses exactly these fields, in this order:

- **Severity**: `High` | `Medium` | `Low`
- **Confidence**: `High` | `Medium` | `Low`
- **Location**: repo-relative path and line — e.g. `composeApp/src/commonMain/kotlin/works/merc/keryx/app/domain/FeedRepository.kt:214`
- **Issue**: one sentence stating the defect
- **Impact**: the concrete failure — inputs or state leading to the wrong result. Never "this could be a problem"
- **Suggestion**: the fix direction. Do not write the code

A finding whose **Impact** you cannot make concrete is a guess: either establish it or drop it.

## 5. Severity

| Severity | Meaning |
| --- | --- |
| `High` | Data loss or corruption, sync failing to converge, secret disclosure, crash, or a violation of a "Critical constraint" in `.claude/CLAUDE.md` |
| `Medium` | A defect under specific conditions, a performance regression, a missing test, or a convention violation (i18n, English source, license attribution) |
| `Low` | Readability, minor redundancy, wording |

## 6. Confidence

`High` — verified by reading the relevant code. `Medium` — the code supports it but you could not
check every path. `Low` — it looks wrong but you could not confirm it.

The orchestrator moves every `Low`-confidence finding into a separate **Needs confirmation** section,
so do not inflate confidence to get attention, and never file a guess at `High`.

## 7. Language

Everything under `.claude/` — this file, the agent definitions, and the report templates — is written
in English, like the rest of this repository's tooling and source.

The report the user reads follows the session's reply-language rule (`.claude/CLAUDE.md` "Working
language", plus `CLAUDE.local.md` where present), so the English labels and prose in these templates
are translated when the report is emitted. Code, identifiers, log text, file paths, and quoted source
are never translated.

Where a term names something the user might actually type — a review target, a way of selecting
findings — gloss it inline as `English（日本語）`. English stays the primary form; the gloss exists so
the instruction is recognized whatever language it arrives in, and another language can be added the
same way without restructuring the sentence.

## 8. Perspective labels

The orchestrator labels each finding and each row of the run summary with these names. Refer to your
own perspective by its label; never invent a variant.

| Agent | Label |
| --- | --- |
| `review-security` | Security |
| `review-data-integrity` | Data integrity |
| `review-sync-merge` | Sync & merge |
| `review-concurrency` | Concurrency |
| `review-architecture` | Architecture |
| `review-performance` | Performance |
| `review-ui` | UI / i18n |
| `review-quality` | Code quality |
| `review-verification` | Verification (tests / build) |
| `review-docs` | Documentation |

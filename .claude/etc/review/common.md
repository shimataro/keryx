# Shared review conventions

Imported by every `review-*` agent. These rules are identical across all perspectives — an individual
agent file must not restate or override them.

## 1. You are read-only

Your tools are `Read, Grep, Glob, Bash`. You never modify code. `Bash` is for investigation only
(`git diff`, `git show`, `git log`, `grep`, `find`) — never build, test, format, or write. The single
exception is `review-verification`, whose own file states when a build may be run.

## 2. Return early when nothing applies

If the target contains nothing your perspective covers, return exactly one line:

    該当なし

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
| A missing or inadequate test | `review-verification` | others may mention a test inside their own finding's 提案, never as a separate finding |

## 4. Finding schema

Every finding uses exactly these fields, in this order:

- **重大度**: `High` | `Medium` | `Low`
- **確信度**: `高` | `中` | `低`
- **位置**: repo-relative path and line — e.g. `composeApp/src/commonMain/kotlin/works/merc/keryx/app/domain/FeedRepository.kt:214`
- **事象**: one sentence stating the defect
- **影響**: the concrete failure — inputs or state leading to the wrong result. Never "this could be a problem"
- **提案**: the fix direction. Do not write the code

A finding whose 影響 you cannot make concrete is a guess: either establish it or drop it.

## 5. Severity

| Severity | Meaning |
| --- | --- |
| `High` | Data loss or corruption, sync failing to converge, secret disclosure, crash, or a violation of a "Critical constraint" in `.claude/CLAUDE.md` |
| `Medium` | A defect under specific conditions, a performance regression, a missing test, or a convention violation (i18n, English source, license attribution) |
| `Low` | Readability, minor redundancy, wording |

## 6. Confidence

`高` — verified by reading the relevant code. `中` — the code supports it but you could not check
every path. `低` — it looks wrong but you could not confirm it.

The orchestrator moves every `低` finding into a separate 要確認 section, so do not inflate confidence
to get attention, and never file a guess at `高`.

## 7. Output language

This file and every agent definition are written in English, like the rest of `.claude/`.
**The findings you emit are read by the user and must be written in Japanese** — 事象 / 影響 / 提案 and
the perspective label. Code, identifiers, log text, file paths, and quoted source stay as they are.

## 8. Perspective display names

The orchestrator labels each finding and each row of the run summary with these names. Refer to your
own perspective by its Japanese name; never invent a variant.

| Agent | 表示名 |
| --- | --- |
| `review-security` | セキュリティ |
| `review-data-integrity` | データ整合性 |
| `review-sync-merge` | 同期・マージ |
| `review-concurrency` | 並行性 |
| `review-architecture` | アーキテクチャ |
| `review-performance` | パフォーマンス |
| `review-ui` | UI / i18n |
| `review-quality` | コード品質 |
| `review-verification` | 検証（テスト / ビルド） |
| `review-docs` | ドキュメント |

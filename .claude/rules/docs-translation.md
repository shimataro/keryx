---
paths:
  - "docs/**/*.md"
---

# Rule: `docs/*.md` and `docs/*.ja.md` are updated together

Every English design doc under `docs/` has a Japanese twin (`docs/foo.md` ⇄
`docs/foo.ja.md`), and the two are kept in lockstep — a change to one is **not complete**
until the same change lands in the other. Both are checked into the repo; the `.ja.md`
files are translations maintained for readers, not a stale mirror that may drift.

This applies in both directions and to every file in the set (`README`, `setup`,
`external-spec`, `build`, `app-architecture`, `error-design`, `db-schema`,
`sync-architecture`, `background-update`, `testing`, `known-issues`), including
`docs/README.md`'s index table when a document is added or renamed.

It has already been missed twice — `docs/testing.md` was updated with no matching
`.ja.md` change in commits `2c44663` and `29a55cf` — so treat it as a checklist item,
not a nicety: after editing any `docs/*.md`, edit its counterpart in the same turn, and
say so in the summary. Keep the two structurally parallel (same headings, same order,
same tables) so a later diff between them stays readable.

Source code, log/exception text and identifiers stay English regardless
(`.claude/CLAUDE.md` constraint #9); this rule is only about the documentation pair.

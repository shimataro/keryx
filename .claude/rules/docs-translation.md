---
paths:
  - "docs/**/*.md"
---

# Rule: `docs/*.md` and their translations are updated together

Every English design doc under `docs/` has a translation for each documentation locale
(`docs/foo.md` ⇄ `docs/foo.<lang>.md` — today only Japanese, `docs/foo.ja.md`), and they are kept
in lockstep — a change to one is **not complete** until the same change lands in every other. All
are checked into the repo; the translations are maintained for readers, not a stale mirror that may
drift. Don't assume the set is Japanese only: list it with `ls docs/foo.*.md` and update every file
it shows.

This applies in every direction and to every file in the set (`README`, `setup`,
`external-spec`, `build`, `app-architecture`, `error-design`, `db-schema`,
`sync-architecture`, `background-update`, `testing`, `known-issues`), including
`docs/README.md`'s index table when a document is added or renamed. Adding a documentation locale
means adding its translation of every file in the set, `docs/README.md` included.

It has already been missed twice — `docs/testing.md` was updated with no matching
`.ja.md` change in commits `2c44663` and `29a55cf` — so treat it as a checklist item,
not a nicety: after editing any `docs/*.md`, edit all its translations in the same turn, and
say so in the summary. Keep them structurally parallel (same headings, same order,
same tables) so a later diff between them stays readable.

Source code, log/exception text and identifiers stay English regardless
(`.claude/CLAUDE.md` constraint #9); this rule is only about the documentation set.

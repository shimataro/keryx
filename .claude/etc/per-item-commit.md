# Applying approved items one commit each

Read by any skill that applies a user-approved list of changes — review fixes, performance items,
refactor batches — and commits each one on its own. The caller decides **what an item is**, which
tests it must keep green, and any extra checks; this file defines **how** each item is approved,
verified, reverted and committed.

## 1. Approval and branch

- The caller's approval gate is the **one-time run confirmation**: every approved item proceeds
  through apply → verify → commit with no further per-item approval. This is what satisfies the
  repo's "don't commit unless asked" convention.
- Before the first commit, if the current branch is a version branch (`v*`), run
  `/feature-branch <type>` (the caller names `<type>`) to create and switch to a feature branch.
- The tree must have no unrelated uncommitted changes when the first item starts; that clean
  state is the **baseline** §3 returns to. The caller says where it checked.

## 2. Verify

Run the caller's tests for the item. In addition, the JVM build never compiles the Apple code, so
when an item touches:

- `shared/src/commonMain` (including `sqldelight/`), `shared/src/commonTest`, `shared/src/apple*`,
  `shared/src/macos*` or `shared/src/ios*`, also run `./gradlew :shared:macosArm64Test` — these
  source sets also compile for Kotlin/Native, and `commonTest` runs natively too. When it touches
  `shared/src/ios*`, run `:shared:iosSimulatorArm64Test` as well;
- `appleApp/`, also run `xcodebuild -scheme Keryx -destination 'platform=macOS' test`.

Off a Mac, an item touching an Apple-only source set (`apple*`, `macos*`, `ios*`) or `appleApp/`
cannot be verified: treat it as failed (§3), not as passed. An item touching only `commonMain` /
`commonTest` is judged on the caller's tests there, and the caller's closing summary records
"Apple targets not verified locally" for it (CI runs the Apple tests on every push).

If the caller used temporary instrumentation for this item, remove it per
`.claude/etc/temporary-instrumentation.md` §3–§4 **whatever the outcome**, before anything else.

## 3. Revert on failure

When the item fails verification and the caller's own retry (if any) cannot fix it:

1. Make sure no temporary instrumentation remains (the marker check in
   `.claude/etc/temporary-instrumentation.md` §3 prints nothing).
2. Confirm via `git status` / `git diff` that only this item's changes are present, so the revert
   cannot destroy other work in the same file.
3. **Revert that one item** — `git restore -- <files>` for the tracked files it changed, and delete
   any untracked file it created — and confirm `git status --porcelain` is back to the baseline,
   so nothing of it reaches the next item.
4. Make **no commit** for it. Record the reason for the caller's closing summary and move on to
   the next item — one item's failure never stops the rest.

## 4. Commit

1. Review `git status --porcelain` / `git diff`, then run the marker check from
   `.claude/etc/temporary-instrumentation.md` §3 and confirm it prints nothing **before staging
   anything**.
2. Stage **only the files, and only the hunks, belonging to this item** (including anything the
   caller says rides along, such as a doc fix). Verify the staged diff contains nothing beyond
   this item, so an unrelated edit or residue from an earlier item's revert never rides along.
3. Create **exactly one commit** with a **single-line** message only (no body, no trailer), in
   the repo's Conventional Commits style. Never batch several items into one commit, never amend
   a commit made earlier in the same run, and never push.

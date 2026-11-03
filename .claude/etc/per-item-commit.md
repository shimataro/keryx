# Applying approved items one commit each

Read by any skill that applies a user-approved list of changes — review fixes, performance items,
refactor batches — and commits each one on its own. The caller decides **what an item is**, which
tests it must keep green, and any extra checks; this file defines **how** each item is approved,
verified, reverted and committed.

## 1. Approval and branch

- The caller's approval gate is the **one-time run confirmation**: every approved item proceeds
  through apply → verify → commit with no further per-item approval. This is what satisfies the
  repo's "don't commit unless asked" convention.
- Before the run's first commit, if the current branch is a version branch (`v*`), §4 moves the
  work onto a feature branch with `/feature-branch`.
- The tree must have no uncommitted changes when the first item starts — `git status --porcelain`
  prints nothing, untracked files included; that clean state is the **baseline** §3 returns to.
  The caller says where it checked.

## 2. Verify

If the caller re-measures this item with temporary instrumentation, do that first, then remove it
per `.claude/etc/temporary-instrumentation.md` §3–§4 **whatever the outcome**, before running the
tests below — they must run on the item's change alone.

Run the caller's tests for the item. In addition, the JVM build never compiles the Apple code, so
when an item touches:

- `shared/src/commonMain` (including `sqldelight/`), `shared/src/commonTest`, `shared/src/apple*`,
  `shared/src/macos*`, `shared/src/ios*`, `testing/src/commonMain`, or the build scripts that
  configure those compilations (`shared/build.gradle.kts`, `testing/build.gradle.kts`,
  `gradle/libs.versions.toml`), also run
  `./gradlew :shared:macosArm64Test :shared:iosSimulatorArm64Test` — these source sets compile for
  both Kotlin/Native targets and `commonTest` runs natively on both (with the `testing/` fakes it
  depends on), while `macosMain` and `iosMain` hold separate actuals, so a change can break one
  target while the other still passes;
- `appleApp/`, also run
  `(./gradlew :shared:assembleKeryxSharedReleaseXCFramework :composeApp:generateStringCatalog && cd appleApp && xcodegen generate && xcodebuild -scheme Keryx -destination 'platform=macOS' test)`
  — in a subshell, so later commands still run from the repo root. The Gradle step comes first
  because `xcodegen generate` and Xcode's build planning both need its outputs, which a fresh
  checkout lacks (`docs/build.md`). The `.xcodeproj` is a
  gitignored artifact that XcodeGen writes with every source file listed, so one left over from an
  earlier generate can miss a `project.yml` change or an added, removed or renamed file and still
  pass.

Off a Mac, an item touching an Apple-only source set (`apple*`, `macos*`, `ios*`) or `appleApp/`
cannot be verified: treat it as failed (§3), not as passed. An item touching only `commonMain` /
`commonTest`, `testing/src/commonMain` or those build scripts is judged on the caller's tests there, and the caller's closing summary records
"Apple targets not verified locally" for it (CI runs the Apple tests on every push).

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
2. If this is the run's first commit and the current branch is a version branch (`v*`), run
   `/feature-branch` now, with this item's changes still uncommitted. The skill infers the branch
   name from those changes, which do not exist yet at the start of the run; `git switch -c` carries
   them onto the new branch. Do not run it before the first item has been applied and verified.
3. Stage **only the files, and only the hunks, belonging to this item** (including anything the
   caller says rides along, such as a doc fix). Verify the staged diff contains nothing beyond
   this item, so an unrelated edit or residue from an earlier item's revert never rides along.
4. Create **exactly one commit** in the repo's Conventional Commits style. The message is a
   single-line subject (`<type>(<scope>): <subject>`) with no body; the only addition allowed is
   the attribution trailer that the harness or the user's instructions require. Never batch several
   items into one commit, never amend a commit made earlier in the same run, and never push.

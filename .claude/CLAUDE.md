# Keryx (Kotlin Multiplatform)

A cross-platform RSS reader (Kotlin Multiplatform / Compose Multiplatform).
Local-first, Dropbox / Google Drive sync, no account required.

It targets desktop (Windows/macOS/Linux) via Compose Multiplatform first, with
mobile (Android/iOS) targets planned for later.

## Working language

**Output language**: Always reply to the user in the language of their **most recent**
message — switch immediately if they switch languages mid-conversation. If the latest
message alone doesn't clearly signal a language (e.g. it's just a URL, file path, command,
or very short), infer it from earlier messages in this conversation, or from what you
already know about the user's usual language, before defaulting to English. This applies to
the entire reply (explanations, summaries, headers, and Plan Mode plans), not just an
opening/closing line. Quoting or discussing English source code, logs, or documentation is
expected and does not change what language your own prose is written in — do not let it
leak into your reply's language. Source code, log/exception text, and identifiers
themselves stay in English regardless (see constraint #9 below).

## Documentation

- @../docs/external-spec.md — External spec & technology choices
- @../docs/app-architecture.md — App structure & key classes
- @../docs/error-design.md — Error design & Result type
- @../docs/db-schema.md — DB schema & local_settings
- @../docs/sync-architecture.md — Sync architecture
- @../docs/background-update.md — Background update
- @../docs/build.md — Build & packaging
- @../docs/setup.md — Development environment setup
- @../docs/testing.md — Testing conventions
- @../docs/known-issues.md — Known defects deliberately left unfixed (with the evidence, so
  investigations aren't repeated)
- UI/Compose style guidelines → the **`ui-guidelines` skill** (invoke it when
  adding/modifying Compose under `ui/`: pane tones, divider policy, article
  card style, flat native-feel components, dialog/popup conventions)
- **`README.md` is user-facing only.** It must not mention frameworks,
  languages, libraries, or directory structure — that architecture/implementation
  detail belongs in the `docs/*.md` files above. If a technical detail seems
  worth adding to the README, put a user-facing summary and a link there
  instead, and write the detail in the relevant `docs/` file.

## Commands

```bash
./gradlew build                      # Compile all source sets + run tests
./gradlew :composeApp:desktopTest    # Run tests only
./gradlew :composeApp:run            # Run the desktop app
./gradlew :composeApp:packageDmg     # Package (macOS; use packageMsi/packageDeb on Windows/Linux)
```

## Branching

When a turn has changed tracked files while the current branch is a version branch
(`v0`, `v1`, … — anything matching `v*`, the default/integration branch), do not
leave the edits on that branch. After making the changes, offer a few candidate
branch names (best first) as choices via the `AskUserQuestion` tool. Once the user
picks one — or supplies their own via "Other" — create that branch and switch to it
(`git switch -c <name>` / `git checkout -b <name>` carries the uncommitted changes
along — no stash needed); the user's selection is the agreement to create it. Never
create or switch branches without the user's selection or explicit name.

Branch names follow the repo convention `<type>/<kebab-summary>`, where `<type>` is
the Conventional Commits type of the change (`feat`, `fix`, `refactor`, `docs`,
`chore`, `build`, `test`, `ci`, `perf`, `style`), e.g. `feat/m17n-english`,
`refactor/split-oversized-ui-panes`. Candidates follow this convention. Once on a
feature branch (i.e. not `v*`), this rule no longer triggers.

## Commit messages

After implementing a plan that changes source code, output a concise commit
message in English for the resulting diff (`git diff`). Do not commit unless
asked. Follow the repo's Conventional Commits style (`fix(scope): ...`,
`docs(scope): ...`, etc.).

## Bug fixes

When fixing a bug, prefer the **root-cause fix over the smallest diff**. A change
that resolves the underlying cause is preferred over a small change that only
papers over the symptom (a stopgap / workaround) — do not optimize for minimal
change at the expense of actually fixing the problem. If only a workaround is
feasible (the root cause is external, or a proper fix is genuinely out of scope),
say so explicitly and note what the real fix would be, rather than presenting the
workaround as a complete fix. (This concerns *bug fixing*; behavior-preserving
refactors and perf work still follow the small, independently-revertible-batch
discipline of the `refactor` / `perf-tune` skills.)

## Architecture

Layered: UI (Compose) → ViewModel (androidx.lifecycle + Koin) → Repository → DataSource (SQLDelight / Ktor)

```text
composeApp/src/
├── commonMain/kotlin/works/merc/keryx/app/
│   ├── core/       # Constants, error types, Result, date parsing, Clock
│   ├── data/       # DataSource (SQLDelight / FeedFetcher / CloudStorage / OPML)
│   ├── domain/     # Repositories + sync (CloudSession, SyncRepository, MergeSql)
│   ├── platform/   # expect declarations for platform-specific code
│   ├── di/         # Koin modules
│   └── ui/         # Compose screens + ViewModels + theme + i18n
├── commonMain/sqldelight/       # .sq schema + queries
├── commonMain/composeResources/ # values/strings.xml (i18n), drawable (tray icons)
├── commonTest/                  # pure + MockEngine (Ktor) tests
├── desktopMain/kotlin/…/        # actual platform implementations + main.kt
└── desktopTest/                 # SQLDelight (in-memory / file) DB tests
```

The package root is `works.merc.keryx.app` (reverse-DNS of `keryx.merc.works`).

## Critical constraints — DO NOT violate without explicit user approval

1. **`articles_fts` is runtime-only (`FtsManager`, raw SQL) — never in a `.sq`
   file, and the live table is never DROPped.** Hot paths index incrementally
   (`indexMissing()`), never a full `'rebuild'`; the upload excludes it via a
   `VACUUM INTO` snapshot copy so concurrent searches never hit `no such table`.
   Full mechanism (daily rebuild heal, `ensureIndexed`, `busy_timeout`) →
   `.claude/rules/fts-index.md` (auto-loads when you touch FTS / sync / driver code).
2. **The ATTACH-DATABASE merge runs through `platform/DatabaseMerger`, NOT the
   SQLDelight driver** (the JVM `JdbcSqliteDriver` opens a fresh connection per
   statement, so an `ATTACH` wouldn't survive to the next merge statement).
   Details → `.claude/rules/sync-merge.md`.
3. **No hardcoded user-facing strings.** All UI text goes through Compose
   Multiplatform resources (`composeResources/values/strings.xml`). Japanese is
   the only shipped locale for now, but the mechanism must be used for every
   string a user can see — including tray/notification text built outside
   composition (see `NotificationMessages` + `getString`).
4. **Platform-specific code stays behind `commonMain` `expect` declarations**
   (`AppDirs`, `FileIO`, `BrowserOpener`, `FilePicker`, `DatabaseDriverFactory`,
   `DatabaseMerger`, `Pkce`, `CloudStorageAvailability`, `platformModule`).
   Desktop implementations live in `desktopMain`. This keeps the door open for
   Android/iOS targets later.
5. **Follow the design docs.** Do not change the sync algorithm, merge SQL
   semantics, error taxonomy, or feature scope on your own judgment. If
   something in the docs seems wrong, ask before deviating.
6. **Riverpod does not exist here** — state management is androidx.lifecycle
   ViewModels resolved via Koin.
7. **New features and bug fixes must come with tests.** Except for UI-only
   changes with no runtime logic (visual tweaks, layout), new or changed logic
   in `domain/`, `data/`, or ViewModels needs a corresponding test in
   `commonTest/`/`desktopTest/`. See @../docs/testing.md for conventions;
   delegate the actual writing to the `test-writer` agent if useful.
8. **Third-party libraries that require license attribution go in
   `THIRD-PARTY-LICENSES.md`** (repo root). When adding or removing a shipped
   runtime dependency in `gradle/libs.versions.toml` whose license requires
   attribution (Apache-2.0, MIT, BSD, etc.), add/remove its row (library name /
   SPDX / project URL) there too — keep the two in sync. This applies equally to
   **bundled third-party resources that are not Gradle dependencies** (e.g. icon
   assets under `composeResources/`) whose license requires attribution: add/remove
   their row on the same event. Test-only dependencies
   are excluded. This file is surfaced in-app via the About dialog's
   Open Source Licenses link (`LICENSES_URL` in `ui/settings/AboutLicenses.kt`),
   so it is the single source of truth — there is no hardcoded Kotlin list.
9. **Source code (comments, KDoc, log/exception messages, non-UI string
   literals) must be written in English**, regardless of the language used
   in conversation with the user (this file and `docs/*.md` are themselves
   in English — see "Working language" above for reply language; `docs/*.ja.md`
   are Japanese translations for readers, and UI text is Japanese-only per
   constraint #3). Exceptions: (a) UI-facing strings
   still go through Compose Resources per #3 and stay Japanese there; (b)
   test assertions that must match actual rendered UI text (e.g.
   `onNodeWithText("...")` against a real `strings.xml` string) legitimately
   contain the Japanese string being asserted against — that's not a
   violation.

## Environment

- Kotlin 2.4.10 / Compose Multiplatform 1.11.1 / Gradle 9.6.1
- **Requires JDK 25 or later** as the JVM that launches `./gradlew` (i.e.
  `JAVA_HOME`). Compilation uses a JDK 25 toolchain auto-provisioned by the
  foojay-resolver plugin, but `:composeApp:run` executes with whatever JVM
  launched Gradle — if that's older than 25, you'll hit `UnsupportedClassVersionError`.
- SQLDelight 2.3.2, sqlite-jdbc 3.53.2.0, Ktor 3.5.1, Koin 4.2.2, coroutines 1.11.0
- Config cache is disabled (the `generateBuildConfig` task isn't cache-safe yet).

# Keryx (Kotlin Multiplatform)

A cross-platform RSS reader (Kotlin Multiplatform / Compose Multiplatform).
Local-first, Dropbox sync, no account required.

It targets desktop (Windows/macOS/Linux) via Compose Multiplatform first, with
mobile (Android/iOS) targets planned for later.

## Working language

**Output language**: Always reply to the user **in the language they used in their request**.

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

1. **`articles_fts` is never part of the SQLDelight-managed schema.** It is
   created/maintained at runtime via `FtsManager` (raw SQL on the driver). Do not
   add it to a `.sq` file. The **live table is never dropped**: the sync flow
   excludes it from the uploaded file by building a `VACUUM INTO` snapshot copy
   (`platform/DatabaseSnapshot`) and dropping it there, so a concurrent search
   never hits `no such table`. Hot paths (feed refresh, sync merge) index new
   rows incrementally via `FtsManager.indexMissing()` — never a full `'rebuild'`,
   which is O(all indexed text) and would block/zero-out concurrent searches. The
   whole index is only rebuilt in the rare healing pass: a once-per-24h idle pass
   in `main.kt` (`maybeRebuildFtsIndex`, gated on `lastFtsRebuiltAt` +
   `ActivityCenter` idle), which also re-indexes content that incremental indexing
   left stale and sweeps entries left by cache-cleanup deletions. On startup, `FtsManager.ensureIndexed()`
   creates the table on first run and backfills any missing rows. `busy_timeout`
   (set in `DatabaseDriverFactory`) lets a search wait out, rather than error on,
   the brief write lock of an incremental insert or a rebuild.
2. **The ATTACH-DATABASE merge runs through `platform/DatabaseMerger`, NOT the
   SQLDelight driver.** SQLDelight's JVM `JdbcSqliteDriver` opens a fresh
   connection per statement for file DBs, so an `ATTACH` on one call is invisible
   to the merge statements on the next. `DatabaseMerger` does the whole
   attach → version-check → merge → detach on a single dedicated JDBC connection.
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
   SPDX / project URL) there too — keep the two in sync. Test-only dependencies
   are excluded. This file is surfaced in-app via the About dialog's
   Open Source Licenses link (`LICENSES_URL` in `ui/settings/AboutLicenses.kt`),
   so it is the single source of truth — there is no hardcoded Kotlin list.
9. **Source code (comments, KDoc, log/exception messages, non-UI string
   literals) must be written in English**, regardless of the language used
   elsewhere in the project (this file and `docs/*.md` are Japanese; UI text
   is Japanese-only per constraint #3). Exceptions: (a) UI-facing strings
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

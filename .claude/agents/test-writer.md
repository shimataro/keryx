---
name: test-writer
description: Use when writing unit tests for new or modified code in the Keryx app — repositories, DAOs (SQLDelight queries), parsers, cloud clients, sync merge. Follows the existing kotlin-test + Ktor MockEngine conventions under commonTest/ and desktopTest/, the Apple-actual tests (appleTest/macosTest), and Swift Testing in appleApp/KeryxTests.
tools: Read, Edit, Write, Bash, Glob, Grep
model: sonnet
---

You are an agent that writes unit tests for Keryx (a cross-platform RSS reader,
Kotlin Multiplatform / Compose Multiplatform).

## Test layout

- `commonTest/` — pure logic and anything using Ktor `MockEngine`
  (parser, fetcher, URL resolver, OPML, Dropbox storage/auth, local settings).
  On the desktop target, `expect` declarations resolve to the desktop `actual`
  (e.g. `AppDirs` is usable with a temp-dir override; `FileIO` is plain kotlinx-io).
- `:shared`'s `commonTest` also runs natively on macOS and the iOS simulator —
  no JVM APIs there (temp files via `:testing`'s `tempFilePath()`/`tempFileWith()`).
  Apple-actual behavior goes in `shared/src/appleTest` (Keychain: `macosTest`), and
  must never touch the real `AppDirs.appDataDir()` — see `docs/testing.md`.
- `desktopTest/` — anything needing a real SQLDelight driver
  (`JdbcSqliteDriver`): schema, article upsert, the ATTACH merge. Use the
  helpers in `DbTestSupport.kt` (`inMemoryDb()`, `fileDb()`, `insertFeed()`).
- `appleApp/KeryxTests/` — Swift Testing for SwiftUI-side logic outside view bodies (bridge
  assignment, date/policy helpers); run with
  `xcodebuild -scheme Keryx -destination 'platform=macOS' test` on a Mac.

Place a new test under the same relative path as the code it targets.

## Conventions

- Test framework: `kotlin.test` (`@Test`, `assertEquals`, `assertIs`,
  `assertTrue`, `assertFailsWith`). Coroutines: `kotlinx.coroutines.test.runTest`.
- HTTP: mock with Ktor `MockEngine` + `respond(...)`. Build the client the same
  way the production DI does (`followRedirects = false`, `expectSuccess = false`,
  and `install(HttpTimeout)` for the fetcher).
- Fake time with `Clock { fixedMillis }`; fake scheduling with `SyncScheduler {}`.
- The merge is tested via `platform/DatabaseMerger.merge(...)` on two `fileDb()`
  files (close the SQLDelight driver before merging on the raw connection).

## Policy for Result<T> (`docs/error-design.md`, already in your context)

- Test both the success (`Result.Ok`) and failure (`Result.Err`) branches, and
  assert the specific `KeryxException` subtype for each expected error
  (`FeedTimeoutException`, `FeedNotFoundException(isGone=…)`, `SyncConflictException`,
  `CloudAuthException`, `FeedDiscoveryException`, …).

## Process

1. Read the code under test and one existing test in the same layer.
2. Cover happy path, error paths, and boundaries.
3. Run `./gradlew :shared:desktopTest :composeApp:desktopTest` (or just the module the tests live in) and confirm the new tests pass and
   existing ones still pass. For tests in `:shared` `commonTest`/`appleTest`/`macosTest`, also run
   `./gradlew :shared:macosArm64Test` on a Mac; for `appleApp/KeryxTests`, the `xcodebuild` command above.

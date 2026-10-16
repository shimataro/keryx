---
name: build
description: Use to verify changes in the Keryx KMP app by running the full build → test pipeline in one step, or when SQLDelight-generated code needs to be regenerated after a .sq file change. Invoke explicitly with /build.
---

Run Keryx KMP's standard build flow in sequence.

## Steps

1. If any `.sq` file changed, regenerate the SQLDelight interface explicitly first:
   ```bash
   ./gradlew :shared:generateCommonMainKeryxDatabaseInterface
   ```
2. Run the full build (compiles all source sets, runs `check`):
   ```bash
   ./gradlew build
   ```
   If there are compile errors or warnings you introduced, report the affected
   file, line, and message, and stop here.
3. Run tests explicitly (also part of step 2's `check`, but useful to isolate):
   ```bash
   ./gradlew :shared:desktopTest :composeApp:desktopTest
   ```
   If any tests fail, summarize the failing test names and the reason (assertion
   diffs / exceptions). Pay special attention to `SchemaTest` and
   `SyncMergerTest` — failures there mean the DB schema or merge SQL regressed.
4. Optionally smoke-test the app launches:
   ```bash
   ./gradlew :composeApp:run
   ```
   (Blocks until the window closes — only run when asked to verify UI manually.)
5. **Apple targets — on a Mac only, when the change touches `shared/src/apple*`,
   `:shared` `commonMain`/`commonTest` code (which also compiles for Kotlin/Native), or
   `appleApp/`.** Steps 2–3 run on the JVM and never compile the Apple targets:
   ```bash
   ./gradlew :shared:macosArm64Test :shared:iosSimulatorArm64Test
   ```
   and, when `appleApp/` changed (run `cd appleApp && xcodegen generate` first if
   `project.yml` changed):
   ```bash
   xcodebuild -scheme Keryx -destination 'platform=macOS' test
   ```
   Off a Mac, skip this step and say so in the report rather than reporting it as passed.

## Notes

- **JDK 25+ must be the JVM launching Gradle** (`JAVA_HOME`). The compile
  toolchain (JDK 25) is auto-provisioned by the foojay-resolver plugin, but
  `:composeApp:run` and other JavaExec tasks use the launching JVM. If a sandbox
  blocks toolchain download, pass `-Dorg.gradle.java.installations.auto-download=true`.
- Configuration cache is disabled in `gradle.properties` (the `generateBuildConfig`
  task isn't config-cache-safe). Don't re-enable it without verifying that task.
- Cloud API keys are build-time inputs, each resolved as
  `-P<prop>` > env var > `local.properties` (git-ignored) > empty:
  Dropbox (`dropboxAppKey` / `DROPBOX_APP_KEY` / `dropbox.app.key`), Google Drive
  (client id **and** secret), OneDrive (`onedrive.client.id`). An empty key hides
  that provider from the UI entirely — it is a feature, not a build failure.
  The Google Drive client id + secret are for desktop; the Apple app has its own
  client id (`googleDriveAppleClientId` / `GOOGLE_DRIVE_APPLE_CLIENT_ID`). Android has
  no Google Drive key at all: it goes through Play services' `AuthorizationClient`, so
  its availability there is a runtime check for Play services on the device. See
  `docs/build.md` for how to obtain each one; `local.properties.example` lists the
  exact property names.

## How to report

- Briefly indicate whether each step succeeded or failed.
- On failure, quote only the relevant excerpt that points to the cause.
- If all steps succeed, report in one line that "build and tests all succeeded".

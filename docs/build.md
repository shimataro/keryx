# Build & Packaging

[日本語](build.ja.md)

## Requirements

- **JDK 25 or later** (`JAVA_HOME`, the JVM that launches `./gradlew`).
  The JDK 25 compilation toolchain is auto-provisioned by Gradle's foojay-resolver.
  However, JavaExec tasks such as `:composeApp:run` are executed with the JVM that launched Gradle, so if it is older than 25 you will hit `UnsupportedClassVersionError` at runtime.
- Use the bundled wrapper (`./gradlew`, Gradle 9.6.1).

If toolchain auto-download is blocked in a sandbox:
`./gradlew -Dorg.gradle.java.installations.auto-download=true ...`.

## Build & Run

```bash
./gradlew build                    # Compile all source sets + run tests
./gradlew :composeApp:desktopTest  # Tests only
./gradlew :composeApp:run          # Launch the desktop app
```

## Dropbox App Key (`DROPBOX_APP_KEY`)

Implemented via a Gradle custom task (`generateBuildConfig`). Priority:

1. `-PdropboxAppKey=...` (Gradle property)
2. Environment variable `DROPBOX_APP_KEY`
3. `local.properties` `dropbox.app.key` (not in git; see `local.properties.example`)
4. Empty string

The generated `works.merc.keryx.app.BuildConfig.DROPBOX_APP_KEY` being empty causes
`CloudStorageAvailability.dropboxAvailable` to return `false`, completely hiding Dropbox integration from the Setup / Settings screens.

## Google Drive Client ID / Secret (`GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET`)

Also generated via the Gradle custom task (`generateBuildConfig`).
In Google Cloud Console, create an **"Desktop app" type OAuth client**, and use its client ID and client secret (Google's desktop client does not allow arbitrary custom schemes; redirects must be received at `http://127.0.0.1:<port>`. The app uses `LoopbackRedirectTransport` to stand up a temporary HTTP server to receive it). The flow uses PKCE (`code_verifier`), but **the client secret is also required separately** — unlike iOS/Android, Google's "Desktop app" OAuth client is not treated as a full public client, and Google's token endpoint rejects token exchange / refresh without `client_secret` with `invalid_request: client_secret is missing` (regardless of PKCE). The scope requested is `drive.appdata` only (an app-specific hidden folder in the user's Drive). During development, set the OAuth consent screen to "Testing" and register test users.

Client ID priority:

1. `-PgoogleDriveClientId=...` (Gradle property)
2. Environment variable `GOOGLE_DRIVE_CLIENT_ID`
3. `local.properties` `googledrive.client.id` (not in git; see `local.properties.example`)
4. Empty string

Client secret priority (same rules):

1. `-PgoogleDriveClientSecret=...`
2. Environment variable `GOOGLE_DRIVE_CLIENT_SECRET`
3. `local.properties` `googledrive.client.secret` (not in git)
4. Empty string

If either generated `works.merc.keryx.app.BuildConfig.GOOGLE_DRIVE_CLIENT_ID` or `GOOGLE_DRIVE_CLIENT_SECRET` is empty, `CloudStorageAvailability.googleDriveAvailable` returns `false`, completely hiding Google Drive integration from the Setup / Settings screens.

## Packaging

Created under [`composeApp/build/compose/binaries/main`](./composeApp/build/compose/binaries/main).

Only the platform matching the execution platform can be built (cross-compilation is not supported).

```bash
# Execution-platform-dependent run folder
./gradlew :composeApp:createDistributable

# macOS
./gradlew :composeApp:packageDmg

# Windows
./gradlew :composeApp:packageMsi

# Linux
./gradlew :composeApp:packageDeb
./gradlew :composeApp:packageRpm
```

App icons are at `composeApp/icons/{keryx.icns, keryx.ico, keryx.png}`. Tray icons are at
`composeApp/src/commonMain/composeResources/drawable/tray_icon*.png`. These are generated from shared artwork via
`design/icons/make_desktop_icons.sh` (it is preferable to commit generated files).

> **macOS Dropbox linking confirmation**: The custom URI `keryx://` is routed by macOS LaunchServices to the packaged app, so `./gradlew :composeApp:run` cannot complete the link. To verify linking behavior, build with `createDistributable` and launch `Keryx.app` (see [setup.md](setup.md) "Common Issues" for details).

## Release (CD)

`.github/workflows/release.yml` builds the package and attaches it to the GitHub Release.
**macOS only for now** (cross-compilation is not supported, so each additional platform needs its own runner).

Flow:

1. Publish a GitHub Release with a `vMAJOR.MINOR.PATCH` tag (e.g. `v0.1.0`).
2. The workflow triggers on `release: published`, strips the leading `v`, and passes the result as `-PappVersion`.
3. `:composeApp:packageDmg` runs, and the DMG is attached to the Release as `Keryx-<version>-macos-arm64.dmg`.

The **tag is the single source of truth for the version**. `appVersion` in `composeApp/build.gradle.kts` resolves
`-PappVersion` > `APP_VERSION` env var > the literal in the file, so the tag drives both `BuildConfig.VERSION`
(shown in the About screen) and the native-distribution `packageVersion` — they cannot disagree. Local builds fall
through to the literal, so nothing changes for day-to-day development. A tag that does not yield a
jpackage-compatible version (`MAJOR[.MINOR[.PATCH]]`) fails the workflow early with an explicit message.

`macos-latest` runners are arm64, hence the architecture in the artifact name — it leaves room for an x86_64 or
universal build alongside it later.

### 0.x versions on macOS

jpackage refuses a macOS `--app-version` whose first component is `0` (it enforces the CFBundleVersion rule that
versions start at 1), and it fails `createDistributable` — not just the DMG step — so a `0.x` release would
otherwise be impossible to package at all. jpackage has no separate `--mac-app-version` input, and the Compose
plugin's own validation does not cover this, so it cannot be configured away.

`composeApp/build.gradle.kts` works around it: when the major version is `0`, macOS is packaged under the
placeholder `1.0.0` (`macOsPackageVersion`, applied via `macOS { packageVersion }` only — deb/rpm/msi accept `0.x`
and are left alone), and `restoreMacOsShortVersion` rewrites `CFBundleShortVersionString` back to the real version
in `createDistributable`'s `doLast`. A major version of 1 or higher is passed through untouched.

The net effect for `0.1.1`: the tag, `BuildConfig.VERSION` (About screen), the update checker, and the version
Finder shows are all `0.1.1`. Only `CFBundleVersion` keeps the `1.0.0` placeholder, which is an internal build
identifier that never surfaces. The intermediate artifact is named `Keryx-1.0.0.dmg`, but the workflow's rename
step derives the final asset name from the tag, so the attached file is still `Keryx-0.1.1-macos-arm64.dmg`.

Set `DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` as **repository secrets**. If they
are unset the build still succeeds, but the released app has the corresponding cloud integration hidden entirely
(see `CloudStorageAvailability`).

> **The released DMG is unsigned** (ad-hoc). Users will be blocked by Gatekeeper and must right-click → Open, or
> clear the quarantine attribute. See "Signing & Notarization" below for what lifting this requires.

## Signing & Notarization (future)

Currently, packaged artifacts are **ad-hoc signed** (effectively unsigned). This is fine for local development, but the following requires **Developer ID Application** signing (requires paid Apple Developer Program enrollment):

- Distribution to other Macs (getting past Gatekeeper).
- Removing Keychain access permission dialogs on macOS (a stable signing identity fixes the ACL).

> **Signing while still on a 0.x version needs care.** jpackage signs the `.app`, so anything that edits
> `Info.plist` *afterwards* breaks the bundle seal. The custom URI scheme no longer does this — it goes through
> `macOS { infoPlist { extraKeysRawXml } }` and is therefore already in the plist jpackage signs. What remains is
> `restoreMacOsShortVersion`, which only runs when the major version is `0`: at 1.0.0 and beyond there is no
> post-processing at all and `codesign --verify --strict` passes. If Developer ID signing is adopted **while still
> on 0.x**, the version write-back will invalidate it, so the bundle must be re-signed with the same identity
> afterwards — re-signing ad-hoc would silently replace the Developer ID signature and defeat notarization.

Overview:

1. Enroll in Apple Developer Program and import a **Developer ID Application** certificate into the login keychain (must appear in `security find-identity -v -p codesigning`).
2. Add signing to `macOS {}` in `composeApp/build.gradle.kts`:

   ```kotlin
   macOS {
       signing {
           sign.set(true)
           identity.set("Developer ID Application: <Name> (<TEAMID>)")
       }
   }
   ```

   If you don't want secrets in VCS, put it in `~/.gradle/gradle.properties` as
   `compose.desktop.mac.signing.identity`.
3. For distribution notarization only, prepare an app-specific password and set `macOS { notarization { appleID/password/teamId } }`, then run `./gradlew :composeApp:notarizeDmg`. Notarization is not required for local testing.

No special entitlements are required for Keychain access (just ensure `get-task-allow` is not added; jpackage's Developer ID signing uses hardened runtime, which satisfies the requirement). See [sync-architecture.md](sync-architecture.md) "Dropbox Authentication > Token Storage" for token storage details.

## Notes

- Configuration cache is disabled in `gradle.properties` (the `generateBuildConfig` task is not config-cache-safe). Do not re-enable without verifying safety.
- `-Xexpect-actual-classes` is passed to suppress the Beta warning for expect/actual classes.

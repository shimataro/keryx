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

## Cloud Storage Integration

Specify API keys at build time to enable cloud storage (sync) integration.

Property values are referenced from [local.properties.example](../local.properties.example).
Copy this file to `local.properties` and edit it during the build.

Services without an API key will not show integration options. If no service is configured, the integration itself does not appear (e.g., tabs in the settings dialog).
**Only one cloud storage can be connected at a time**, and data cannot be distributed across multiple storages.

This is implemented via a Gradle custom task (`generateBuildConfig`).

Below is how to obtain API keys for each service.

### Dropbox

1. Create an app on [DBX Platform](https://www.dropbox.com/developers/apps/create)
   - If already created, search from [App Console](https://www.dropbox.com/developers/apps)
   - "Choose an API": `Scoped access`
   - "Choose the type of access you need": `App folder`
   - This grants access only to the app-specific folder, not arbitrary files in the drive.
2. In "Settings", configure the following:
   - "Redirect URIs": `keryx://oauth2/callback`
   - "Allow public clients (Implicit Grant & PKCE)": `Allow`
3. Check the following in "Permissions":
   - `files.content.write`
   - `files.content.read`
4. Specify the "App key" in `local.properties` (copy of [local.properties.example](../local.properties.example)).

### Google Drive

1. Create a project in the [Google Cloud Console](https://console.cloud.google.com)
2. Navigate to "APIs & Services" → "Library" and find the "Google Drive API"
   - Enter "drive" in the search box, or narrow down from "Storage" in the sidebar
   - Click "Enable"
3. Navigate to "Google Auth Platform" → "Data Access" (this replaced the old "OAuth consent screen" page)
   - Click "Add or remove scopes"
   - Check `.../auth/drive.appdata` for "Google Drive API"
   - Click "Update" to confirm the selection, then click "Save" on the Data Access page to persist it
   - This grants access only to the app-specific folder, not arbitrary files in the drive.
4. Navigate to "Google Auth Platform" → "Clients" and create a client
   - "Create client" at the top
   - Application type: "Desktop app"
   - Specify the "Client ID" and "Client Secret" shown on the same page in `local.properties` (copy of [local.properties.example](../local.properties.example))

The redirect after OAuth2 cannot be arbitrarily determined like Dropbox, so it is received via loopback at `http://127.0.0.1:<port>` (the app temporarily sets up an HTTP server with `LoopbackRedirectTransport` to receive it).
The flow uses PKCE (`code_verifier`), but **a client secret is also required separately** — unlike iOS/Android, Google's "Desktop app" OAuth client is not treated as a full public client, and Google's token endpoint rejects token exchange / refresh without `client_secret` with `invalid_request: client_secret is missing` (regardless of PKCE). The scope requested is `drive.appdata` only (an app-specific hidden folder in the user's Drive). During development, set the publishing status to "Testing" on the "Audience" tab and register test users.

> **Testing status expires refresh tokens after 7 days.** While the OAuth consent screen's
> publishing status stays "Testing", Google issues refresh tokens that expire 7 days after
> being granted, so a Google Drive sync connection needs to be re-linked roughly weekly (the
> app surfaces this as a `CloudAuthException` notification-center entry, not a silent
> failure). For long-running use, move the publishing status to "In production" on the
> "Audience" tab — `drive.appdata` is a non-sensitive scope, so publishing does not require
> Google's sensitive/restricted-scope verification at all; only the optional, lighter-weight
> "brand verification" is needed if you want the app name and logo shown on the consent
> screen instead of Google's default unverified-app presentation.

### OneDrive

1. Register an app in the [Azure Portal](https://portal.azure.com) → "Microsoft Entra ID" → "App registrations" → "New registration"
   - "Supported account types": choose "Personal Microsoft accounts only".
2. In "Authentication" → "Add a platform" → **"Mobile and desktop applications"**:
   - Under "Custom redirect URIs" add `keryx://oauth2/callback`.
   - Set "Allow public client flows" to **Yes** (OneDrive is a PKCE public client — no client secret).
3. In "API permissions" → "Add a permission" → "Microsoft Graph" → "Delegated permissions", add **`Files.ReadWrite.AppFolder`** (access is limited to the app's hidden folder, not arbitrary files). `offline_access` is requested at runtime for a refresh token.
4. Copy the "Application (client) ID" from "Overview" into `local.properties` (copy of [local.properties.example](../local.properties.example)) as `onedrive.client.id`.

OneDrive reuses the same custom URI scheme as Dropbox (`keryx://oauth2/callback`, disambiguated by `state`), so no additional OS registration is needed. **No client secret is required** (unlike Google, Microsoft treats a "Mobile and desktop applications" registration as a full public client with PKCE). The sync DB is stored in OneDrive's hidden app folder (`/me/drive/special/approot`). As with Dropbox, macOS routes `keryx://` to the packaged app, so `./gradlew :composeApp:run` cannot complete linking — build `Keryx.app` with `createDistributable` to test it on macOS.

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
`composeApp/src/commonMain/composeResources/drawable/tray_icon*.png` — `tray_icon_outlined.png` (white glyph +
black outline) for the macOS menu bar and the Linux SNI panel, `tray_icon.png` (full colour) for the Windows
notification area, the Linux AWT fallback and the window's own title-bar icon. These are generated from shared artwork via
`design/icons/make_desktop_icons.sh` (it is preferable to commit generated files).

The app's store/menu category is set per platform in `nativeDistributions`: macOS uses
`appCategory = "public.app-category.news"` (`LSApplicationCategoryType`) since Apple's App Store
taxonomy has no plain "Internet" category; Linux uses `menuGroup = "Network;News;Feed;"`, written
verbatim into the generated `.desktop` file's `Categories=` field — `Network` is the relevant main
category in the freedesktop.org Desktop Menu Specification, with `News` and `Feed` as matching
registered additional categories. Windows/jpackage has no category concept (its `menuGroup` is only
the Start Menu folder name), so nothing is set there.

The `keryx://` custom URI scheme is **not** registered by the deb/rpm package. jpackage only emits a `.desktop` file when
given a shortcut or a file association, and its template's `Exec` line has no `%u`, so the URI would never reach the
process. Instead the app registers itself on first launch (`LinuxUriSchemeRegistrar`), writing
`$XDG_DATA_HOME/applications/keryx-url-handler.desktop` (default `~/.local/share/applications`) and an
association in `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`). This also covers
`createDistributable` app images and tarball installs. Both files live in the user's home and are **not removed when the
package is uninstalled**, and there is no uninstall hook to clean them up. This is not harmless: the surviving
`[Default Applications]` entry in `mimeapps.list` keeps pointing `keryx://` at a launcher path that no longer exists,
so `xdg-open` (or a browser resolving the scheme) can fail until the two are removed manually — delete
`keryx-url-handler.desktop` from the applications directory and drop the `x-scheme-handler/keryx` line(s) from
`mimeapps.list`.

> **Custom-URI linking confirmation**: `./gradlew :composeApp:run` cannot complete Dropbox / OneDrive linking on any desktop OS — macOS routes `keryx://` to the packaged app, and the Windows/Linux startup registration deliberately skips non-packaged launchers. To verify linking behavior, build with `createDistributable` and launch the packaged app (see [setup.md](setup.md) "Common Issues" for details).

## Release (CD)

`.github/workflows/release.yml` builds the packages and attaches them to the GitHub Release.
**macOS and Linux (x86_64) for now** (cross-compilation is not supported, so each additional
platform needs its own runner — Windows is not yet automated).

Flow:

1. Publish a GitHub Release with a `vMAJOR[.MINOR[.PATCH]]` tag (e.g. `v0.1.0`).
2. The workflow triggers on `release: published`, strips the leading `v`, and passes the result as `-PappVersion`.
3. Two independent jobs run in parallel: `:composeApp:packageDmg` (macOS runner), attached as
   `Keryx-<version>-macos-arm64.dmg`; and `:composeApp:packageDeb :composeApp:packageRpm` (Linux
   runner, after installing `fakeroot`/`rpm` for jpackage), attached as
   `Keryx-<version>-linux-x86_64.deb` and `Keryx-<version>-linux-x86_64.rpm`.

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

Set `DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` / `ONEDRIVE_CLIENT_ID` as
**repository secrets**. If they are unset the build still succeeds, but the released app has the corresponding
cloud integration hidden entirely (see `CloudStorageAvailability`).

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
- `nativeDistributions.modules` includes **`jdk.security.auth`** because dbus-java's SASL EXTERNAL
  authentication resolves the uid through `com.sun.security.auth.module.UnixSystem` on every
  non-Windows host. Leave it out and the jlink image still builds, but the packaged `.deb`/`.rpm`
  dies with `NoClassDefFoundError` while `./gradlew run` (full JDK) keeps working - so **verify Linux
  packaging with `createDistributable` and by launching the produced `bin/Keryx`, not with `run`**.
  This affects the java-keyring Secret Service path too, not just the tray.
- dbus-java (MIT) is shipped on every platform but only touched at runtime on Linux (tray +
  notifications). Its version is pinned to the one java-keyring already brings in transitively:
  `de.swiesend:secret-service` still references `org.freedesktop.dbus.errors.Error`, which dbus-java 5
  moved, so upgrading would break the Linux keyring.

# Build & Packaging

[日本語](build.ja.md)

## Requirements

- **JDK 25 or later** (`JAVA_HOME`, the JVM that launches `./gradlew`).
  The JDK 25 compilation toolchain is auto-provisioned by Gradle's foojay-resolver.
  However, JavaExec tasks such as `:composeApp:run` are executed with the JVM that launched Gradle, so if it is older than 25 you will hit `UnsupportedClassVersionError` at runtime.
- Use the bundled wrapper (`./gradlew`, Gradle 9.6.1).
- **Android SDK** (`local.properties`' `sdk.dir` or the `ANDROID_HOME` environment variable) —
  `:composeApp` itself configures an Android library target, so the root `./gradlew build` needs
  the SDK resolvable even for a desktop-only change. See [setup.md](setup.md) for install/AVD
  setup; a desktop-scoped task like `:composeApp:compileKotlinDesktop`/`:composeApp:desktopTest`
  avoids this requirement.

If toolchain auto-download is blocked in a sandbox:
`./gradlew -Dorg.gradle.java.installations.auto-download=true ...`.

## Build & Run

```bash
./gradlew build                       # Compile all source sets + run tests
./gradlew :composeApp:desktopTest     # Tests only
./gradlew :composeApp:run             # Launch the desktop app

./gradlew :androidApp:assembleDebug        # Build a debug APK
./gradlew :androidApp:installGithubDebug   # Build + install it on a connected device/emulator
```

`:androidApp` has a `distribution` product-flavor dimension (see "Android (APK / AAB)" below), so
installing is a per-variant task — there is no `installDebug`, and `githubDebug` is the only debug
variant (`playDebug` is disabled). `assembleDebug` remains an aggregate over the enabled debug
variants and so still works as written.

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

> [!IMPORTANT]
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
     This is **paired with the `consumers` tenant segment** hardcoded in
     `core/Constants.kt`'s `ONEDRIVE_AUTHORIZE_ENDPOINT`/`ONEDRIVE_TOKEN_ENDPOINT` — Microsoft
     rejects a `Consumer`-audience registration on the `/common` endpoint, and only after the
     user submits their address, so the mismatch shows up as a generic "authentication failed".
     Do not change one without the other. Work/school accounts are deliberately unsupported:
     `Files.ReadWrite.AppFolder` below is a personal-account-only Graph permission (see
     [sync-architecture.md](sync-architecture.md)).
2. In "Authentication" → "Add a platform" → **"Mobile and desktop applications"**:
   - Under "Custom redirect URIs" add `keryx://oauth2/callback`.
   - Set "Allow public client flows" to **Yes** (OneDrive is a PKCE public client — no client secret).
3. In "API permissions" → "Add a permission" → "Microsoft Graph" → "Delegated permissions", add **`Files.ReadWrite.AppFolder`** (access is limited to the app's hidden folder, not arbitrary files). `offline_access` is requested at runtime for a refresh token.
4. Copy the "Application (client) ID" from "Overview" into `local.properties` (copy of [local.properties.example](../local.properties.example)) as `onedrive.client.id`.

OneDrive reuses the same custom URI scheme as Dropbox (`keryx://oauth2/callback`, disambiguated by `state`), so no additional OS registration is needed. **No client secret is required** (unlike Google, Microsoft treats a "Mobile and desktop applications" registration as a full public client with PKCE). The sync DB is stored in OneDrive's hidden app folder (`/me/drive/special/approot`). As with Dropbox, macOS routes `keryx://` to the packaged app, so `./gradlew :composeApp:run` cannot complete linking — build `Keryx.app` with `createDistributable` to test it on macOS.

### Android

Android supports **Dropbox and OneDrive only** — set the same `local.properties` keys as above
(`dropbox.app.key` / `onedrive.client.id`, or their `DROPBOX_APP_KEY`/`ONEDRIVE_CLIENT_ID`
environment-variable equivalents); the Google Drive keys have no effect on the Android build.
**Google Drive is not
offered on Android** because its desktop OAuth configuration (a "Desktop app" client using loopback
redirect + `client_secret`) cannot be reused there — see `external-spec.md` §4 and
`sync-architecture.md`'s "Google Drive on Android" for the underlying investigation.

Unlike desktop, where `keryx://` needs an OS-level registration step (see each provider's note
above), Android receives the `keryx://oauth2/callback` redirect through a plain manifest
declaration — an `ACTION_VIEW` intent-filter (`scheme="keryx"` `host="oauth2"`) in
`androidApp/src/main/AndroidManifest.xml` — so there is no packaged-vs-unpackaged distinction like
the desktop `./gradlew :composeApp:run` limitation above. To verify linking in an emulator, it
needs a real browser to actually complete the OAuth flow — a Google Play system image (Chrome) is
the recommended way to get one — see [setup.md](setup.md).

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

### Android (APK / AAB)

Unlike the desktop packages above, an APK/AAB can be built on **any** OS — there is no
cross-compilation restriction here.

`androidApp` splits into two product flavors on a `distribution` dimension — `github` and `play`,
same `applicationId` — that differ in exactly one thing: `androidApp/src/github/AndroidManifest.xml`
declares `REQUEST_INSTALL_PACKAGES`, needed for the in-app update installer's `PackageInstaller`
session (see [background-update.md](background-update.md)'s "In-App Update"); the `play` flavor's
manifest omits it, since Play already updates the app itself and Play policy restricts that
permission to apps whose primary purpose is installing other apps. `composeApp` (a KMP library
module) has no flavor dimension of its own and is consumed identically by both.

```bash
./gradlew :androidApp:assembleGithubRelease -PappVersion=1.2.3   # APK (GitHub Releases)
./gradlew :androidApp:bundlePlayRelease     -PappVersion=1.2.3   # AAB (Play Store submission format)
```

Output goes to `androidApp/build/outputs/apk/github/release/` and
`androidApp/build/outputs/bundle/playRelease/` respectively (a different location than the desktop
packages' `composeApp/build/compose/binaries/main` above). `assembleGithubRelease` is reachable
through the default `build` lifecycle's aggregate `assembleRelease`/`build` tasks (which build both
flavors' release variants); `bundlePlayRelease` is not part of any aggregate lifecycle task and must
be invoked explicitly — see "Release (CD)" below for how `release.yml` uses both. Run
`./gradlew :androidApp:tasks --all | grep -i release` after touching `androidApp/build.gradle.kts`'s
`flavorDimensions` to confirm these task names and output paths before changing `release.yml` — AGP
derives them from the flavor/build-type names, and a rename there silently breaks the workflow only
once a release tag is pushed.

Release signing is resolved from three sources, in this priority order — a Gradle project property,
an environment variable, then `local.properties` — and all four values are required together (an
incomplete set fails the build immediately rather than falling back to an unsigned/half-signed
result); see [setup.md](setup.md) for how to generate a keystore for local use:

| `local.properties` key | `-P` property | Environment variable |
| --- | --- | --- |
| `android.release.keystore.path` | `androidReleaseKeystorePath` | `ANDROID_RELEASE_KEYSTORE_PATH` |
| `android.release.keystore.password` | `androidReleaseKeystorePassword` | `ANDROID_RELEASE_KEYSTORE_PASSWORD` |
| `android.release.key.alias` | `androidReleaseKeyAlias` | `ANDROID_RELEASE_KEY_ALIAS` |
| `android.release.key.password` | `androidReleaseKeyPassword` | `ANDROID_RELEASE_KEY_PASSWORD` |

With none of the three sources set, the build still succeeds but produces an **unsigned** release
APK (a build warning, no fallback to debug signing) — see "Release (CD)" below for how CI handles
signing, and setup.md's "Software Required to Build" for the reasoning behind that design.

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

> [!IMPORTANT]
> **Custom-URI linking confirmation**: `./gradlew :composeApp:run` cannot complete Dropbox / OneDrive linking on any desktop OS — macOS routes `keryx://` to the packaged app, and the Windows/Linux startup registration deliberately skips non-packaged launchers. To verify linking behavior, build with `createDistributable` and launch the packaged app (see [setup.md](setup.md) "Common Issues" for details).

### `.opml` file association

Double-clicking (or "Open With Keryx" on) an `.opml` file launches Keryx and imports its
subscriptions (`FeedRepository.importOpml`, surfaced via the notification center — see
[app-architecture.md](app-architecture.md)). Registration mirrors the `keryx://` scheme above,
per platform:

- **macOS**: declared at build time via `CFBundleDocumentTypes` in the same
  `infoPlist { extraKeysRawXml }` block as `CFBundleURLTypes`. `LSHandlerRank` is `Default` (not
  `Alternate`) so a plain double-click launches Keryx directly rather than only adding it to the
  "Open With" submenu. macOS has no single built-in system UTI for OPML, and the third-party feed
  reader ecosystem never converged on one either — NetNewsWire uses `org.opml.opml` (the closest
  thing to a de facto standard, since OPML itself predates Apple's UTI system), Reeder uses
  `com.reederapp.opml`, and Overcast uses `unofficial.opml`. An earlier version of this app instead
  exported its own UTI (`works.merc.keryx.opml`), but that made Keryx invisible in Finder's "Open
  With" menu on any Mac where another app had already claimed the `.opml` extension for one of these
  other identifiers — the file resolves to whichever UTI is already bound to that extension, and a
  competing export doesn't win that binding. `LSItemContentTypes` therefore lists all three known
  identifiers, declared via `UTImportedTypeDeclarations` (Keryx is a consumer of these identifiers,
  not their owner) rather than `UTExportedTypeDeclarations`, so Keryx is offered as a handler
  whichever one (if any) is already bound to `.opml` on the user's machine.
- **Windows**: registered at startup (`registerWindowsOpmlAssociation`) under a dedicated
  `Keryx.opml` ProgID (`HKEY_CURRENT_USER\Software\Classes\.opml` → `Keryx.opml` →
  `shell\open\command`), the same per-user, no-admin-needed mechanism as the URI scheme.
- **Linux**: registered at startup (`LinuxOpmlAssociationRegistrar`), writing a *second* user-level
  `.desktop` entry (`keryx-opml-handler.desktop`, `Exec=... %f` — a bare local path, not a URI) plus
  a shared-mime-info package XML at `$XDG_DATA_HOME/mime/packages/keryx-opml.xml` mapping the
  `*.opml` glob to `application/x-opml+xml`, since that MIME type isn't guaranteed to be predefined
  by the distro's own `shared-mime-info` package. As on macOS, no single OPML MIME type is
  standardized across Linux feed readers either, so the `.desktop` entry's `MimeType=` also lists
  the other candidate seen in the wild, `text/x-opml` (`OPML_MIME_TYPE_ALT`) — but only there, not
  in Keryx's own shared-mime-info package, so Keryx becomes an eligible opener if another
  already-installed reader's package has bound `.opml` to that type instead, without Keryx itself
  asserting a second, conflicting glob mapping for `.opml`. Same gate as the URI scheme: only
  registers from a packaged launcher, so `./gradlew :composeApp:run` never creates these files
  either. Like the `keryx://` scheme's `keryx-url-handler.desktop` and `mimeapps.list` entry, all of
  these files live in the user's home and are **not removed when the package is uninstalled** — the
  same leftover-association risk applies (a stale entry pointing at a removed launcher), with the
  same manual cleanup: delete `keryx-opml-handler.desktop` and `keryx-opml.xml`, and drop the
  `application/x-opml+xml` and `text/x-opml` line(s) from `mimeapps.list`. Also rerun
  `update-mime-database` against `$XDG_DATA_HOME/mime` (default `~/.local/share/mime`) afterward —
  deleting `keryx-opml.xml` alone leaves the compiled MIME cache pointing at the removed type until
  the database is rebuilt.
- **Android**: declared entirely in `androidApp/src/main/AndroidManifest.xml` as two more
  `ACTION_VIEW` intent-filters on `MainActivity` — unlike the three desktop OSes above, there is no
  startup-time registration step; the manifest declaration alone is what makes Keryx appear in the
  system's "Open with" chooser. As on macOS and Linux, there is no single standardized OPML MIME
  type, and Android content providers commonly report a plain `.opml` file as
  `application/octet-stream` rather than any XML-flavored type — so MIME matching alone would miss
  most real files. A MIME-based filter (`application/x-opml+xml`, `text/x-opml`, `text/xml`,
  `application/xml` — the same identifiers the Linux section above already lists) and an
  extension-based fallback filter (`scheme="content"`, `host="*"`, `mimeType="*/*"`,
  `pathPattern=".*\\.opml"`, matching on the `content://` URI's path regardless of the reported MIME
  type) are declared as **two separate intent-filters**, not combined `<data>` tags within one:
  Android pools every `<data>` element's scheme/host/mimeType/pathPattern within a single
  `<intent-filter>` into one shared match set (`IntentFilter.matchData`), so a `pathPattern`
  declared on one `<data>` tag would silently apply to every other `<data>` tag's plain MIME type in
  the same filter too — an intent whose MIME type matched but whose `content://` path lacked a
  literal `.opml` suffix (the common case, since SAF document IDs are often opaque) would then fail
  to match the filter at all, defeating the MIME-based tags entirely. Splitting them keeps a
  plain-MIME match independent of the `.opml` suffix. The fallback filter's `host="*"` is required,
  not decorative: `IntentFilter.matchData` only evaluates a `pathPattern` at all when the filter also
  declares a host, so without one the fallback would silently never match any real `content://` URI
  (whose actual authority is the serving provider, e.g. `com.android.externalstorage.documents`, and
  can't be enumerated up front) — `"*"` is `IntentFilter`'s documented wildcard for "any host".
  `AndroidOpmlOpen.kt`'s `handleOpmlOpenIfPresent` reads the incoming `content://` `Uri`
  via `ContentResolver` and excludes the `keryx://` OAuth redirect, which shares the same
  `MainActivity`/`ACTION_VIEW` handling through a separate intent-filter. Accepting `text/xml`/
  `application/xml` means Keryx also appears in the chooser for unrelated XML files — the same
  trade-off the Linux section's `text/x-opml` fallback already accepts — and malformed input is
  handled the same way as the other platforms: `OpmlImporter.import`'s failure is caught rather than
  propagated.

## Release (CD)

`.github/workflows/release.yml` builds the packages and attaches them to the GitHub Release.
**macOS, Linux, Windows (x86_64, plus macOS arm64), and Android (universal APK/AAB)** (cross-compilation is not
supported, so each platform needs its own runner).

Flow:

1. Publish a GitHub Release with a `vMAJOR.MINOR.PATCH` tag, optionally with a SemVer-style
   pre-release suffix (e.g. `v0.1.0`, `v1.2.0-beta.1`).
2. The workflow triggers on `release: published`, strips the leading `v`, and passes the result as `-PappVersion`.
3. Four independent jobs run in parallel:

   - `:composeApp:packageDmg` (macOS runner), attached as `Keryx-<version>-macos-arm64.dmg` **and `Keryx-<version>-macos-arm64.zip`**. **For a pre-release tag, `packageDmg` is skipped and only the `.zip` is attached** (same reasoning as the Windows MSI case below).
   - `:composeApp:packageDeb :composeApp:packageRpm` (Linux runner, after installing `fakeroot`/`rpm` for jpackage), attached as `Keryx-<version>-linux-x86_64.deb`, `Keryx-<version>-linux-x86_64.rpm` **and `Keryx-<version>-linux-x86_64.zip`**. **For a pre-release tag, `packageDeb`/`packageRpm` are skipped and only the `.zip` is attached** (same reasoning as the Windows MSI case below).
   - `:composeApp:createDistributable :composeApp:packageMsi` (Windows runner — `windows-latest` ships WiX Toolset v3.14.1 preinstalled, so no separate WiX setup step is needed), attached as `Keryx-<version>-windows-x86_64.msi` **and `Keryx-<version>-windows-x86_64.zip`**. **For a pre-release tag, `packageMsi` is skipped and only the `.zip` is attached** — MSI's `ProductVersion` must be purely numeric (see below), so every pre-release of a given target version would collapse to the same `ProductVersion` under the fixed `upgradeUuid`, and WiX would not recognize a later pre-release or the eventual final release as an upgrade of an earlier one.
   - `:androidApp:assembleGithubRelease` and `:androidApp:bundlePlayRelease` (Ubuntu runner), attached as `Keryx-<version>-android-universal.apk` and `Keryx-<version>-android-universal.aab`. The APK comes from the `github` flavor (carries `REQUEST_INSTALL_PACKAGES`, since it's the one an in-app update installs over — see the "Android (APK / AAB)" section above) and the AAB from `play` (the Play Console submission artifact, which must not carry that permission). Unlike the desktop installers, Android packages are built and attached for pre-release tags too, because Android has no equivalent version-metadata restriction and testers need a signed APK. **Pre-release APK/AAB files produced by the workflow are GitHub test artifacts only.** `androidApp/build.gradle.kts` derives `versionCode` from `appVersion.substringBefore('-')`, so a pre-release tag such as `v1.2.0-beta.1` and the final `v1.2.0` produce the same `versionCode` (e.g. `10200`). Before submitting to Google Play, assign a strictly increasing `versionCode` by adjusting `androidApp/build.gradle.kts` (or the release tag that drives it) and rebuilding the APK/AAB — the value is baked into the signed artifact at build time and cannot be edited afterward.

   The `.zip` files are archives of the non-packaged app bundle/image produced by `:composeApp:createDistributable`, for users who prefer not to use an installer package. The `deploy-pages` job (which triggers the Cloudflare Pages deploy hook) waits on all four packaging jobs before running.

The **tag is the single source of truth for the version**. `appVersion` in `composeApp/build.gradle.kts` resolves
`-PappVersion` > `APP_VERSION` env var > the literal in the file, and drives `BuildConfig.VERSION` (shown in the
About screen, and used by the update checker) as the full tag, pre-release suffix included.
`composeApp/build.gradle.kts` separately derives `appPackageVersion` from it by stripping any pre-release suffix,
and that drives the native-distribution `packageVersion` for every target — jpackage's packaging metadata
(CFBundleVersion, RPM `%version`, MSI `ProductVersion`) must stay purely numeric `MAJOR.MINOR.PATCH` and cannot
carry a pre-release suffix. For a plain (non-prerelease) tag the two are identical, so nothing changes; local
builds fall through to the same `"0.0.0"` literal for both. A tag that does not yield a jpackage-compatible
`MAJOR.MINOR.PATCH[-<pre-release>]` version fails the workflow early with an explicit message.

`macos-latest` runners are arm64, hence the architecture in the artifact name — it leaves room for an x86_64 or
universal build alongside it later.

### 0.x versions and pre-release tags on macOS

jpackage refuses a macOS `--app-version` whose first component is `0` (it enforces the CFBundleVersion rule that
versions start at 1), and it fails `createDistributable` — not just the DMG step — so a `0.x` release would
otherwise be impossible to package at all. jpackage also requires the packaging version to be purely numeric
(`MAJOR.MINOR.PATCH`), so a pre-release-suffixed tag like `1.2.0-beta.1` can't be handed to it either — the same
restriction applies to RPM's `%version` field and MSI's `ProductVersion` on the other two platforms. jpackage has
no separate `--mac-app-version` input, and the Compose plugin's own validation covers neither case, so neither
can be configured away.

`composeApp/build.gradle.kts` works around both with `appPackageVersion` (`appVersion` with any pre-release
suffix stripped, see above), which feeds the shared `packageVersion` for deb/rpm/msi, and its macOS-only
derivative `macOsPackageVersion`: when `appPackageVersion`'s major component is `0`, macOS is additionally
packaged under the placeholder `1.0.0` (applied via `macOS { packageVersion }` only — deb/rpm/msi accept `0.x`
and are left alone). A major version of 1 or higher is passed through untouched. Whenever the packaged
`macOsPackageVersion` ends up different from the true `appVersion` — the 0.x placeholder, a stripped pre-release
suffix, or both at once — `restoreMacOsShortVersion` rewrites `CFBundleShortVersionString` back to the real
version in `createDistributable`'s `doLast`; when they already match (a plain, non-prerelease, major-1-or-higher
tag) it is a no-op.

jpackage signs the bundle *before* that `doLast` runs, so a write-back invalidates the ad-hoc seal — which is
why the same `doLast` then **re-signs** the bundle (`resealMacOsBundle`: `codesign --force --deep
--preserve-metadata=entitlements,flags,runtime --sign -`), **checks that the re-sign changed nothing about the
signature but its hashes** (`macSignatureProperties` compares `codesign -dv`'s `flags=` and `hashes=13+N`
before and after), and finally **verifies** it (`verifyMacOsBundleSeal`: `codesign --verify --strict --deep`),
failing the build outright at either step.

`--preserve-metadata` is what makes that middle step pass, and it is load-bearing rather than defensive.
Compose Desktop signs the app image with its own `default-entitlements.plist` — `allow-jit`,
`allow-unsigned-executable-memory`, `disable-library-validation` — **and** the hardened-runtime flag, all of
which a JVM needs to run at all on Apple Silicon. Naming `--options runtime` by hand reproduces the flag while
silently dropping the entitlements (`hashes=13+7` becomes `13+3`), which yields a bundle that passes
`codesign --verify` and is then killed by AMFI the moment it launches — a failure `verifyMacOsBundleSeal` alone
cannot see, since the seal really is valid. Hence both the metadata preservation and the before/after
comparison; neither is redundant with the seal verify. The verify runs on every macOS build whether or not anything was patched; on Windows and
Linux all three steps are no-ops, since no `.app` exists there. This is not cosmetic: the in-app updater runs
that exact check against every downloaded bundle before swapping it in (see
[background-update.md](background-update.md)), so an app image that cannot pass it leaves the release ZIP
un-installable **by the in-app updater** — a manual install of the very same ZIP keeps working, since the kernel
never re-hashes `Info.plist` at launch. That asymmetry is why every 0.x release shipped this way unnoticed until
the in-app updater first exercised the check, and why the build-time verify is the only thing that catches it:
ordinary manual smoke-testing cannot. The DMG never exposed it either, because jpackage re-signs its own copy of
the app image while building it — only the ZIP asset, made straight from `binaries/main/app`, carried the broken
seal.

The net effect for `0.1.1`: the tag, `BuildConfig.VERSION` (About screen), the update checker, and the version
Finder shows are all `0.1.1`. Only `CFBundleVersion` keeps the `1.0.0` placeholder, which is an internal build
identifier that never surfaces. The intermediate artifact is named `Keryx-1.0.0.dmg`, but the workflow's rename
step derives the final asset name from the tag, so the attached file is still `Keryx-0.1.1-macos-arm64.dmg`. For a
pre-release tag such as `1.2.0-beta.1`, the same split applies to the numeric metadata: `BuildConfig.VERSION`,
Finder's displayed version, and the release asset name are all `1.2.0-beta.1`, while `CFBundleVersion` / RPM
`%version` / MSI `ProductVersion` are all the stripped `1.2.0`.

Set `DROPBOX_APP_KEY` / `GOOGLE_DRIVE_CLIENT_ID` / `GOOGLE_DRIVE_CLIENT_SECRET` / `ONEDRIVE_CLIENT_ID` as
**repository secrets**. If they are unset the build still succeeds, but the released app has the corresponding
cloud integration hidden entirely (see `CloudStorageAvailability`).

For Android release signing, set `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD` as repository secrets. The keystore is a Base64-encoded PKCS12/JKS file; the workflow decodes it at build time. To keep the same signing key on GitHub Releases and Google Play, generate the keystore locally and, when creating the app in Google Play Console, enroll it as the **existing app signing key**: Play Console never accepts the raw JKS/PKCS12 file directly — first encrypt it with Google's PEPK (Play Encrypt Private Key) tool (`java -jar pepk.jar --keystore=<path> --alias=<alias> --output=<encrypted-file> --encryptionkey=<key-from-play-console>`, downloaded from the Play App Signing enrollment page), then upload the resulting encrypted file. This registers the keystore as the **app signing key** — the key Google holds and uses to re-sign the app before it reaches users, distinct from the **upload key** used to sign each `.aab` submitted through Play Console afterward. The same keystore can serve both roles (Google explicitly allows reusing the app signing key as its own upload key), which is what keeps a single keystore sufficient for both GitHub Releases (where the APK/AAB is signed with it directly) and Google Play; a separate, dedicated upload key is Google's recommended hardening, not a requirement. `release.yml` passes `-PandroidReleaseSigningRequired=true` to `:androidApp:assembleGithubRelease`/`:androidApp:bundlePlayRelease`, which turns a missing (or half-configured) secret into an immediate build failure — since this workflow publishes its output, it must never succeed with an unsigned artifact — so all four secrets are required for the release workflow to succeed. Both flavors are signed with the same keystore (the `signingConfigs` block isn't flavor-scoped), which is exactly what the app-signing-key enrollment above requires: the sideloaded `github` APK and the Play-resigned `play` AAB need to trace back to the same signing identity, or a device that already has one installed can never receive the other as an in-place update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

`ci.yml`'s ordinary build job never receives these secrets — deliberately, since it runs on every
push and never publishes anything. AGP wires `assembleRelease` into `:androidApp`'s default
`build` task regardless of whether the artifact is ever consumed (`bundleRelease` is a separate
lifecycle task, which is why `release.yml` above invokes it explicitly), but
`androidApp/build.gradle.kts`'s `signingConfigs` block treats a completely unconfigured signing
identity as the unsigned-release case (a build warning, not a failure — see "Android release
signing keystore" in [setup.md](setup.md)) rather than requiring `androidReleaseSigningRequired`.
So plain `./gradlew build` — in CI or locally — needs no keystore at all; only a workflow that
actually distributes the result (`release.yml`) opts into hard failure instead.

> [!IMPORTANT]
> **The released DMG is unsigned** (ad-hoc), so Gatekeeper blocks it on open. See the
> [Download](../README.md#download) section for the workaround; "Signing & Notarization" below
> covers what a permanent fix requires.

## Signing & Notarization (future)

Currently, packaged artifacts are **ad-hoc signed** (effectively unsigned). This is fine for local development, but the following requires **Developer ID Application** signing (requires paid Apple Developer Program enrollment):

- Distribution to other Macs (getting past Gatekeeper).
- Removing Keychain access permission dialogs on macOS (a stable signing identity fixes the ACL).

> [!CAUTION]
> **Signing while still on a 0.x version needs care.** jpackage signs the `.app`, so anything that edits
> `Info.plist` *afterwards* breaks the bundle seal. The custom URI scheme no longer does this — it goes through
> `macOS { infoPlist { extraKeysRawXml } }` and is therefore already in the plist jpackage signs. What remains is
> `restoreMacOsShortVersion`, which only runs when the major version is `0`. Its write-back is already followed by
> `resealMacOsBundle` (see the version-handling section above), but that re-signs **ad-hoc**, with `-` hardcoded
> because this build configures no signing identity at all. Adopting Developer ID signing therefore means passing
> that identity to `resealMacOsBundle` instead: re-signing ad-hoc over a Developer ID signature would silently
> replace it and defeat notarization. At 1.0.0 and beyond there is no write-back and no re-sign — jpackage's own
> signature is left exactly as produced.

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

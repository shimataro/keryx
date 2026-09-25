# Build & Packaging

[日本語](build.ja.md)

## Requirements

- **JDK 25 or later** (`JAVA_HOME`, the JVM that launches `./gradlew`).
  The JDK 25 compilation toolchain is auto-provisioned by Gradle's foojay-resolver.
  However, JavaExec tasks such as `:composeApp:run` are executed with the JVM that launched Gradle, so if it is older than 25 you will hit `UnsupportedClassVersionError` at runtime.
- Use the bundled wrapper (`./gradlew`, Gradle 9.7.1).
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

Dropbox and OneDrive use the same `local.properties` keys as above (`dropbox.app.key` /
`onedrive.client.id`, or their `DROPBOX_APP_KEY`/`ONEDRIVE_CLIENT_ID` environment-variable
equivalents).

**Google Drive takes a different route on Android** — Play services' `AuthorizationClient`, because
Google deprecates both the custom-URI and loopback redirects for its Android OAuth client type (see
`sync-architecture.md`'s "Google Drive on Android"). It needs **no client secret and no backend**,
and the `googledrive.client.*` keys above have no effect on the Android build. What it needs instead
is an OAuth client registered against this app's identity:

1. In the **same Cloud project** as the desktop client (this matters: `appDataFolder` is scoped per
   project, so sharing the project is what lets a phone and a desktop see the same sync file), go to
   "Google Auth Platform" → "Clients" → "Create client" and choose application type **"Android"**.
2. Package name: `works.merc.keryx`.
3. SHA-1 of the signing certificate. Register **one client per signing certificate that actually
   reaches a device**:
   - the **app signing key**'s certificate (see "App signing key vs. upload key" under
     "Release (CD)" below) — Google re-signs every APK with this key before it reaches a device,
     regardless of which key the AAB was uploaded with, so this is the certificate to register, not
     the upload key's (which never reaches a device at all); one entry covers both the sideloaded
     GitHub APK and the Play-installed one, since both carry this same certificate in the end;
   - your local **debug** keystore, or `installGithubDebug` builds cannot authorize at all
     (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
4. No client ID or secret is copied into the project — Play services matches the app by package name
   + signing certificate at runtime. An unregistered key surfaces as an authorization failure on the
   device, not as a build error.

Google Drive is offered only where `GoogleApiAvailability.isGooglePlayServicesAvailable` reports
`ConnectionResult.SUCCESS` — Play services installed, enabled, and up to date. Anything short of
that (a de-Googled ROM, but equally a device where Play services is disabled or needs an update)
sees just Dropbox / OneDrive / local-only, since an authorization request could not be served there
anyway. See `CloudStorageAvailability.android.kt`.

Unlike desktop, where `keryx://` needs an OS-level registration step (see each provider's note
above), Android receives the `keryx://oauth2/callback` redirect through a plain manifest
declaration — an `ACTION_VIEW` intent-filter (`scheme="keryx"` `host="oauth2"`) in
`androidApp/src/main/AndroidManifest.xml` — so there is no packaged-vs-unpackaged distinction like
the desktop `./gradlew :composeApp:run` limitation above. To verify linking in an emulator, it
needs a real browser to actually complete the OAuth flow — a Google Play system image (Chrome) is
the recommended way to get one — see [setup.md](setup.md).

## Packaging

Created under `composeApp/build/compose/binaries/main` (relative to the repo root, not this file's own
directory — this is a build-output path, not a doc to link to).

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

### Linux package metadata (license, homepage, vendor)

`nativeDistributions` fills in metadata that jpackage's own Linux defaults leave wrong or blank,
plus one field it has no Compose DSL property for at all:

- `vendor = "Mercury Works"` — the publishing identity, kept separate from `appName` ("Keryx", the
  product's own display name). Surfaces as the rpm `Vendor:` tag and the Windows installer's
  Publisher property.
- `linux { rpmLicenseType = "MIT" }` — without it, jpackage's own default is the literal string
  `Unknown` in the rpm's `License:` tag.
- `licenseFile` (set only when building on Linux, gated on `System.getProperty("os.name")`) —
  installs `LICENSE` as the deb's `/usr/share/doc/<pkg>/copyright` and the rpm's `%license` file.
  Gated to Linux so it doesn't also add a license-acceptance page to the Windows MSI.
- `linux { debMaintainer = "keryx@merc.works" }` — the email written into the deb's
  `Maintainer:` tag. jpackage prefixes the configured `vendor`, so the output is
  "Mercury Works <keryx@merc.works>". Without it, jpackage's own default is
  `<build-user>@<build-host>` (the CI runner's own account). A role address, not a personal
  one — it lands in every published `.deb` permanently and is readable via `apt show`.
- `linux { appCategory = "net" }` — the deb `Section:` / rpm `Group:` tag. Distinct from
  `menuGroup`, which maps to the `.desktop` file's `Categories=`.
- `--about-url` — fills the deb's `Homepage:` and the rpm's `URL:` tags with the project's
  website. The Compose `nativeDistributions` DSL has no matching property, so this is added
  directly to the `packageDeb`/`packageRpm` jpackage invocations via
  `tasks.withType<AbstractJPackageTask>().configureEach { freeArgs.addAll(...) }` in
  `composeApp/build.gradle.kts`.

None of the above makes a Linux **software center** (GNOME Software, Ubuntu App Center, KDE
Discover) show a license or a homepage link, though: those read
[AppStream metainfo](https://www.freedesktop.org/software/appstream/docs/), not the package
control file — the `.deb` control file has no license field at all. So `packageDeb` also runs a
finalizer (`injectDebMetainfo` in `composeApp/build.gradle.kts`) that extracts the built `.deb`
with `dpkg-deb -R`, writes `composeApp/packaging/linux/works.merc.keryx.metainfo.xml.in` into
`usr/share/metainfo/works.merc.keryx.metainfo.xml` with its placeholders substituted
(`@DESKTOP_ID@` — the actual `.desktop` filename jpackage placed in the package, discovered by
walking the extracted payload rather than assumed, since jpackage derives the name from
`packageName`/the launcher name; `@VERSION@`; `@DATE@`). The same finalizer also adds a
`Comment[ja]=` line to that same `.desktop` file (right after its own `Comment=`, per the
freedesktop.org Desktop Entry Specification's locale-suffixed-key convention), so a Japanese
desktop environment shows a localized tooltip in the applications menu — `snap/gui/keryx.desktop`
carries the same `Comment[ja]=` independently, since it's a separately-maintained static file, not
jpackage-generated. Both edits are done in the same `dpkg-deb -R` extraction before a single
`dpkg-deb --build --root-owner-group` repack. This finalizer is a no-op when `dpkg-deb` isn't on
`PATH` (a local macOS/Windows build never produces a `.deb` at all).

`.rpm` gets no equivalent metainfo injection — deliberately asymmetric. jpackage's own
`--resource-dir` is fixed by the Compose plugin to a temp directory it clears mid-task, so the rpm
`.spec` template can't be substituted the way the `.deb` control files can, and repacking an
already-built `.rpm` needs an extra tool (`rpmrebuild`) this project doesn't otherwise depend on.
This isn't a functional gap, though: unlike `.deb`, the rpm `.spec` template already has native
`License:` and `URL:` tags, so `rpmLicenseType` and `--about-url` alone are enough for both
`rpm -qi` and PackageKit-based software centers to show correct values. The same limitation
applies to `Comment[ja]=`: the rpm's own `.desktop` entry only ever carries the unlocalized
`Comment=` (from the shared `description` above), since adding a localized variant would need the
same kind of `.rpm` unpack/repack this project already declined to depend on for metainfo.

AppStream metainfo requires the `<launchable type="desktop-id">` file to actually exist under
`/usr/share/applications` after install for the entry to validate and link correctly, which is
why `linux { shortcut = true }` is load-bearing here too: without it, jpackage's own
`DesktopIntegration` never emits a `.desktop` file into the `.deb` at all (its condition is "has a
shortcut or a file association"), leaving the injected metainfo's `<launchable>` pointing at
nothing. This does not change how the `keryx://` custom URI scheme is registered — see below.

### Linux Snap package

Unlike `.deb`/`.rpm` (built by jpackage via the tasks above), the Snap is built by `snapcraft`
directly from `snap/snapcraft.yaml`, which `dump`s the same `createDistributable` app image
(`composeApp/build/compose/binaries/main/app/Keryx`) rather than rebuilding anything — so run
`createDistributable` first:

```bash
./gradlew :composeApp:createDistributable
sudo snap install snapcraft --classic   # if not already installed
platform=amd64   # arm64 on an arm64 host
sudo env "PATH=$PATH" snapcraft pack --destructive-mode --platform "$platform"
```

Unlike deb/rpm (see "Linux package metadata" above), `snap/snapcraft.yaml` needs no Gradle-side
help to carry its license and links: `license: MIT` and the `website` / `contact` / `issues` /
`source-code` top-level keys are read directly by the Snap Store / `snap info` from the file
itself — no AppStream metainfo or extra build step involved. `contact` and `issues` accept either
an email address or a URL; `website` and `source-code` are URL-only (see the
[Snapcraft reference](https://ubuntu.com/docs/snapcraft/stable/reference/snapcraft-yaml/) for the
exact key types).

`--destructive-mode` builds directly on the host with no sandboxing, so the host itself
must match `snap/snapcraft.yaml`'s `base: core24` (Ubuntu 24.04) and the command needs
root access — and it can modify the host environment. `platforms:` declares more than one
entry (`amd64` and `arm64`), and destructive mode can only ever produce one snap per run, so
`--platform` above must name the one matching the host's own architecture. CI (`release.yml`) already runs it
in a dedicated `package-snap` job pinned to `ubuntu-24.04`/`ubuntu-24.04-arm` (one matrix
leg per architecture — not `ubuntu-latest`, which would silently drift away from
`base: core24` whenever GitHub retargets that label to a newer LTS). Bump `base:` and
both `runs-on:` values together. For a local build on a different
host, use `snapcraft pack --use-lxd` instead, which builds inside an isolated LXD
container — this needs LXD installed, initialized, and accessible to the current user first:

```bash
sudo snap install lxd
sudo usermod -a -G lxd "$USER" && newgrp lxd   # logging out and back in also works instead of newgrp
sudo lxd init --auto
snapcraft pack --use-lxd
```

`confinement: strict` (Ubuntu's default for Store distribution) means the app only gets the
plugs declared in `snap/snapcraft.yaml` plus any [extensions](https://snapcraft.io/docs/supported-extensions).
To avoid manually enumerating every X11 client library, font stack, and GTK dependency that
AWT/Swing, FlatLaf, Skiko, and WebKitGTK need, the snap uses the `gnome` extension, which
stages the common GNOME/GTK runtime libraries automatically. The `gpu-2404` content
interface (plug) provides Mesa GPU drivers without bloating the snap with `libllvm17`
(~100 MB) that would come from staging `libgl1-mesa-dri` directly.

Token storage inside the snap does **not** go through `java-keyring`/Secret Service the way the
deb/rpm builds do, and the snap declares **no `password-manager-service` plug at all**. That
interface is not auto-connected by snapd policy, and Snapcraft's reviewers decline auto-connect
requests for it on principle — it would grant a snap access to every secret in the user's
session, not just its own (see recent forum outcomes such as the
[NordPass](https://forum.snapcraft.io/t/nordpass-auto-connection-request-to-password-manager-service/50469)
request and others in
[store-requests › privileged-interfaces](https://forum.snapcraft.io/c/store-requests/privileged-interfaces/27),
all declined with "use the Secret portal instead") — but declaring the plug anyway, for a user to
manually `snap connect`, would be pointless here regardless of that policy: `LibSecretTokenStorage`
is used for every provider inside the snap (wired in `PlatformModule.desktop.kt`'s
`providerTokenStorage`, gated on `platform.isSnap`), and it never falls through to raw Secret
Service — `KeyringTokenStorage` (the class deb/rpm use for that) is simply not reachable from
inside the snap at all, by design. So the plug would sit declared without anything in the app ever
using the access it grants, needlessly widening the snap's declared privilege for no benefit.

`LibSecretTokenStorage` calls libsecret directly via JNA rather than going through
`org.freedesktop.secrets`. libsecret itself detects the snap sandbox (via `SNAP_NAME`) and
transparently routes through `org.freedesktop.portal.Secret` instead, storing the actual token
JSON in a local file it encrypts with a per-app master secret it obtains from that portal — access
to the portal comes from the `desktop` plug the `gnome` extension already adds, so neither a
privileged interface nor a manual `snap connect` is needed. Whenever libsecret (or the portal)
cannot be reached, `LibSecretTokenStorage` falls back to the same permission-restricted plaintext
file every platform already uses when the OS store is unavailable, see `SECURITY.md` — this is not
silent: `CloudSession` raises a notification-center warning, see `error-design.md`. In that
(expected to be rare, given the portal is part of the desktop baseline the `gnome` extension
already requires) case there is no privileged-interface fallback to reach for — updating
`xdg-desktop-portal` and its desktop-specific backend is the actual fix, which is what the
warning's own detail text says.

**Manual verification (no CI coverage — `ci.yml` never builds the Snap):** that the `gnome`
extension's platform snap actually resolves `libsecret-1.so.0` at runtime is a runtime-only
assumption (the lint step can't see a `dlopen`, see the `lint.ignore` comment below). Before a
release, `snapcraft pack --destructive-mode` (or `--use-lxd`) → `snap install --dangerous` the
result, connect a cloud provider, and confirm (a) no plaintext-fallback warning appears in the
notification center, and (b) that this holds **without** ever running
`snap connect keryx:password-manager-service` (there is nothing to connect — see above). If
libsecret failed to resolve, `stage-packages: [libsecret-1-0]` under `parts.keryx` is the fix.

`home` is what lets the OPML import/export file picker (`JFileChooser`, see
`app-architecture.md`) reach non-hidden files anywhere under the user's home directory — but it
explicitly excludes hidden files and directories, so it could never let the `keryx://` URI scheme
and `.opml` association self-registration described below
(`LinuxUriSchemeRegistrar`/`LinuxOpmlAssociationRegistrar`) reach the host's
`~/.local/share/applications` and `~/.config/mimeapps.list`. In the snap they never even reach for
them: both registrars resolve their targets from `XDG_DATA_HOME` / `XDG_CONFIG_HOME`, and under
the `gnome` extension those already point into the snap's own writable area (see the next
paragraph), so the writes *succeed* — into a private directory no host desktop ever reads. Either
way the self-registration has no effect on the host, and neither outcome crashes (a failure is
caught and logged as a warning). The Snap build's host-side registration instead comes from
`snap/gui/keryx.desktop` itself declaring `MimeType=` for both `x-scheme-handler/keryx` and the
`.opml` MIME types plus an `Exec=keryx %u` field code — the mechanism snapd processes at install time. A `file://` URI that
some desktop environments hand to `%u` for a local file is normalized back to a plain path in
`main()` (`normalizeFileUriArg`) before classification.

The app also writes its own data (database, settings, lock file, and log file) under
`~/.local/share`, which the strict `home` plug blocks just the same, so those XDG variables have
to be remapped into the snap's own writable area or the app cannot even start. The `gnome`
extension already does most of that: its `snap/command-chain/desktop-launch` unconditionally
exports `XDG_CONFIG_HOME=$SNAP_USER_DATA/.config`,
`XDG_DATA_HOME=$SNAP_USER_DATA/.local/share` and `XDG_CACHE_HOME=$SNAP_USER_COMMON/.cache`.

`$SNAP_USER_DATA` (`~/snap/keryx/<revision>`) is the wrong home for the database, though: it is
revision-scoped, so snapd copies the whole directory on every `snap refresh` and rolls it back on
`snap revert` — taking the article database, and the sync bookkeeping in `sync_state`, with it. An
`environment:` entry cannot move it, because snapd applies that block *before* the command chain
runs and `desktop-launch`'s own `export` then wins. `snap/snapcraft.yaml` therefore declares its
own `command-chain` entry, `bin/keryx-xdg-launch` (staged from `snap/local/keryx-xdg-launch`,
which must keep its executable bit), which snapcraft appends *after* the extension's; it
re-exports `XDG_DATA_HOME=$SNAP_USER_COMMON/.local/share` — `~/snap/keryx/common`, shared by every
revision — and then execs the app. `XDG_CACHE_HOME` is deliberately left alone, since the
extension already points it at `$SNAP_USER_COMMON`. `AppDirs.desktop.kt` reads these environment
variables, so no source code change is needed.

WebKitGTK's nested sandbox (`bwrap`) cannot start inside strict confinement, so
`WEBKIT_DISABLE_SANDBOX=1` is set in the app's environment block. This disables the
renderer sandbox for the article reader's WebView only; the snap's own strict confinement
still isolates the process from the host.

Manual `stage-packages` is down to two entries — the AWT `libxtst6` extension and `libffi8`
for JNA — because everything else is covered by the `gnome` extension.

**WebKitGTK in particular must not be staged.** The `gnome-46-2404` platform snap the extension
plugs into already ships `libwebkit2gtk-4.1-0` along with the `libjavascriptcoregtk-4.1-0` /
`libsoup-3.0-0` / `libsecret-1-0` it depends on; the extension's launcher puts that snap's
`usr/lib/<triplet>` on `LD_LIBRARY_PATH`, and it also adds a `layout` binding
`/usr/lib/<triplet>/webkit2gtk-4.1` — the injected bundle plus the
`WebKitWebProcess`/`WebKitNetworkProcess` helpers — to the platform's copy no matter what the
snap itself stages. Staging our own copy therefore duplicates a library that is already mounted
(and pairs our `.so` with the platform's helper processes, which only works while the two
versions happen to agree), while pulling in WebKitGTK's entire apt dependency closure —
GStreamer's base/good plugin sets, `libicu74`, `libvpx`, `libwoff1`, `libenchant`, … — which by
itself roughly doubled the size of the `.snap` against the equivalent `.deb`.

`snapcraft pack` also runs a set of built-in linters, and two of its findings are worth
explaining rather than "fixing":

- The `library` linter only inspects ELF `DT_NEEDED` entries, so it cannot see libraries
  loaded at runtime via `dlopen()` — it reports the JVM's own runtime libraries
  (`lib/runtime/lib/*.so`, `lib/libapplauncher.so`) as "unused library". These are false
  positives snapcraft's own documentation says not to act on; removing any of them would break
  the app (`libfontmanager.so` in particular is the file the harfbuzz dependency fix in
  `0394c79e` was for). `snap/snapcraft.yaml`'s `lint.ignore` suppresses these specific paths.
- **That suppression also disables the linter's *missing*-dependency detection for the
  same paths** — the check that previously caught the X11/font gap (`88ceff7e`) and the
  harfbuzz gap (`0394c79e`). Whenever `stage-packages` or the bundled JDK version changes,
  comment out the `lint:` block in `snap/snapcraft.yaml` and re-pack once to confirm no new
  missing-dependency warnings appear, then restore it.
- The `metadata` linter's "title is missing" finding is real (unlike the library ones) and
  is fixed by the top-level `title: Keryx` key — the display name shown in the Snap
  Store / GNOME Software, separate from `snap/gui/keryx.desktop`'s `Name=` used by the
  desktop shell.

**Benign startup log lines under strict confinement.** A few lines that look like errors at
launch are expected and need no fix, seen especially inside a GPU-less VM guest (e.g. VMware) or
a host where no GL stack is reachable from the sandbox:

- `[SKIKO] warn: Fallback to next API` followed by `org.jetbrains.skiko.RenderException: Cannot
  create Linux GL context`, then a run of `libEGL warning: ... DRI3 ...` / `... failed to create
  dri2 screen` / `VMware: No 3D enabled`: Skiko (Compose's Skia renderer) tried hardware-accelerated
  GL first and fell back to software rendering because no GPU is reachable — inside a VM without
  3D-accelerated `virtio`/`vmwgfx` passthrough, or on a host where the `gpu-2404` content interface
  isn't connected. The first line is that fallback itself succeeding; the app still renders
  correctly, just off the CPU.
- `Could not open /sys/class/dmi/id/chassis_type` / `/sys/firmware/acpi/pm_profile:
  Permission denied`: GLib/GTK probing hardware chassis info (used elsewhere to guess a
  tablet/convertible form factor), blocked by snapd's device cgroup under strict confinement.
  GTK already handles a missing answer here gracefully; nothing in this app reads either path.
- `GDBus.Error:org.freedesktop.portal.Error.NotAllowed: This call is not available inside the
  sandbox`: an underlying native toolkit (GTK/AWT) probing an xdg-desktop-portal call the strict
  sandbox doesn't expose. Keryx's own file dialogs and menus go through `JFileChooser` /
  `java.awt.FileDialog` / AWT popups (see "UI Direction" in `external-spec.md`), never a portal,
  so this is not the app's own call failing.

The desktop runtime's `slf4j` provider is `slf4j-jdk14` (`composeApp/build.gradle.kts`,
`gradle/libs.versions.toml`), routing dbus-java's own logging — and any other third-party `slf4j`
caller — through `java.util.logging`, where `Log.desktop.kt` installs its own formatter/handlers on
the JUL root logger. This is why third-party log lines (e.g. dbus-java's own
`TransportBuilder - Using transport dbus-java-transport-native-unixsocket`) land in `keryx.<n>.log`
with the same format as the app's own, rather than going to stderr in a different format.

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

`androidApp/build.gradle.kts`'s `versionCodeOf` folds `appVersion` into Android's single,
strictly-increasing `versionCode` integer: `MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 +
preReleaseOrdinal`, where `preReleaseOrdinal` comes from an optional SemVer pre-release label
(`-alpha`, `-beta.2`, `-rc.1`, ...) — `0` for a bare `alpha`, `0 + N` (`N` in `1..29`) for
`alpha.N`, `30`/`30 + N` for `beta`/`beta.N`, `60`/`60 + N` for `rc`/`rc.N`, or `99` with no
suffix at all. A bare label gets its own ordinal rather than defaulting to `.1`'s, since SemVer
itself orders `v1.2.0-alpha` strictly before `v1.2.0-alpha.1`:

| Tag | `versionCode` |
| --- | --- |
| `v1.2.0-alpha` | `1020000` |
| `v1.2.0-alpha.1` | `1020001` |
| `v1.2.0-beta.1` | `1020031` |
| `v1.2.0-rc.1` | `1020061` |
| `v1.2.0` | `1020099` |

This keeps every pre-release of a given `MAJOR.MINOR.PATCH` strictly below its own eventual final
release's `versionCode`, and the alpha → beta → rc → final progression itself strictly increasing —
Google Play rejects a re-upload at a `versionCode` it has already seen, so a scheme that let two
tags collide could never actually publish both of them to a test track. `ci.yml`'s "Verify Android
versionCode scheme" step is a regression guard against exactly that (see `printAndroidVersionCodes`,
a plain stdout probe task since `versionCodeOf` lives in this `*.gradle.kts` script and has no test
source set of its own).

Debug variants do not take their `versionCode` from `appVersion` at all: `androidApp/build.gradle.kts`
pins every debug output to a fixed `debugVersionCode` (2,000,000,000 — below Play's ceiling, above
anything `versionCodeOf` can fold to, since `MAJOR` is capped at 1999). A local build passes no
`-PappVersion`, so it would otherwise be a low `versionCode` and the package manager would reject
`installGithubDebug` as a downgrade over any real-version APK already on the device. Release variants
are unaffected. See [setup.md](setup.md)'s "Common Issues" for the install failure this leaves — a
release-signed and a debug-signed APK still cannot replace each other.

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

The `playRelease` variant as a whole — `:androidApp:bundlePlayRelease`'s AAB, and any APK
`assemblePlayRelease` produces too — additionally accepts a second, **optional** signing identity
under the same three-source priority — the *upload* key, distinct from the app signing key above:

| `local.properties` key | `-P` property | Environment variable |
| --- | --- | --- |
| `android.upload.keystore.path` | `androidUploadKeystorePath` | `ANDROID_UPLOAD_KEYSTORE_PATH` |
| `android.upload.keystore.password` | `androidUploadKeystorePassword` | `ANDROID_UPLOAD_KEYSTORE_PASSWORD` |
| `android.upload.key.alias` | `androidUploadKeyAlias` | `ANDROID_UPLOAD_KEY_ALIAS` |
| `android.upload.key.password` | `androidUploadKeyPassword` | `ANDROID_UPLOAD_KEY_PASSWORD` |

With none of these four set, `playRelease` simply signs with the app signing key instead — a
legitimate choice for a local, unpublished build. `release.yml` and `publish-play.yml` require all
four regardless (via `-PandroidReleaseSigningRequired=true`), since this project has a dedicated
upload key registered with Play and an AAB signed any other way is not publishable — see
"Publishing to Google Play" below for why this project uses a separate upload key at all.

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

The deb/rpm package now ships a system `.desktop` file (`linux { shortcut = true }`, added for
AppStream's `<launchable>` — see "Linux package metadata" above), but it still does **not**
register the `keryx://` custom URI scheme: jpackage's own `.desktop` template has no `%u` on its
`Exec` line, so the URI would never reach the process that way regardless. Instead the app
registers itself on first launch (`LinuxUriSchemeRegistrar`), writing
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
  `com.reederapp.opml`, and Overcast uses `unofficial.opml`. Exporting a Keryx-owned UTI for `.opml`
  (`works.merc.keryx.opml`) instead of these would make Keryx invisible in Finder's "Open With" menu
  on any Mac where another app has already claimed the `.opml` extension for one of these
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

`.github/workflows/release.yml` builds the packages and attaches the installers to the GitHub
Release — **macOS (arm64), Linux (x86_64 and arm64), Windows (x86_64), and Android (universal
APK)** (cross-compilation is not supported, so each architecture needs its own runner) — and
separately **publishes the Android AAB to Google Play** (see "Publishing to Google Play" below).
The AAB itself is never attached to the GitHub Release: neither a user nor the in-app updater can
install one (`selectUpdateAsset` never matches a `.aab`), so the only place it belongs is Play.

Linux arm64 ships with the same caveat noted in `known-issues.md`: the article reader's web-view
library ships no `linux-aarch64` binary, so on that architecture the reader falls back to a
Compose-drawn simplified view instead of using the native web view (never a freeze — the fallback
covers block structure, inline decorations, and images).

**Workflow lint.** Most of what this section describes (`release.yml`, `publish-play.yml`,
`.github/scripts/`) only ever runs when a release is cut, so `.github/workflows/lint-workflows.yml`
checks it statically on every push that touches `.github/**`: `shellcheck` (preinstalled on
`ubuntu-latest`) over `.github/scripts/*.sh`, and `actionlint` over every workflow — undefined
`inputs`/`matrix`/`needs` references, unknown action inputs, expression type errors, and each `run:`
block through shellcheck. It cannot catch a misspelled `steps.<id>.outputs.<name>`, since step
outputs are only written at run time through `$GITHUB_OUTPUT`. actionlint is not preinstalled, so
the workflow downloads a pinned release tarball and verifies it against a hardcoded SHA-256; to
upgrade, update `ACTIONLINT_VERSION` and `ACTIONLINT_SHA256` together (the digest is listed in that
release's `actionlint_<version>_checksums.txt`). Run the same checks locally, with no install, via
Docker (keep the image tag in step with `ACTIONLINT_VERSION`):

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12
docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:stable .github/scripts/*.sh
```

Flow:

1. Publish a GitHub Release with a `vMAJOR.MINOR.PATCH` tag, optionally with a SemVer-style
   pre-release suffix (e.g. `v0.1.0`, `v1.2.0-beta.1`).
2. The workflow triggers on `release: published`, strips the leading `v`, and passes the result as `-PappVersion`.
3. Seven job definitions in total, five of which (`package-macos`, `package-linux`, `package-snap`,
   `package-windows`, `package-android`) run in parallel — seven actual runs, since `package-linux`
   and `package-snap` are each an `x86_64`/`arm64` matrix (`ubuntu-latest`/`ubuntu-24.04-arm` and
   `ubuntu-24.04`/`ubuntu-24.04-arm` respectively; the arm64 legs run `android-actions/setup-android@v3`
   first, since `:composeApp`'s Android target needs `ANDROID_HOME` merely to configure, and the
   `ubuntu-24.04-arm` image — unlike `ubuntu-latest` — ships no Android SDK at all). The remaining
   two are not part of that parallel set: `publish-play` depends only on `package-android` (see its
   own bullet below), and `deploy-pages` is gated on the four non-Snap `package-*` jobs
   (nine actual runs in total; see below):

   - `:composeApp:createDistributable :composeApp:packageDmg` (macOS runner — `createDistributable` is requested explicitly, alongside `packageDmg`, to still produce the app bundle the `.zip` below is made from), attached as `Keryx-<version>-macos-arm64.dmg` **and `Keryx-<version>-macos-arm64.zip`**. **For a pre-release tag, `packageDmg` is skipped and only `createDistributable` runs, so only the `.zip` is attached** (same reasoning as the Windows MSI case below).
   - `:composeApp:packageDeb :composeApp:packageRpm` (Linux runner, once per architecture, after installing `fakeroot`/`rpm` for jpackage), attached as `Keryx-<version>-linux-<arch>.deb`, `Keryx-<version>-linux-<arch>.rpm` **and `Keryx-<version>-linux-<arch>.zip`** for `<arch>` in `x86_64`, `arm64`. **For a pre-release tag, `packageDeb`/`packageRpm` are skipped and only the `.zip` is attached** (same reasoning as the Windows MSI case below). A failure on one architecture's leg (`fail-fast: false`) does not withhold the other's assets.
   - `package-snap`, a separate job (also an `x86_64`/`arm64` matrix, `ubuntu-24.04`/`ubuntu-24.04-arm`)
     so a `snapcraft` failure on either architecture can never block the deb/rpm/zip job above from
     reaching the release (see "Linux Snap package" above for why it also needs its own
     `ubuntu-24.04`-family runner rather than `ubuntu-latest`).
     - **Build and attach.** It runs
       `sudo snapcraft pack --destructive-mode --platform <platform> --output "Keryx-$VERSION-linux-<arch>.snap"`
       against `snap/snapcraft.yaml` (after `sudo snap install snapcraft --classic`) and attaches the
       resulting `Keryx-<version>-linux-<arch>.snap` — unlike `.deb`/`.rpm`, this **is** attached for
       pre-release tags too, since snapcraft's `version:` field isn't restricted to
       `MAJOR.MINOR.PATCH` the way jpackage's packaging metadata is. `--platform` (`amd64` or `arm64`,
       snapcraft's own vocabulary — `<arch>` in the output filename spells it `x86_64`/`arm64` to
       match the `.zip`/`.deb`/`.rpm` convention instead) is required as soon as `snapcraft.yaml`
       declares more than one entry under `platforms:` (core24's replacement for the old
       `architectures:` key): `--destructive-mode` builds directly on the unsandboxed host and
       refuses to produce more than one snap per invocation, so each matrix leg must say which
       declared platform it is building.
     - **Snap Store publish.** After the GitHub Release attachment, the same job also **publishes
       the snap to the Snap Store** (`snapcraft upload --release=<channel>`, gated on the
       `SNAPCRAFT_STORE_CREDENTIALS` secret below being set at all) — once per architecture, each
       creating its own revision under that channel.
     - **Channel selection.** The channel is `edge` when the tag carries a pre-release suffix **or**
       the GitHub Release itself is marked as a pre-release, and `stable` otherwise (the deb/rpm/msi
       skip checks above key on the tag suffix alone; only the Snap Store channel also honours the
       Release's own pre-release flag, since a snap mis-channelled to `stable` is pushed to every
       Store user by snapd's own auto-refresh with no way to recall it).
   - `:composeApp:createDistributable :composeApp:packageMsi` (Windows runner — `windows-latest` ships a compatible WiX Toolset version (v3/v4/v5) preinstalled, so no separate WiX setup step is needed; see [setup.md](setup.md)), attached as `Keryx-<version>-windows-x86_64.msi` **and `Keryx-<version>-windows-x86_64.zip`**. **For a pre-release tag, `packageMsi` is skipped and only the `.zip` is attached** — MSI's `ProductVersion` must be purely numeric (see below), so every pre-release of a given target version would collapse to the same `ProductVersion` under the fixed `upgradeUuid`, and WiX would not recognize a later pre-release or the eventual final release as an upgrade of an earlier one.
   - `:androidApp:assembleGithubRelease` and `:androidApp:bundlePlayRelease` (Ubuntu runner), building
     the APK from the `github` flavor (carries `REQUEST_INSTALL_PACKAGES`, since it's the one an
     in-app update installs over — see the "Android (APK / AAB)" section above) and the AAB from
     `play` (the Play Console submission artifact, which must not carry that permission). Unlike
     the desktop installers, Android packages are built for pre-release tags too, because Android has
     no equivalent version-metadata restriction — `versionCodeOf` (see "Android (APK / AAB)" above)
     keeps every pre-release version distinct on its own — and testers need a signed APK.
     - **Build and attach.** Only the APK is attached to the GitHub Release, as
       `Keryx-<version>-android-universal.apk` — the AAB is never attached (see the note at the top
       of this section for why); instead it is uploaded as a build artifact
       (`actions/upload-artifact`) for the separate `publish-play` job below to consume.
   - `publish-play`, a separate job (needs `package-android`, so it starts only once that job's AAB
     artifact exists) that downloads that artifact and **publishes it to Google Play**
     (`.github/scripts/publish-play.sh`, one atomic Play Developer API edit that uploads the AAB
     once and assigns it to every configured track), gated on the `PLAY_SERVICE_ACCOUNT_JSON` secret below being set
     at all — same skip-if-unconfigured pattern as the Snap Store publish above. Kept as its own job
     (rather than a step inside `package-android`) so a Play publish failure never withholds the APK
     already attached to the GitHub Release, the same reasoning as `package-snap`'s own separation
     from `package-linux`. See "Publishing to Google Play" below for the full setup and the tracks
     this targets.

   `deploy-pages` (triggers the Cloudflare Pages deploy hook for the download page) waits on
   `package-macos` / `package-linux` / `package-windows` / `package-android`, but deliberately
   **not** `package-snap` — a Store publish delay shouldn't hold back updating the page once every
   other installer is already live.

   The `.zip` files are archives of the non-packaged app bundle/image produced by `:composeApp:createDistributable`, for users who prefer not to use an installer package.

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
never re-hashes `Info.plist` at launch — so ordinary manual smoke-testing of a downloaded ZIP cannot catch a
broken seal; the build-time verify is the only thing that does. The DMG is unaffected either way, because
jpackage re-signs its own copy of the app image while building it — only the ZIP asset, made straight from
`binaries/main/app`, can carry a broken seal.

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

For Snap Store publishing, register the snap name once (`snapcraft register keryx`) and set
`SNAPCRAFT_STORE_CREDENTIALS` as a repository secret — the value comes from `snapcraft export-login`
(scoped to the `keryx` snap with the `package_access,package_push,package_update,package_release`
ACLs), not a plain username/password. Like the cloud-provider keys above, an unset secret does not
fail the build: the "Publish to Snap Store" step is skipped entirely (the `.snap` is still built and
attached to the GitHub Release), so the workflow only starts publishing once this secret is
configured.

No `password-manager-service` auto-connect request is filed after publishing — see "Linux Snap
package" above for why (Snapcraft reviewers decline this interface's auto-connect on principle)
and for the `LibSecretTokenStorage`/Secret-portal path used instead, which needs no such request.

For Android release signing, set `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`,
`ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`, and (optionally, see "App signing key
vs. upload key" below) `ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`,
`ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD` as repository secrets. Each keystore is a
Base64-encoded PKCS12/JKS file; the workflow decodes both at build time.

**Enrolling the app signing key with Google Play (one-time).** Generate the keystore locally and,
when creating the app in Google Play Console, enroll it as the **existing app signing key**: Play
Console never accepts the raw JKS/PKCS12 file directly — first encrypt it with Google's PEPK (Play
Encrypt Private Key) tool
(`java -jar pepk.jar --keystore=<path> --alias=<alias> --output=<encrypted-file> --encryptionkey=<key-from-play-console>`,
downloaded from the Play App Signing enrollment page), then upload the resulting encrypted file.

**App signing key vs. upload key.** Google re-signs every APK with the **app signing key** just
enrolled before it ever reaches a device — that re-signed identity is what has to be consistent
everywhere, not the key a given artifact happened to be built with. This project takes advantage of
that: `githubRelease`'s APK is signed directly with the app signing key (so it already carries the
identity a device will see, matching what sideloading needs), while `playRelease`'s AAB is signed
with a **separate upload key** instead — the key Play's own signing-config UI shows as the "upload
key certificate" once one is registered, distinct from the "app signing key certificate" alongside
it. Google explicitly allows reusing the app signing key as its own upload key — for a *local*,
unpublished build, simply not setting the four `ANDROID_UPLOAD_*` values does that (see
`androidApp/build.gradle.kts`'s `signingConfigs`) — but registering a dedicated upload key is
Google's recommended hardening, and it is what this project actually does. Because a dedicated
upload key is registered with Play Console, that fallback is not an option once publishing is
actually involved: Play only recognizes an AAB signed with the registered upload key certificate
(or the app signing key certificate, if no upload key were ever registered) and rejects anything
else, so the two publishing workflows require the dedicated key below rather than allowing the
fallback.

**All eight signing secrets are required together for anything that publishes.** `release.yml` and
`publish-play.yml` both pass `-PandroidReleaseSigningRequired=true`, which turns a missing or
half-configured app-signing secret into an immediate build failure (as always), and — separately —
*any* incomplete upload secret, half-configured or fully unset, into the same failure: this project
has a dedicated upload key registered with Play, so a `playRelease` AAB signed with anything else
(the app-signing-key fallback included) is not a publishable artifact, only a locally useful one.
Either way, this workflow must never succeed with an unsigned artifact or a half-formed signing
identity. Outside that flag — a plain local `./gradlew build`/`bundlePlayRelease` — the four
`ANDROID_UPLOAD_*` values stay optional and the app-signing-key fallback above still applies.

**What has to match across channels is the identity Play re-signs to, not the build-time key.**
`androidApp/build.gradle.kts`'s `signingConfigs` block is not flavor-scoped by itself — `playRelease`
is repointed at the upload key through the `androidComponents` variant API instead (see that file),
leaving `githubRelease` on the app signing key unchanged. This is why the two channels stay
compatible for an in-place update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` otherwise): a device already
running the GitHub APK sees Play's own install as coming from that same app signing key, because
Play re-signed it that way — never mind that the AAB was uploaded signed with something else
entirely.

`ci.yml`'s ordinary build job never receives these secrets — deliberately, since it runs on every
push and never publishes anything. AGP wires `assembleRelease` into `:androidApp`'s default
`build` task regardless of whether the artifact is ever consumed (`bundlePlayRelease` is not part of any
aggregate lifecycle task, which is why `release.yml` above invokes it explicitly), but
`androidApp/build.gradle.kts`'s `signingConfigs` block treats a completely unconfigured signing
identity as the unsigned-release case (a build warning, not a failure — see "Android release
signing keystore" in [setup.md](setup.md)) rather than requiring `androidReleaseSigningRequired`.
So plain `./gradlew build` — in CI or locally — needs no keystore at all; only a workflow that
actually distributes the result (`release.yml`, and `publish-play.yml` below) opts into hard
failure instead.

### Publishing to Google Play

**One-time Google Cloud / Play Console setup**, done once by whoever administers this project's
Play Console listing (`works.merc.keryx`):

1. Enable the Google Play Android Developer API for a Google Cloud project
   (`console.cloud.google.com` → APIs & Services → Library → "Google Play Android Developer API").
2. Create a service account in that same project (IAM & Admin → Service Accounts). It needs no GCP
   role at all — Play Console grants its own permissions separately in the next step. Generate a
   JSON key for it (Keys tab → Add key → JSON) and keep the file.
3. In Play Console → Users and permissions → Invite new users, add the service account's email,
   open the "App permissions" tab, add this one app, and grant it the
   **"Release apps to testing tracks"** permission (production access can be added later, once
   this account's own product-level access is approved — see below).
4. Set the JSON key's full file contents as the `PLAY_SERVICE_ACCOUNT_JSON` repository secret. Both
   `release.yml` and `publish-play.yml` read it; `release.yml`'s own publish step is skipped
   entirely for as long as this secret is unset, the same skip-if-unconfigured pattern
   `SNAPCRAFT_STORE_CREDENTIALS` above uses.
5. Upload one AAB **manually** through the Play Console UI before the first automated publish. The
   Play Developer API can refuse a publish to a package it has never seen a release for at all
   (a precondition failure, not the same thing as the per-track access above), so the very first
   release of the app has to be created by hand.

**Track constants.** `release.yml`'s "Resolve Play track" step hardcodes `PLAY_TRACKS_PRERELEASE`
and `PLAY_TRACKS_STABLE`, each a comma-separated list of Play Developer API track ids — currently
`internal,alpha` for both, i.e. internal testing *and* closed testing (`alpha` is the API's id for
Play Console's closed testing — "closed" itself is not a valid track id; see `publish-play.yml`'s own
`tracks` input). A GitHub Release marked as a pre-release publishes to the former, everything else
to the latter (the same `github.event.release.prerelease` flag `package-snap`'s own
channel-selection step above uses). Neither includes `production`/`beta` because a **personal**
Google Play developer account created on or after 2023-11-13 cannot use "production" or "open
testing" at all until it clears Play's own testing requirement: a closed test with 12 or more
opted-in testers, sustained continuously for 14 days, followed by an approved application for
production access. Once product-level access is approved, change these two constants (e.g. adding
`beta`/`production`) and the workflow needs no further changes.

Play refuses a second upload of a `versionCode` it has already seen, so publishing one build to
several tracks can't be done as one upload per track. `.github/scripts/publish-play.sh` instead
opens a single Play Developer API edit, uploads the AAB once, points every listed track's release
at the resulting `versionCode` (`edits.tracks.update`), and only then commits. If any step fails, the
edit is deleted rather than committed, so a failed publish never leaves one track updated and the
other not. The script calls the API directly with `curl`/`jq`/`openssl` (signing the service
account's OAuth JWT itself) rather than through a third-party action; it validates each track id
before making any request.

**`publish-play.yml`** is a manual `workflow_dispatch` escape hatch for the same publish, given a
`tag` (an existing GitHub Release) and `tracks` to target (comma-separated, default `internal,alpha`)
— useful for publishing to a different set of tracks than the constants above, or for retrying a
failed publish without cutting a new GitHub Release (which would also bump the tag, and therefore
the `versionCode`). It runs the same `publish-play.sh`, taken from the workflow's own ref rather
than from `tag`, so it also works for a tag cut before the script existed. It rebuilds the AAB from that tag rather than reusing anything already
published — a tag deterministically reproduces the same `versionCode` and signing output either
way, and `release.yml` no longer attaches an AAB to the GitHub Release for it to reuse. Neither
workflow *promotes* a `versionCode` Play has already seen to a different track: both only ever
upload a freshly built AAB. The Play Developer API itself can do it — `edits.tracks.update` accepts
an already-uploaded `versionCode` in another track's release — but neither workflow implements
that, so moving an existing release between tracks is a Play Console UI action (or an API call of
your own), not something either workflow does.

**Release notes ("recent changes") come from the GitHub Release body**, reformatted to Play's
plain-text, 500-characters-per-locale limit (headings and bolded bullet titles survive; links,
descriptions, and the generated header/footer lines are dropped). Written identically to both
`whatsnew-en-US` and `whatsnew-ja-JP` — this project's release notes are English-only, and leaving
`ja-JP` absent would make Play silently keep whichever text that locale last had, which is more
misleading than a same-language duplicate. Edit a release's notes for a specific locale afterward
directly in Play Console if a translated version is ever wanted; neither workflow touches an
existing release once published.

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

No special entitlements are required for Keychain access (just ensure `get-task-allow` is not added; jpackage's Developer ID signing uses hardened runtime, which satisfies the requirement). See [sync-architecture.md](sync-architecture.md) "Cloud Authentication (OAuth PKCE + Offline Access) > Token Storage" for token storage details, which covers all three providers, not just Dropbox.

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

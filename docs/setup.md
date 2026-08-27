# Development Environment Setup

[日本語](setup.ja.md)

## Prerequisites

### System Requirements

- **Supported OS**: Windows / macOS / Linux. Since the app runs on the JVM, any environment that
  can run JDK 25 or later can develop it.
- **If Android work is involved** (building `androidApp`, or verifying behavior on a device/
  emulator), follow Android Studio's official system requirements as a practical baseline
  (64-bit OS, 8GB+ RAM, 8GB+ free disk space as a rough guide — allow more headroom when also
  running an emulator). See
  [Android Studio's system requirements](https://developer.android.com/studio/install) for
  current, authoritative numbers.
- **Desktop-target-only work** needs only an environment that can run the JDK and the Gradle
  Wrapper — no extra headroom for the Android SDK or an emulator.

### Recommended IDE

- **[Android Studio](https://developer.android.com/studio)**: fully supports Android development as an
  IntelliJ-based IDE, and can also **run the Compose Multiplatform desktop target directly from a
  Run Configuration**. It covers both desktop and Android in one free IDE, making it the top
  choice for this project. Its bundled SDK Manager can also install the Android SDK described
  below.
- **[IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/download/)**: opens, builds, and runs this
  Gradle-based Kotlin Multiplatform project fine, and is sufficient for desktop-target work.
  JetBrains'
  [Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform)
  is installable on Community Edition too, but it is mainly aimed at iOS preview/run/debug
  support — not needed yet, since this project doesn't target iOS (see
  [external-spec.md](external-spec.md) §2, "Planned"). Android-specific tooling (deploying to a
  device/emulator, layout preview, etc.) is weaker than in Android Studio, so prefer Android
  Studio when the work is mainly on the Android side.
- **[Visual Studio Code](https://code.visualstudio.com/)**: JetBrains released an official "Kotlin by JetBrains" extension in Alpha in 2026,
  but it explicitly does not yet support Kotlin Multiplatform projects. Not recommended for this
  project's development at this time.

### Software Required to Build

Split into what every target needs in common, and what's specific to the Android target.

#### Common

| Software | Purpose | How to install |
| --- | --- | --- |
| **JDK 25 or later** (`JAVA_HOME`) | The JVM that launches `./gradlew` | macOS: `brew install temurin@25`. Windows: `winget install EclipseAdoptium.Temurin.25.JDK`, or the [official installer](https://adoptium.net/installation/). Linux: your distro's package (e.g. `sudo apt install temurin-25-jdk` via Adoptium's apt repository) or [SDKMAN!](https://sdkman.io/) (`sdk install java 25-tem`), etc. |
| **Git** | Cloning the repository | Whatever your OS provides ([git-scm.com](https://git-scm.com/downloads), Xcode Command Line Tools on macOS, your distro's package, etc.) |
| Gradle | Running the build | Uses the bundled Wrapper (`./gradlew`, Gradle 9.6.1) — **no separate install needed** |

#### For the Android Target

- **Android SDK Platform 37** (`compileSdk` / `targetSdk`) and build-tools. Install via Android
  Studio's SDK Manager, or the standalone command-line tools
  ([`cmdline-tools`](https://developer.android.com/tools/sdkmanager),
  `sdkmanager "platforms;android-37" "build-tools;36.0.0"`) — 36.0.0 is the version AGP 9.3.2
  selects by default when none is specified.
- Setup: point `local.properties`' `sdk.dir` at the SDK location (AGP reads this key itself; it
  doesn't go through this project's own `-P`/env-var/`local.properties` resolution chain used for
  the OAuth keys below), or set the `ANDROID_HOME` environment variable instead. A target-scoped
  task like `:composeApp:compileKotlinDesktop` or `:composeApp:desktopTest` works fine without it,
  but the root `./gradlew build` aggregates every subproject including `:androidApp`, so it fails
  immediately at configuration time without a resolvable SDK — see Common Issues below.
- **Android release signing keystore**: Gradle's default `build` lifecycle includes
  `:androidApp`'s `assembleRelease`/`validateSigningRelease`, and `androidApp/build.gradle.kts` is
  deliberately built to **not** fall back to debug signing when signing credentials are missing.
  So **without a keystore, the root `./gradlew build` fails here**. For development, a throwaway
  keystore made with the JDK's own `keytool` is enough:

  ```bash
  keytool -genkeypair -v -keystore "$PWD/keryx-dev.keystore" \
    -alias keryx-dev -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Dev, OU=Dev, O=Dev, L=Dev, S=Dev, C=US" \
    -storepass changeit -keypass changeit
  ```

  Set the generated file's **absolute path** in `local.properties` as
  `android.release.keystore.path` / `android.release.keystore.password` /
  `android.release.key.alias` / `android.release.key.password` (a relative path is resolved
  against the `androidApp` module directory, not the repo root). `.gitignore` already excludes
  `*.keystore` / `*.jks`, so it's safe to keep the file at the repo root — it won't get committed
  by accident. See [build.md](build.md) for how to issue a production keystore for Google Play
  distribution.
- **A connected Android device or running emulator**: only needed to run the `androidDeviceTest`
  instrumented suite (`DatabaseMerger`/`DatabaseSnapshot`'s Android actuals against the real
  bundled SQLite — see [testing.md](testing.md)); building, `./gradlew build`, and every other
  test task work without one. Running the emulator at a usable speed on Linux needs **KVM**
  (hardware acceleration) — see the
  [official guide](https://developer.android.com/studio/run/emulator-acceleration) for setup.
- **The NDK is not needed** (the project builds no native code of its own — don't install it by
  mistake).

#### Linux-Specific

- **Xvfb**: required to get `./gradlew build` through on a headless machine (an SSH session, a
  container, CI, etc.), since `runDesktopComposeUiTest` renders through real Skia/AWT and needs a
  display. Example: `sudo apt-get install -y xvfb`, then `xvfb-run -a --server-args="-screen 0
  1920x1080x24" ./gradlew build`.

### Software Required to Run the App

Runtime dependencies that aren't needed to build, but are needed to actually **launch** the
packaged app (or `:composeApp:run`).

- **Linux: WebKitGTK** (`libwebkit2gtk-4.1-0` on Debian/Ubuntu; `webkit2gtk4.1` on Fedora): used
  by the article reader's native WebView (see [app-architecture.md](app-architecture.md)). Not
  needed to build or test — `ArticleDetailPaneTest` substitutes a stub for the real WebView, so
  building and testing succeed without this dependency.
- **Windows: the WebView2 runtime**: already bundled via Microsoft Edge on Windows 11 and updated
  Windows 10. Only install the
  [Evergreen runtime](https://developer.microsoft.com/microsoft-edge/webview2/) separately if
  it's missing.
- **Linux: a D-Bus session bus** (optional): used by the tray (StatusNotifierItem) and desktop
  notifications. Not required — an environment without one falls back to the AWT-based tray
  automatically.
- **macOS**: no additional software needed (the WebView uses the OS's own WebKit).

### Software Required for Packaging

`:composeApp:run` needs the JDK plus the applicable platform runtime from "Software Required to
Run the App" above (aside from Xvfb on a headless Linux machine); the root `./gradlew build`
additionally needs the Android SDK and a release signing keystore, per above. The native
packaging tasks (`createDistributable`, `packageDmg`,
`packageMsi`, `packageDeb`, `packageRpm` — see [build.md](build.md)) additionally require the
following, per OS:

- **Linux**
  - `fakeroot` — required for `packageDeb` (jpackage shells out to it to build the `.deb`)
  - `rpm` (provides `rpmbuild`) — required for `packageRpm`
- **macOS**
  - Xcode Command Line Tools (`xcode-select --install`), for `SetFile` — required for
    `packageDmg` (sets the DMG volume icon; `hdiutil` itself ships with the OS)
- **Windows**
  - WiX Toolset v3, v4, or v5, on `PATH` — required for `packageMsi` (jpackage's Windows
    installer step). GitHub-hosted `windows-latest` runners ship WiX Toolset v3.14.1
    preinstalled, so both `ci.yml` and `release.yml` build it with no separate install
    step — see [build.md](build.md).

`fakeroot`/`rpm` are not installed by default on `ubuntu-latest`; the release workflow installs
them via `apt-get` right before packaging (`.github/workflows/release.yml`). Xcode Command Line
Tools and WiX Toolset already come preinstalled on the `macos-latest` and `windows-latest` runner
images respectively — a local dev machine still needs whichever of these three it's missing set up
manually.

## First-time Setup

```bash
git clone <repo>
cd keryx
cp local.properties.example local.properties   # Then add sdk.dir (see Prerequisites); the OAuth keys are optional

# Android release signing keystore (required for ./gradlew build — see Prerequisites'
# "Software Required to Build")
keytool -genkeypair -v -keystore "$PWD/keryx-dev.keystore" \
  -alias keryx-dev -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Dev, OU=Dev, O=Dev, L=Dev, S=Dev, C=US" \
  -storepass changeit -keypass changeit
# Add android.release.keystore.path (absolute path) / android.release.keystore.password /
# android.release.key.alias / android.release.key.password to local.properties

./gradlew build
```

If `build` passes, code generation for SQLDelight / Compose Resources / BuildConfig, compilation,
and every test task `build` runs are all verified — for both the desktop and Android targets,
since `build` now also compiles and assembles `:androidApp`. This does not include the
separately-executed `androidDeviceTest` instrumented suite, which needs a real device/emulator
(see Prerequisites above).

For desktop-only work, a target-scoped task like `./gradlew :composeApp:desktopTest` avoids
needing either the Android SDK or a release signing keystore.

## Data Directory

The app's local data (`keryx.db`, `local_settings.json`) is created in the OS-standard location.

| OS | Path |
| --- | --- |
| macOS | `~/Library/Application Support/Keryx` |
| Windows | `%APPDATA%\Keryx` |
| Linux | `$XDG_DATA_HOME/Keryx` (default `~/.local/share/Keryx`) |

To reset data during development, delete `keryx.db` and `local_settings.json` in this directory.

## Common Issues

### `SDK location not found` (at Gradle configuration time)

`composeApp` itself now configures an Android library target
(`com.android.kotlin.multiplatform.library`), so any task that touches its `build` lifecycle —
the root `./gradlew build`, or even `:composeApp:build` alone — needs the Android SDK, not just
`:androidApp`.

Set `sdk.dir` in `local.properties` (see Prerequisites above) or the `ANDROID_HOME` environment
variable. Desktop-only work can avoid this by scoping to a specific desktop task instead, e.g.
`:composeApp:compileKotlinDesktop` or `:composeApp:desktopTest`, neither of which resolves the
Android SDK.

### Build fails at `validateSigningRelease`

Gradle's default `build` lifecycle includes `:androidApp`'s `assembleRelease`/
`validateSigningRelease`, and `androidApp/build.gradle.kts` is deliberately built to not fall back
to debug signing when signing credentials are missing. So the root `./gradlew build` fails here —
even for desktop-only work — unless an Android release signing keystore is in place.

Generate a development keystore per Prerequisites' "Software Required to Build" and set the four
`local.properties` values (`android.release.keystore.path` / `android.release.keystore.password` /
`android.release.key.alias` / `android.release.key.password`), or scope to a target-limited task
like `:composeApp:desktopTest` instead.

### `UnsupportedClassVersionError` (at runtime)

The JVM that launched `./gradlew` is older than 25. Set `JAVA_HOME` to JDK 25+.

### Toolchain download blocked

Add `-Dorg.gradle.java.installations.auto-download=true`.

### (Linux) `./gradlew build` hangs or fails in Compose UI tests on a headless machine

`runDesktopComposeUiTest` renders through real Skia/AWT and needs a display (see Prerequisites'
"Software Required to Build" too).

CI runs under a virtual X server (`sudo apt-get install -y xvfb`, then `xvfb-run -a
--server-args="-screen 0 1920x1080x24" ./gradlew build`); do the same locally if there's no
display server (SSH session, container, etc.).

### Dropbox integration not showing

`DROPBOX_APP_KEY` is not set (hidden by design as a feature). See `build.md`.

### (Android emulator) Dropbox/OneDrive linking opens a page but taps don't respond

The AVD has no real browser installed, so the implicit `ACTION_VIEW` intent resolves to **WebView
Browser Tester** (a WebView diagnostics app, not a functional browser) instead of Chrome — it
renders the OAuth page but doesn't handle interaction correctly.

Recreate the AVD with a **Google Play** system image (not "Google APIs" only — that omits Play
Store and therefore Chrome), or install a real browser APK on the existing AVD.

### `./gradlew :composeApp:run` does not complete Dropbox / OneDrive linking (all desktop OSes)

Their redirect URI uses the custom scheme `keryx://`, and the connect button remains disabled
until timeout. The reason differs per OS:

- **macOS**: LaunchServices routes `keryx://` to the **packaged `Keryx.app`** (via
  `CFBundleURLTypes` in Info.plist), which the `gradlew run` instance is not.
- **Windows / Linux**: the scheme is registered at startup, but only when running from a packaged
  launcher — pointing the OS at the JDK's own `java` binary would leave a broken handler behind
  after the Gradle run ends.

**To test or perform linking, build the app with `./gradlew :composeApp:createDistributable` and
launch it** (terminate the gradle instance first). Saved tokens are stored in the keychain or
`.dropbox_tokens.json` in the data directory. Google Drive uses loopback reception and links fine
under `gradlew run`.

### (Linux) The browser reports an unknown protocol for `keryx://`

The scheme is not registered with the desktop environment. A packaged build registers it on first
launch, writing `$XDG_DATA_HOME/applications/keryx-url-handler.desktop` (default
`~/.local/share/applications/keryx-url-handler.desktop`) and an association in
`$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`).

Verification steps:

1. Run `xdg-mime query default x-scheme-handler/keryx` and confirm it returns
   `keryx-url-handler.desktop`.
2. While Keryx is running, run `xdg-open 'keryx://oauth2/callback?code=test&state=test'` — the
   window should come to the front if it's working end to end.

Note that these two files live in the user's home and are **not removed when the deb/rpm package
is uninstalled**, and there is no uninstall hook to clean them up. This is not harmless: the
surviving `[Default Applications]` entry in `mimeapps.list` keeps pointing `keryx://` at a
launcher path that no longer exists, so `xdg-open` (or a browser resolving the scheme) can fail
until the two are removed manually.

Removal must be done manually: delete `keryx-url-handler.desktop` from the applications
directory, and drop the `x-scheme-handler/keryx` line(s) from `mimeapps.list`.

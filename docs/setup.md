# Development Environment Setup

[日本語](setup.ja.md)

## Prerequisites

- **JDK 25 or later** (`JAVA_HOME`). On macOS, Temurin or Homebrew openjdk is recommended.
- IDE: IntelliJ IDEA / Android Studio (with Kotlin Multiplatform plugin) is recommended.

## First-time Setup

```bash
git clone <repo>
cd kmp
cp local.properties.example local.properties   # Optional: configure Dropbox App Key
./gradlew build
```

If `build` passes, code generation for SQLDelight / Compose Resources / BuildConfig, compilation, and tests are all verified.

## Data Directory

The app's local data (`keryx.db`, `local_settings.json`) is created in the OS-standard location.

| OS | Path |
| --- | --- |
| macOS | `~/Library/Application Support/Keryx` |
| Windows | `%APPDATA%\Keryx` |
| Linux | `$XDG_DATA_HOME/Keryx` (default `~/.local/share/Keryx`) |

To reset data during development, delete `keryx.db` and `local_settings.json` in this directory.

## Packaging Prerequisites

`./gradlew build` / `:composeApp:run` need nothing beyond the JDK (aside from Xvfb on a headless
Linux machine — see below). The native packaging tasks (`createDistributable`, `packageDmg`,
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

## Common Issues

- **`UnsupportedClassVersionError` (at runtime)**: The JVM that launched `./gradlew` is older than 25. Set `JAVA_HOME` to JDK 25+.
- **Toolchain download blocked**: Add `-Dorg.gradle.java.installations.auto-download=true`.
- **(Linux) `./gradlew build` hangs or fails in Compose UI tests on a headless machine**:
  `runDesktopComposeUiTest` renders through real Skia/AWT and needs a display. CI runs under a
  virtual X server (`sudo apt-get install -y xvfb`, then `xvfb-run -a --server-args="-screen 0
  1920x1080x24" ./gradlew build`); do the same locally if there's no display server (SSH session,
  container, etc.).
- **Dropbox integration not showing**: `DROPBOX_APP_KEY` is not set (hidden by design as a feature). See `build.md`.
- **`./gradlew :composeApp:run` does not complete Dropbox / OneDrive linking (all desktop OSes)**: Their redirect URI uses the custom scheme `keryx://`, and the connect button remains disabled until timeout. The reason differs per OS. macOS: LaunchServices routes `keryx://` to the **packaged `Keryx.app`** (via `CFBundleURLTypes` in Info.plist), which the `gradlew run` instance is not. Windows / Linux: the scheme is registered at startup, but only when running from a packaged launcher — pointing the OS at the JDK's own `java` binary would leave a broken handler behind after the Gradle run ends. **To test or perform linking, build the app with `./gradlew :composeApp:createDistributable` and launch it** (terminate the gradle instance first). Saved tokens are stored in the keychain or `.dropbox_tokens.json` in the data directory. Google Drive uses loopback reception and links fine under `gradlew run`.
- **(Linux) The browser reports an unknown protocol for `keryx://`**: the scheme is not registered with the desktop environment. A packaged build registers it on first launch, writing `$XDG_DATA_HOME/applications/keryx-url-handler.desktop` (default `~/.local/share/applications/keryx-url-handler.desktop`) and an association in `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`). Verify with `xdg-mime query default x-scheme-handler/keryx` (expect `keryx-url-handler.desktop`) and test end to end with `xdg-open 'keryx://oauth2/callback?code=test&state=test'` while Keryx is running — the window should come to the front. Note that these two files live in the user's home and are **not removed when the deb/rpm package is uninstalled**, and there is no uninstall hook to clean them up. This is not harmless: the surviving `[Default Applications]` entry in `mimeapps.list` keeps pointing `keryx://` at a launcher path that no longer exists, so `xdg-open` (or a browser resolving the scheme) can fail until the two are removed manually — delete `keryx-url-handler.desktop` from the applications directory and drop the `x-scheme-handler/keryx` line(s) from `mimeapps.list`.

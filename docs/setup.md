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

## Common Issues

- **`UnsupportedClassVersionError` (at runtime)**: The JVM that launched `./gradlew` is older than 25. Set `JAVA_HOME` to JDK 25+.
- **Toolchain download blocked**: Add `-Dorg.gradle.java.installations.auto-download=true`.
- **Dropbox integration not showing**: `DROPBOX_APP_KEY` is not set (hidden by design as a feature). See `build.md`.
- **`./gradlew :composeApp:run` does not complete Dropbox / OneDrive linking (all desktop OSes)**: Their redirect URI uses the custom scheme `keryx://`, and the connect button remains disabled until timeout. The reason differs per OS. macOS: LaunchServices routes `keryx://` to the **packaged `Keryx.app`** (via `CFBundleURLTypes` in Info.plist), which the `gradlew run` instance is not. Windows / Linux: the scheme is registered at startup, but only when running from a packaged launcher — pointing the OS at the JDK's own `java` binary would leave a broken handler behind after the Gradle run ends. **To test or perform linking, build the app with `./gradlew :composeApp:createDistributable` and launch it** (terminate the gradle instance first). Saved tokens are stored in the keychain or `.dropbox_tokens.json` in the data directory. Google Drive uses loopback reception and links fine under `gradlew run`.
- **(Linux) The browser reports an unknown protocol for `keryx://`**: the scheme is not registered with the desktop environment. A packaged build registers it on first launch, writing `~/.local/share/applications/keryx-url-handler.desktop` and an association in `~/.config/mimeapps.list`. Verify with `xdg-mime query default x-scheme-handler/keryx` (expect `keryx-url-handler.desktop`) and test end to end with `xdg-open 'keryx://oauth2/callback?code=test&state=test'` while Keryx is running — the window should come to the front. Note that these two files live in the user's home and are **not removed when the deb/rpm package is uninstalled**; they are harmless (`NoDisplay=true`, and no other application handles `keryx://`), but there is no uninstall hook to clean them up.

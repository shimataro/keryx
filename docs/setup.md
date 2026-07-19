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
- **(macOS) `./gradlew :composeApp:run` does not complete Dropbox linking**: The redirect URI uses the custom scheme `keryx://`, and macOS LaunchServices routes this to the **packaged `Keryx.app`** (via `CFBundleURLTypes` in Info.plist). The instance launched by `gradlew run` does not receive it, and the connect button remains disabled until timeout (Windows/Linux pass the URL as a command-line argument, so this is not an issue). **To test or perform Dropbox linking on macOS, build `Keryx.app` with `./gradlew :composeApp:createDistributable` and launch it** (terminate the gradle instance first). Saved tokens are stored in the keychain or `.dropbox_tokens.json` in the data directory.

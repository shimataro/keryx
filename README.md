# Keryx

[日本語](README.ja.md)

**One reader. Every device.**

A local-first, cross-platform RSS reader

🌐 **Website**: <https://keryx.merc.works>

## Features

- **Multi-device sync**: via cloud storage (Dropbox / Google Drive / OneDrive)
- **Local-first**: no central server; works fully offline without sync
- **Fast local full-text search**: instantly search article titles and content by keyword
- **Organize feeds with tags and folders**: tags for cross-cutting labels, folders for hierarchical grouping
- **RSS 2.0 / Atom 1.0 support**: RSS 1.0/RDF is also loosely parsed
- **OPML import & export**: migrate to or from other RSS readers and back up your subscriptions
- **Completely free**: no freemium, no trial period, no ads
- **Open source**: source code is public and can be inspected or modified by anyone

## Supported Platforms

Currently available for Windows, macOS, Linux, and Android. A native app for iOS/iPadOS is planned for the future, with macOS eventually joining that native lineup as well.

## Download

Download the latest release from the [Releases page](https://github.com/shimataro/keryx/releases).

> [!IMPORTANT]
> **macOS**: Until an officially signed release is available, downloaded `.dmg` / `.zip` files
> are blocked by Gatekeeper as unsigned. Before opening the `.dmg` or extracting the `.zip`,
> clear the quarantine attribute on the downloaded file itself:
>
> ```bash
> # .dmg
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.dmg
>
> # .zip
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.zip
> ```
>
> Alternatively, right-click the app and choose "Open" instead of double-clicking. See
> [Signing & Notarization](docs/build.md#signing--notarization-future) for background.
>
> **Windows**: Until a code-signing certificate is in place, the `.msi` is unsigned, so
> Windows SmartScreen shows a "Windows protected your PC" warning on first run. Click
> "More info", then "Run anyway" to continue.
>
> **Android**: the `.apk` is not distributed through Google Play, so Android will ask you to
> allow installing from this source the first time you open it. The `.aab` is a Google Play
> submission format, not something you can install directly — download the `.apk` instead.

Once Keryx is running, it can check for, download, and install newer releases on its own — from
the notification bell, the task tray, or Settings → Updates — so the manual steps above are only
needed for this first install. A file Keryx downloads itself never triggers Gatekeeper's
quarantine warning or Windows SmartScreen's "unsigned file" prompt the way a browser download
does, since neither is something a browser saved to disk. On Android, the one-time "allow
installing from this source" permission above still applies, but you only grant it once, not on
every update afterward.

This applies to a macOS `.app`, a Windows `.msi`/portable install, a Linux portable install, and a
sideloaded Android `.apk`. A Linux `.deb`/`.rpm` install and an Android install from Google Play
instead open the release page for you to update through your usual channel (your package manager,
or Play's own auto-update) — Keryx still tells you a new version exists, just not by installing it
itself there.

## Development Documentation

- [design documents](docs/README.md)
- [build instructions and development environment setup](docs/setup.md)
- [packaging (creating distributable apps)](docs/build.md)

## Roadmap

- Platform support
  - [x] Windows
  - [x] macOS
  - [x] Linux ( `.deb` )
  - [x] Linux ( `.rpm` )
  - [x] Android
  - [ ] iOS
  - [ ] iPadOS
- Cloud storage support
  - [x] [Dropbox](https://www.dropbox.com/)
  - [x] [Google Drive](https://drive.google.com/)
  - [x] [OneDrive](https://onedrive.live.com/)
- Multilingual UI
  - [x] English
  - [x] Japanese

## Other

- [Privacy Policy](PRIVACY.md)
- [Terms of Service](TERMS.md)
- [License (MIT)](LICENSE)

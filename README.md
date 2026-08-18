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

Currently available for Windows, macOS, and Linux. Native apps for Android and iOS/iPadOS are planned for the future, with macOS eventually joining that native lineup as well.

## Download

Download the latest release from the [Releases page](https://github.com/shimataro/keryx/releases).

> [!IMPORTANT]
> **macOS**: Until an officially signed release is available, downloaded `.dmg` / `.zip` files
> are blocked by Gatekeeper as unsigned. Before opening the `.dmg` or extracting the `.zip`,
> clear the quarantine attribute on the downloaded file itself (this also keeps the app inside
> from inheriting it once opened/extracted):
>
> ```bash
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.dmg   # .dmg
> xattr -d com.apple.quarantine ~/Downloads/Keryx-*.zip   # .zip
> ```
>
> Alternatively, right-click the app and choose "Open" instead of double-clicking. See
> [Signing & Notarization](docs/build.md#signing--notarization-future) for background.
>
> **Windows**: Until a code-signing certificate is in place, the `.msi` is unsigned, so
> Windows SmartScreen shows a "Windows protected your PC" warning on first run. Click
> "More info", then "Run anyway" to continue.

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
  - [ ] Android
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

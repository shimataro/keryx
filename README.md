# Keryx

[日本語](README.ja.md)

**One reader. Every device.**

A local-first, cross-platform RSS reader

🌐 **Website**: <https://keryx.merc.works>

## Features

- **Multi-device sync**: via cloud storage (Dropbox / OneDrive / Google Drive — on Android, Google
  Drive requires Google Play services, so a device without it is offered Dropbox and OneDrive)
- **Local-first**: no central server; works fully offline without sync
- **Fast local full-text search**: instantly search article titles and content by keyword
- **Organize feeds with tags and folders**: tags for cross-cutting labels, folders for hierarchical grouping
- **RSS 2.0 / Atom 1.0 support**: RSS 1.0/RDF is also loosely parsed
- **OPML import & export**: migrate to or from other RSS readers and back up your subscriptions
- **Completely free**: no freemium, no trial period, no ads
- **Open source**: source code is public and can be inspected or modified by anyone

## Supported Platforms

Currently available for Windows, macOS, Linux, and Android. The macOS app is a native Mac app and needs
macOS 14 (Sonoma) or later on an Apple Silicon Mac. Support for iOS/iPadOS is planned for the future.

## Download

Download the latest release from the [Releases page](https://github.com/shimataro/keryx/releases).
Linux is available for both x86_64 and arm64.

> [!IMPORTANT]
> **macOS**: the app is signed and notarized by Apple, so it opens like any other downloaded app
> — no extra steps are needed. It requires macOS 14 or later on an Apple Silicon Mac.
>
> **Windows**: Until a code-signing certificate is in place, the `.msi` is unsigned, so
> Windows SmartScreen shows a "Windows protected your PC" warning on first run. Click
> "More info", then "Run anyway" to continue.
>
> **Android**: this `.apk` is a sideload install, distributed directly rather than through
> Google Play, so Android will ask you to allow installing from this source the first time
> you install it from the app you used to open it (e.g. your file manager or browser).

Once Keryx is running, it can check for, download, and install newer releases on its own — from
the notification bell, the task tray, or Settings → Updates (on macOS, from **Keryx → Check for
Updates…** in the menu bar, or automatically, as set in Settings → General) — so the manual steps
above are only needed for this first install. A file Keryx downloads itself never triggers Gatekeeper's
quarantine warning or Windows SmartScreen's "unsigned file" prompt the way a browser download
does, since neither is something a browser saved to disk. On Android, the one-time "allow
installing from this source" permission above still applies, but you only grant it once, not on
every update afterward.

This applies to the macOS app, a Windows `.msi`/portable install, a Linux portable install, and a
sideloaded Android `.apk`. A Linux `.deb`/`.rpm` or Snap install, and an Android install from
Google Play, instead open the release page for you to update through your usual channel (your
package manager, or Play's own auto-update) — Keryx still tells you a new version exists, just not
by installing it itself there.

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
  - [x] Linux ( Snap )
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

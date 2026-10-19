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
- **English and Japanese UI**: follows your system language
- **Completely free**: no freemium, no trial period, no ads
- **Open source**: source code is public and can be inspected or modified by anyone

## Supported Platforms

Currently available for Windows, macOS, Linux, and Android. The macOS app is a native Mac app and needs
macOS 14 (Sonoma) or later on an Apple Silicon Mac. Support for iOS/iPadOS is planned for the future.

## Download

<a href="https://f-droid.org/packages/works.merc.keryx/"><img src="https://f-droid.org/badge/get-it-on.svg" alt="Get it on F-Droid" height="80"></a>
<a href="https://snapcraft.io/keryx"><img src="https://snapcraft.io/static/images/badges/en/snap-store-black.svg" alt="Get it from the Snap Store" height="56"></a>

- **Android**: [F-Droid](https://f-droid.org/packages/works.merc.keryx/) — updates arrive through the F-Droid client.
  The F-Droid build has no Google Drive sync (Dropbox and OneDrive work as usual).
- **Linux (x86_64 / arm64)**: [Snap Store](https://snapcraft.io/keryx) — `sudo snap install keryx`, updated automatically.

All platforms (Windows x86_64, macOS, Linux x86_64 / arm64 `.deb`/`.rpm`/portable, Android `.apk`) are
also available from the [Releases page](https://github.com/shimataro/keryx/releases).

> [!IMPORTANT]
> **Windows**: Until a code-signing certificate is in place, the `.msi` is unsigned, so
> Windows SmartScreen shows a "Windows protected your PC" warning on first run. Click
> "More info", then "Run anyway" to continue.
>
> **Android**: if you install from F-Droid, no extra steps are needed. The `.apk` from the Releases
> page is distributed directly (sideloaded), so Android will ask you to allow installing from this
> source the first time you install it from the app you used to open it (e.g. your file manager or
> browser).

Once Keryx is running, it can check for, download, and install newer releases on its own — from
the notification bell, the task tray, or Settings → Updates (on macOS, from **Keryx → Check for
Updates…** in the menu bar, or automatically, as set in Settings → General). The Windows and
Android steps above are therefore only needed for the first install: an update Keryx downloads
itself is not a browser download, so it never triggers Windows SmartScreen's "unsigned file"
prompt, and on Android the "allow installing from this source" permission is granted only once,
not on every update.

This applies to the macOS app, a Windows `.msi`/portable install, a Linux portable install, and an
Android `.apk` from the Releases page. A Linux `.deb`/`.rpm` install instead opens the release page
for you to update through your package manager — Keryx still tells you a new version exists, just
not by installing it itself there. A Snap install is updated automatically by the Snap Store, and
the F-Droid build leaves updates entirely to the F-Droid client, so it has no in-app update at all.

## Development Documentation

- [design documents](docs/README.md)
- [build instructions and development environment setup](docs/setup.md)
- [packaging (creating distributable apps)](docs/build.md)

## Other

- [Privacy Policy](PRIVACY.md)
- [Terms of Service](TERMS.md)
- [License (MIT)](LICENSE)

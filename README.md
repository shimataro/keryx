# Keryx

[日本語](README.ja.md)

**One reader. Every device.**

A local-first, cross-platform RSS reader

🌐 **Website**: <https://keryx.merc.works>

## Features

- **Multi-device sync**: via cloud storage (Dropbox / OneDrive / Google Drive — on Android, Google
  Drive requires Google Play services, so a device without it is offered Dropbox and OneDrive), or
  your own Nextcloud server (see [Syncing with Nextcloud](#syncing-with-nextcloud))
- **Local-first**: no central server; works fully offline without sync
- **Fast local full-text search**: instantly search article titles and content by keyword
- **Organize feeds with tags and folders**: tags for cross-cutting labels, folders for hierarchical grouping
- **RSS 2.0 / Atom 1.0 support**: RSS 1.0/RDF is also loosely parsed
- **OPML import & export**: migrate to or from other RSS readers and back up your subscriptions
- **English and Japanese UI**: follows your system language
- **Completely free**: no freemium, no trial period, no ads
- **Open source**: source code is public and can be inspected or modified by anyone

## Download

<a href="https://f-droid.org/packages/works.merc.keryx/"><img src="https://f-droid.org/badge/get-it-on.svg" alt="Get it on F-Droid" height="80"></a>
<a href="https://snapcraft.io/keryx"><img src="https://snapcraft.io/static/images/badges/en/snap-store-black.svg" alt="Get it from the Snap Store" height="56"></a>
<a href="https://apps.apple.com/app/keryx-rss-reader/id6821582850"><img src="https://toolbox.marketingtools.apple.com/api/v2/badges/download-on-the-app-store/black/en-us" alt="Download on the App Store" height="56"></a>

| Platform | Requirements | Get it |
| --- | --- | --- |
| Windows | x86_64 | `.msi` / portable ZIP from the [Releases page][releases] |
| macOS (native app) | macOS 14 (Sonoma) or later on Apple Silicon | `.dmg` from the [Releases page][releases] |
| Linux | x86_64 / arm64 | [Snap Store](https://snapcraft.io/keryx), or `.deb` / `.rpm` / portable ZIP from the [Releases page][releases] |
| Android | Android 8.0 or later | [F-Droid](https://f-droid.org/packages/works.merc.keryx/), or `.apk` from the [Releases page][releases] |
| iOS / iPadOS | iOS / iPadOS 17 or later | [App Store](https://apps.apple.com/app/keryx-rss-reader/id6821582850) |

[releases]: https://github.com/shimataro/keryx/releases

The F-Droid build has no Google Drive sync (Dropbox, OneDrive and Nextcloud work as usual).

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
the notification center at the bottom of the feed list, the task tray, or Settings → Updates (on macOS, from **Keryx → Check for
Updates…** in the menu bar, or automatically, as set in Settings → General). The Windows and
Android steps above are therefore only needed for the first install: an update Keryx downloads
itself is not a browser download, so it never triggers Windows SmartScreen's "unsigned file"
prompt, and on Android the "allow installing from this source" permission is granted only once,
not on every update.

In-app updates work for the macOS app, a Windows `.msi`/portable install, a Linux portable install,
and an Android `.apk` from the Releases page. A Linux `.deb`/`.rpm` or Snap install instead opens the
release page — Keryx still tells you a new version exists, just not by installing it itself there.
Update a `.deb`/`.rpm` install through your package manager; a Snap install is updated automatically
by the Snap Store, so there is nothing to download from that page. The F-Droid build leaves updates
entirely to the F-Droid client, so it has no in-app update at all. The iOS/iPadOS app is updated by the
App Store, so it has no in-app update either.

## Syncing with Nextcloud

Choose **Nextcloud** in the first-run setup or in Settings → Cloud sync, enter your server's address
(for example `cloud.example.com`), and sign in on your server's page. A few things to know:

- The server must be reachable over **HTTPS** with a certificate your device trusts; `http://`
  addresses are not accepted.
- Keryx keeps its sync data in a **`Keryx` folder** in your files. The app password Keryx receives can
  reach your whole account, but Keryx only uses that folder. You can revoke it any time under
  Settings → Security → Devices & sessions on your server, and on Nextcloud 36 or later limit it to
  file access there. Disconnecting in Keryx revokes it too —
  unless your server can't be reached at that moment, in which case revoke it there yourself.
- Each sync updates the sync file, so your server may keep **earlier versions** of it and list each
  update in its **Activity** stream.
- If a Nextcloud desktop client syncs your files to a computer, you may want to exclude the `Keryx`
  folder in its selective sync settings.

See [PRIVACY.md](PRIVACY.md#nextcloud) for exactly what is sent where.

## Development Documentation

- [design documents](docs/README.md)
- [build instructions and development environment setup](docs/setup.md)
- [packaging (creating distributable apps)](docs/build.md)

## Other

- [Privacy Policy](PRIVACY.md)
- [Terms of Service](TERMS.md)
- [License (MIT)](LICENSE)

Apple, the Apple logo, and App Store are trademarks of Apple Inc.

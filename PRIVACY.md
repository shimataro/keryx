# Privacy Policy

[日本語](PRIVACY.ja.md)

**Effective date:** 2026-07-18 · **Applies to:** Keryx (desktop)

Keryx is a local-first RSS reader. This document explains, plainly and completely,
what data the app handles and where it goes. Keryx is open source — every claim
below can be verified directly against the source code at
<https://github.com/shimataro/keryx>.

## Overview

- Keryx has **no user accounts** and **no server operated by the developer**. There
  is nothing to sign up for, and no backend that Keryx's developer controls ever
  receives your data.
- All of your data — subscriptions, articles, read/starred status, tags, folders,
  settings — lives in a local database on your device, unless you explicitly turn
  on cloud sync.
- The app does not include any analytics, telemetry, crash reporting, advertising,
  or tracking of any kind. There is nothing to opt out of, because nothing is
  collected in the first place.

## Data stored on your device

Keryx stores its data in two local files, in the OS-standard application data
directory (`~/Library/Application Support/Keryx` on macOS, `%APPDATA%\Keryx` on
Windows, `$XDG_DATA_HOME/Keryx` on Linux):

- **`keryx.db`** (SQLite): your feed subscriptions, cached article content (title,
  summary, body, author — fetched from the feeds you subscribe to, not submitted by
  you), read/starred status, tags, folders, and global app settings. It also
  contains a local full-text search index and internal sync bookkeeping, neither of
  which ever leaves your device (see below).
- **`local_settings.json`**: device-local preferences such as theme, font size,
  refresh interval, and window layout.

An OS-level image cache also stores favicon images fetched for your subscribed
feeds, purely as a local performance cache.

None of this data is transmitted anywhere unless you opt into cloud sync (below).

## Optional cloud sync (Dropbox / Google Drive)

Cloud sync is off by default. If you choose to connect Dropbox or Google Drive (one
provider active at a time), here is exactly what happens:

- **Authentication** uses OAuth 2.0 with PKCE, performed **directly between your
  device and Dropbox's or Google's own servers** — Keryx has no server in the
  middle of this exchange at any point (not for login, not for token refresh, not
  for the sync traffic itself).
- **What syncs:** your feed subscriptions, folders, tags, cached articles
  (read/starred status included), and global app settings.
- **What never syncs:** device-local settings (`local_settings.json`), your OAuth
  credentials, and the local full-text search index. These stay on each device
  independently.
- **What gets uploaded:** a snapshot copy of your local database (with the search
  index removed) is uploaded to a file in your own Dropbox or Google Drive. For
  Google Drive, this is written to the `drive.appdata` scope — a hidden, app-only
  folder that does not appear in your regular Google Drive and that no other app
  can see. For Dropbox, standard file-content scopes are used.
- **Credential storage:** access and refresh tokens are stored using your
  operating system's secure credential storage (Keychain on macOS, Credential
  Manager on Windows, Secret Service on Linux), falling back to a permission-
  restricted local file only if the OS store is unavailable. Tokens are never sent
  anywhere except directly to Dropbox's or Google's own API, as required to
  perform the sync you requested.
- Once your data is in your Dropbox or Google Drive account, it is subject to that
  provider's own privacy policy and terms — Keryx has no further access to or
  control over it beyond the sync file it wrote.
- Disconnecting cloud sync in Settings stops future syncing immediately. It does
  not retroactively delete the sync file already sitting in your Dropbox or Google
  Drive — you can remove that yourself from your cloud storage account if you wish.

## Network requests this app makes

Keryx only talks to servers that are directly relevant to the feature you're using,
and always straight from your device — never through any server run by the
developer:

- **The feeds you subscribe to** — the app fetches each feed's URL periodically (or
  on manual refresh) to check for new articles, using conditional requests so
  unchanged feeds transfer no content.
- **Each feed's own site/favicon** — to display a small site icon next to the feed.
- **GitHub** (`api.github.com`) — an unauthenticated, anonymous check for the
  latest release, so the app can tell you when an update is available. No account
  information, telemetry, or identifiers are sent.
- **Dropbox or Google Drive** — only if you've connected cloud sync, as described
  above.

That's the complete list. Nothing else is contacted, and no analytics or tracking
payloads are ever sent with any of these requests.

## Data retention & deletion

- If you've set a cache retention period in Settings, articles older than that
  window are deleted automatically, except starred articles and the 10 most recent
  articles per feed, which are always kept regardless of age.
- To erase all local Keryx data, delete `keryx.db` and `local_settings.json` from
  the application data directory listed above, or simply uninstall the app.
- See "Optional cloud sync" above for how to remove data from your cloud storage
  account.

## OPML import/export

Importing or exporting your subscriptions as an OPML file is a purely local file
operation — it reads or writes a file you choose on your own device and involves no
network request.

## Security

- All network communication the app performs uses HTTPS.
- Cloud credentials are stored using your operating system's secure credential
  storage where available, as described above.

## Children's privacy

Keryx does not collect personal data from anyone, including children. No age
verification is performed or required.

## Changes to this policy

This file is version-controlled along with the rest of the source code. Its history
of changes is publicly visible at
<https://github.com/shimataro/keryx/commits/master/PRIVACY.md>.

## Verify it yourself

Keryx is open source under the MIT License. Every claim in this document can be
checked against the actual source code at <https://github.com/shimataro/keryx>.

## Contact

Questions about this policy can be raised via GitHub Issues:
<https://github.com/shimataro/keryx/issues>.

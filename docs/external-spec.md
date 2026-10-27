# Keryx External Spec

[日本語](external-spec.ja.md)

External specification.

## 1. Product Overview

A lightweight, simple RSS reader that provides the same feed subscription experience across multiple devices.

- Simple, stylish, high-speed UI/UX
- Local-first (no account required, data stays on the device)
- Cross-device sync via cloud storage (Dropbox / OneDrive / Google Drive)

## 2. Supported Platforms

| Platform | Support |
| --- | --- |
| Windows / Linux | ✅ Compose Multiplatform (current) |
| Android | ✅ Compose Multiplatform (current; cloud sync supports Dropbox / OneDrive, plus Google Drive on any device with working Google Play services — installed, enabled and up to date — see §4 and [sync-architecture.md](sync-architecture.md); the F-Droid build contains no Play services and so has no Google Drive at all, and no in-app update — see §4 and §7) |
| iOS / iPadOS | In development (native SwiftUI UI, sharing its logic with the other platforms via `:shared` — no interim Compose build; see "Apple Native Apps (SwiftUI)" in [app-architecture.md](app-architecture.md)) |
| macOS | ✅ Native SwiftUI app (current; Apple Silicon, macOS 14 or later), sharing its logic via `:shared`. It replaced the Compose macOS build, which is no longer released and remains for internal verification only; see "Apple Native Apps (SwiftUI)" in [app-architecture.md](app-architecture.md) |

## 3. Supported Formats

RSS 2.0 / Atom 1.0 (RSS 1.0/RDF parsed loosely). JSON Feed will come after α.

## 4. Sync Method

- No account registration in Keryx. The user's own cloud storage (Dropbox / OneDrive / Google Drive)
  is used as the sync bus. Only one active connection is allowed at a time; the user selects and
  switches providers (no simultaneous connections).
- **OneDrive supports personal Microsoft accounts only** — work/school accounts cannot be connected.
  See "Cloud Authentication" in [sync-architecture.md](sync-architecture.md).
- **Google Drive on Android** is offered only where Google Play services is installed, enabled and
  up to date. Android uses Google's Play services `AuthorizationClient`; devices without working Play
  services, and the F-Droid build, are not offered Google Drive. The same account and sync file are
  shared with desktop. See "Google Drive on Android" in [sync-architecture.md](sync-architecture.md).
- The sync file format, `VACUUM INTO` snapshot, excluded local-only tables/indexes, OAuth details,
  token storage, and merge behavior are described in [sync-architecture.md](sync-architecture.md).
- Sync targets: subscription list, folders, read state, stars, tag structure, article body, global
  settings.
- Non-sync targets: device-local settings, cloud authentication info.
- Import / export is OPML.
- Read/unread and star changes reach other devices in batches rather than one sync per article — once
  reading pauses for a few minutes, when the app leaves the foreground, or with the next refresh or
  manual sync. Subscription, folder, tag and settings changes still sync within seconds. See
  [sync-architecture.md](sync-architecture.md).
- While a sync is running, the connected provider's row in the Cloud Sync settings tab shows its
  current progress instead of just a plain spinner, so a long first sync doesn't look stalled.
- **On Android and iOS, automatic sync waits for Wi-Fi by default.** Each sync uploads the whole
  database, so an automatic sync is held back on a metered network (mobile data, tethering, a Wi-Fi
  marked as metered) and under the OS's Data Saver / Low Data Mode, and runs as soon as the network
  allows. The Cloud Sync settings tab offers "Sync automatically over mobile data" (off by default,
  device-local) to allow metered networks; Data Saver / Low Data Mode always defers. "Sync now",
  "Refresh All" and pull-to-refresh always sync immediately. Desktop and macOS are never restricted.
  See "Network Gate" in [sync-architecture.md](sync-architecture.md).

## 5. Conflict Resolution Policy

| Data | Policy |
| --- | --- |
| Read / unread, star | Last-write-wins (`read_at` / `starred_at`) |
| Article body | OR merge (keep if present on either side) |
| Subscription list (add) | OR merge |
| Subscription list (remove), tags / folders, global settings | Last-write-wins (propagated via logical deletion) |
| Device-local settings | Not synced |

Details are in [sync-architecture.md](sync-architecture.md).

## 6. Setup Flow

On first launch, choose local-only / cloud sync (Dropbox / OneDrive / Google Drive — on Android,
Google Drive appears only where Google Play services is installed, enabled and up to date, see §4). When cloud is selected, after OAuth authentication, if existing
data exists in the cloud it is automatically merged (imported) during the initial sync.

## 7. Basic Features

- Subscribe to feeds by URL, categorize with tags, OPML import/export. An `.opml` file can also be
  imported by opening it directly from another app — file-association double-click on desktop,
  "open with Keryx" from a file manager or mail attachment on Android, opening it with Keryx on
  macOS/iOS. However it is started (the Settings ▸ Data buttons, the File menu, or an opened file),
  an import or export runs in Settings ▸ Data, which shows its progress and result inline; an
  `.opml` opened during first-run Setup is imported once Setup is done
- With no feeds subscribed yet, the article list shows an empty-state message with an "Add feed"
  button rather than the ordinary "no articles" message; on Android's narrower widths, the
  navigation drawer holding the feed list's own "+" button can auto-open here as well (§9's
  "Narrower widths" has the exact condition), so the button is reachable without the user having to
  find the drawer themselves
- Feed health management: 301/308 auto-updates the subscription URL (notification), 410 Gone shows a warning in the notification center, consecutive errors show an indicator in the feed list
- In-app update on Windows, Linux, and Android (download and install, not just a link to the release
  page), and through Sparkle on macOS. Updates are surfaced via the notification-center bell, desktop
  task tray, and application menu; clicking any of those routes opens Settings ▸ Updates. Every
  download and install is an explicit user action; downloaded files are verified against the GitHub
  release's SHA-256 digest (EdDSA signature on macOS) before installation. Install forms the app
  cannot update in-place (deb/rpm/Snap on Linux, Google Play or F-Droid builds on Android) open the
  release page instead. The F-Droid build omits the feature altogether. See
  [background-update.md](background-update.md) and [SECURITY.md](../SECURITY.md).
- Article list / article view (reader view). **Articles are marked as read the instant they are selected**. An action to mark as unread is available, and the reader toolbar's button for it is a read/unread toggle that shows the article's state (a filled dot while it is unread, like the article list's own unread dot) — so a tap on a reader shown alone, with the list out of sight, is visibly acknowledged and can be undone. Where the reader is swiped between articles (see §9), "selected" means the moment the swipe comes to rest on an article — the neighbouring articles the reader keeps ready are loaded but not selected, and stay unread until one is actually swiped to.
- **The article list shows one of three modes: All, Unread, or Starred.** They are mutually exclusive
  rather than two independent filters, since almost every starred article is already read. The
  current mode names a pop-up button at the start of the article list's toolbar, and the View menu
  lists the three as check items chosen with ⌘/Ctrl+1, 2 and 3. **Starred** narrows whatever is
  selected (a feed, folder, tag or All Feeds, including search results) to its starred articles and
  lasts for the session only; while the subscription list's own Starred item is selected it is shown
  checked and disabled. **All / Unread** are saved and restored at the next launch. See
  [app-architecture.md](app-architecture.md) for the details.
- **Unread mode deliberately does not hide an article the instant it becomes read** (so the list doesn't shift under the reader while reading down it) — the article list's own toolbar carries a separate "hide read" action for pulling the list back to strictly-unread on demand, placed directly beside the mode button (and away from mark-all-read, so the two aren't mistaken for each other). It stays visible but disabled whenever there's nothing to hide — no read article other than the one currently selected, or a mode other than Unread — and running it never leaves Unread mode.
- **New articles that arrive beyond the current scroll position, toward the list's fresh end, surface
  as a floating pill** (e.g. "12 new articles") rather than silently piling up; tapping it jumps to
  them. The fresh end follows the sort direction, and scrolling to it clears the pill. Articles that
  land mid-list or past the stale end are not counted. The pill appears for foreground arrivals
  (manual/background refresh, pull-to-refresh, cloud sync) at every width and platform. It does **not**
  appear the first time an empty list gets articles, nor when an existing article merely re-enters a
  list (re-starred, feed moved) — only an article that has actually just arrived counts.
- Stars (persistent), open in external browser. "Open in browser" (and a feed's "Open site") applies to
  `http`/`https` links only: a link of any other scheme, or a relative one, is never handed to the OS — the
  action stays visible but disabled on every route (toolbar, menu bar, shortcut, context menu), while "Copy URL"
  still works for any non-empty link. An article's link comes from the feed unvalidated, so this keeps a
  `file:`, `javascript:` or custom-scheme link from launching another application.
  On Android, an `http`/`https` link opens in a Custom Tab — the user's default browser's in-app tab,
  themed to the app's own light/dark setting and offering the browser's share action — so Back returns
  straight to the app; where no installed browser supports Custom Tabs, it opens in the default browser
  instead.
- **Sharing links (Android only).** An article's link can be sent to another app through the system share
  sheet — from the reader's toolbar or the article row's long-press menu, enabled under the same rule as
  "Copy URL" (any non-empty link), with the article's title as the share's subject. Desktop shows no share
  action anywhere. In the other direction, a link shared *to* Keryx from another app (a browser's share
  menu, say) opens the "Add feed" dialog with the first `http`/`https` URL in the shared text already
  filled in; anything else in the text, and any other scheme, is ignored. Nothing is subscribed until the
  user confirms in the dialog. A link shared during first-run Setup waits until Setup is done; one shared
  while the dialog is already open replaces what was typed there.
- Local full-text search with SQLite FTS5, scoped to the currently selected subscription-list item
  (all feeds, starred, a feed, folder, or tag). Search terms and ranking details are in
  [db-schema.md](db-schema.md).
- Desktop notifications, task tray residence (close minimizes to tray), notification center, and
  unread badge. Background refresh is followed by a cloud sync when connected. On a metered network or under
  Data Saver / Low Data Mode, background sync and refresh follow §4's rule. On Android, new-article
  notifications and background refresh run through the OS channels; the in-app "check for update" is
  hidden when the app was installed from Google Play or via the F-Droid build, rather than sideloaded.
  iOS likewise refreshes in the background and posts notifications. Platform-level details (D-Bus
  protocols on Linux, WorkManager on Android, BGAppRefreshTask on iOS, badge counts, notification
  permissions) are in [background-update.md](background-update.md).

### Behavior on Feed URL Change / Disappearance

| HTTP | Behavior |
| --- | --- |
| 301 / 308 (Permanent) | Auto-updates subscription URL (notification) |
| 302 / 303 / 307 (Temporary) | Follows but does not change subscription URL |
| 410 Gone | Warning in notification center (not auto-deleted) |
| Timeout | Error notification after a fixed number of retries |

## 8. Accessibility & Internationalization

- All UI strings are managed via Compose Resources (`values/strings.xml` for English — the default and
  fallback — plus `values-ja/strings.xml` for Japanese, same key set). Selected according to system locale,
  falling back to English if the system locale isn't one of the two. The SwiftUI app follows the same rule
  (its String Catalog is generated from these files and its development language is English).
- Font size setting (reflected in `LocalDensity` fontScale). It is applied on top of the OS's own font
  size rather than replacing it — the app setting multiplies the system font scale — so text enlarged
  system-wide stays enlarged in Keryx. On Android 14+, the OS's nonlinear scaling (large text grows
  less than small text) still applies, evaluated for the combined scale.
- On Android, the status and navigation bar icons follow the in-app light/dark setting (including in
  the settings screen), not the OS's.

## 9. UI Direction

**Each platform follows its own native UI idiom rather than one shared design system.** macOS gets
a flat, SF-leaning look; Android uses Material 3's components, shapes, and ripple feedback; the
native iOS / iPadOS app is built in SwiftUI (see §2). **Windows and Linux are the deliberate exception**:
Java/Swing's platform integration is too limited for a comparably native treatment, so both share
macOS's flat look. Android 12+ uses Material You dynamic color, falling back to the fixed teal
palette on older versions. Light / dark / system support. The layout is a 3-pane arrangement
(feed list / article list / article detail) with keyboard navigation, adapting to fewer panes on
narrower widths (see below).

### Least surprise

**The app does what the user expects, and nothing the user did not ask for.**

- **Nothing happens unprompted.** Without the user's action, the app does not navigate, open drawers
  or dialogs, move scroll position, reorder or remove items, or change selection, filters or settings.
  Background work arrives without disturbing the user (new articles surface as a pill rather than
  shifting the list, §7).
- **An action does what its label or icon says**, with no hidden side effect.
- **An action that loses data or settings asks first** — unsubscribing, deleting a folder/tag,
  resetting cloud sync data, overwriting a file. Actions that only change reading state do not ask.
- **Platform conventions win.** Shortcuts, back, menu placement, and gestures follow the OS's
  standard; deliberate departures are recorded in this document.
- **The same action behaves the same from every route** — see "Actions with more than one route" below.
- **At a boundary, nothing happens.**

Examples already decided on this principle: Unread mode does not hide an article the instant it
becomes read; "Open in browser" stays visible but disabled for a link it cannot open; the
keyboard-focus outline appears only once a hardware key has been pressed; a swipe is ignored for a
moment after scrolling; J/K at the first or last article do nothing; a notification's "show feed"
action does not open the narrow layout's drawer (`ShowFeedDetail` in [error-design.md](error-design.md)).

### Refresh feedback

**Progress is shown for every refresh; a result is shown only for one the user started.** An
automatic refresh is the one at launch, the periodic one while the app is open, and a background
one; a user-started refresh is Refresh All, a pull, or a feed's own Refresh.

| Feedback | Automatic refresh | User-started refresh |
| --- | --- | --- |
| In progress: the feed-list rows' spinners, the toolbar's Refresh spinner | Shown | Shown |
| In progress: the pull indicator, iOS's "Checking for new articles…" | Not shown | Shown |
| Result: iOS's status ("N new articles", "No new articles", or the failure) | Not shown | Shown — also when the action joined an automatic refresh already running |
| Result: the new-article OS notification | Only while the app is not in front | Shown (Refresh All and a pull; a feed's own Refresh posts none) — also when the action joined an automatic refresh already running |
| Failure: no feed could be reached | A notification-center entry | A notification-center entry |

Why:

- **Progress, always.** Seeing that a refresh is already running saves starting another (a pull,
  Refresh All) for nothing — the toolbar's spinner alone sits at the edge of the screen and is easy to
  miss. It also gives a visible cause when unread counts change under the user. Every one of these
  sits in a slot of its own, so it moves nothing (Least surprise).
- **A result, only when asked for.** An automatic refresh's result already shows on screen: unread
  counts rise, the article list gains articles, and the new-articles pill appears (§7). A refresh that
  found nothing needs no news, and one that reached no feed leaves a notification-center entry either
  way. A result the user did not ask for would also move things: on iOS before 26 the status is the
  bottom bar's only item, so the bar would come and go (Least surprise).
- **The user's own action always gets its result**, since the result is the response to that action —
  even when the action joined an automatic refresh that was already running instead of starting one.
- **The OS notification is for when the app is not in front.** In front, the unread counts and the
  pill already say the same thing, so an automatic refresh's notification is held back there. A
  user-started refresh still posts it: on desktop and Android it is that action's only result.
  "In front" means the main window has focus (desktop), the main window is key (macOS), the app's
  activity is in the foreground (Android), or the app is active (iOS); a settings window in front does
  not count (see [background-update.md](background-update.md)).

### Actions with more than one route

**An action reachable by more than one route — toolbar button, application menu, context menu,
keyboard shortcut, gesture — behaves identically whichever route invokes it**: the same effect,
enabled/disabled state, and feedback. Routes may differ only in *which item* they act on, never in
what happens to it. The implementation is shared so every route calls the same handler; feedback
tied to a particular control appears when that control is showing the acted-on item. See
[error-design.md](error-design.md) "Notification Center" for how copy confirmation is handled per
platform.

A key that works only in one pane is shown beside a menu item only while it would work:
Feed ▸ Rename and Delete stay clickable whenever a feed-list item is selected, but the list
shortcuts F2 (Return on macOS) and Delete respond only while the feed list has keyboard focus and
no text field is being typed into.

### Adaptive layout (width) and touch input

**3-pane width.** The window never narrows below the width all three panes need, so the feed list
stays a permanent sidebar and the article list and reader are shown beside it.

**Narrower widths.** The feed list becomes a navigation drawer, opened via a hamburger button; it
opens automatically the first time the app has no feeds and no cloud account, so a new user sees
the "Add feed" button. A phone-width screen shows one of the two remaining panes (article list,
reader) at a time with its own back control; a tablet-width screen shows both together. Only the
number of simultaneous panes changes—their content stays the same. On Android, going back from the
reader to the article list uses predictive back where the system supports it.

**Search field placement.** At the 3-pane width the search field lives in the feed-list sidebar and
filters the article list beside it. At narrower widths the drawer has no search field; search is
reached through the article list header, the pane that is always on screen. Tapping it expands the
field above the results, where it stays across rotation. The iOS app follows the same principle:
search always belongs to the article list, using the system search field. Clearing the query returns
to the selected subscription-list item's unfiltered list. See
[app-architecture.md](app-architecture.md) for the full layout rules.

**Mouse and touch affordances.** Where a mouse and touchscreen need different gestures, both are
supported without changing the underlying action: click-and-drag with a mouse, drag from a handle
with touch; right-click context menu on desktop, long-press menu on Android. The same keyboard
shortcuts work on Android tablets with hardware keyboards. See [app-architecture.md](app-architecture.md).

**Scrolling and the reader.** Scroll indicators follow each platform's own idiom. The article reader
uses a native web view; on Linux arm64 a Compose fallback is used because no native web-view binary
is available. See [app-architecture.md](app-architecture.md) and [known-issues.md](known-issues.md).

**Swipe between articles.** At narrow layouts, a horizontal swipe on the reader moves to the
next/previous article, following the finger and settling once released. A swipe is treated as a
scroll when its vertical travel is not clearly smaller than its horizontal travel, and ignored for
a moment after scrolling so a flick meant to scroll does not change the article. This applies
whenever the reader is on screen at a narrower-than-desktop width. At the 3-pane desktop width the
reader is keyboard-driven (J/K) and the swipe does not apply.

**Pull-to-refresh.** On Android and the native iOS / iPadOS app, pulling the article list down
refreshes only the feeds behind the current list (unlike Refresh All). On iOS, pulling the sidebar
refreshes every feed. The gesture is disabled while search results are showing or there are no
feeds. See [app-architecture.md](app-architecture.md) for the full table and platform differences.

**iOS refresh status.** With no Refresh All button to spin, iOS shows a refresh the user started (a
pull, Refresh All, or a feed's own Refresh) in the centre of the bottom bar: "Checking for new
articles…" with a spinner while it runs (through the cloud sync that follows), then the result for
about two and a half seconds — "N new articles", "No new articles", or, when no feed could be
reached, a failure message behind a warning symbol that also stays in the notification center. An
automatic refresh and a sync on their own show nothing there; idle shows nothing. See "Refresh
feedback" above.

**Per-feed refresh spinner.** While feeds refresh, whatever started it, every feed the refresh
covers shows a small spinner in place of its icon in the feed list, on every platform, until its
own new articles have landed. A collapsed folder shows it while any of its feeds does. Nothing on
the row moves, and screen readers hear the row's state as "Refreshing".

**Native menus, tray, file dialogs, and font.** The desktop app uses native Swing/AWT widgets for
the menu bar, context menus, tray menu, and file dialogs. Platform-specific choices
(macOS/Windows/Linux, FlatLaf, JFileChooser on Linux, etc.) are detailed in
[app-architecture.md](app-architecture.md) and [known-issues.md](known-issues.md).

## 10. Privacy & Security

- No server operated by the developer, no account registration required. Keryx contacts only
  servers directly relevant to the feature in use (each subscribed feed and its favicon, GitHub
  for update checks/downloads, and — only if connected — Dropbox/OneDrive/Google Drive), always
  straight from the device; see [PRIVACY.md](../PRIVACY.md)'s "Network requests this app makes"
  for the full list. Keryx's own communication
  (cloud sync, update checks, update downloads) always uses HTTPS; a subscribed feed is fetched
  over plain HTTP if its URL explicitly says `http://` (or redirects there) — a feed URL with no
  scheme at all is upgraded to HTTPS, but an explicit `http://` is not overridden. Note that the
  reader keeps the articles either side of the one on screen ready to swipe to (§9), so their
  content — including any images or embedded content they contain — is fetched before they are
  opened, exactly as it would be on opening them. Nothing about them is marked read.
- Each cloud provider's token (Dropbox, OneDrive, Google Drive) is stored separately in platform secure storage.
  On macOS: the app's data-protection Keychain, with no file fallback. On Windows/Linux: Credential Manager / Secret Service via
  java-keyring, or, inside the Snap package specifically, a local store encrypted with a per-app key from the
  desktop's Secret portal (via libsecret, not java-keyring) — falling back to a file in the data directory when
  unavailable. On Android: an AES-256/GCM key held in the Android Keystore, per provider. See
  `sync-architecture.md`'s "Token Storage" for both.

## 11. Technology Choices

| Layer | Technology |
| --- | --- |
| UI | Compose Multiplatform (Material 3 on Android; native SwiftUI on macOS/iOS; Windows/Linux share a flat desktop look — see §9) |
| State management | androidx.lifecycle ViewModel + Koin |
| DB | SQLDelight (SQLite) + FTS5 (raw SQL) |
| HTTP | Ktor client (CIO on desktop, OkHttp on Android) |
| RSS/HTML/XML parsing | ksoup |
| Serialization / datetime | kotlinx-serialization / kotlinx-datetime |
| Cloud sync | Ktor + Dropbox / OneDrive (Microsoft Graph) / Google Drive REST API (OAuth PKCE + refresh token) |
| i18n | Compose Resources |
| Testing | kotlin-test + kotlinx-coroutines-test + Ktor MockEngine |
| Build | Gradle 9.7.1 (Kotlin 2.4.10 / Compose Multiplatform 1.11.1 / JDK 25 toolchain) |
| Image loading | Coil3 (favicon display. SVG decode support, shared existing HttpClient, disk cache) |

Both the feed list and article list display favicons (`feeds.favicon_url`) using Coil3 `AsyncImage`. If not yet fetched or loading fails, fall back to a letter (initial) avatar or generic icon.

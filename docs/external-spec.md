# Keryx External Spec

[日本語](external-spec.ja.md)

External specification.

## 1. Product Overview

A lightweight, simple RSS reader that provides the same feed subscription experience across multiple devices.

- Simple, stylish, high-speed UI/UX
- Local-first (no account required, data stays on the device)
- Cross-device sync via cloud storage (Dropbox / Google Drive / OneDrive)

## 2. Supported Platforms

| Platform | Support |
| --- | --- |
| Windows / macOS / Linux | ✅ (Compose Multiplatform, current) |
| Android | ✅ (Compose Multiplatform, current; cloud sync supports Dropbox / OneDrive — Google Drive on Android is a future consideration, see §4 and [sync-architecture.md](sync-architecture.md)) |
| iOS / iPadOS / macOS | Planned (initially Compose, then native SwiftUI) |

## 3. Supported Formats

RSS 2.0 / Atom 1.0 (RSS 1.0/RDF parsed loosely). JSON Feed will come after α.

## 4. Sync Method

- No account registration in Keryx. The user's own cloud storage (Dropbox / Google Drive / OneDrive) is used as the sync bus. Only one active connection is allowed at a time; the user selects and switches providers (no simultaneous connections).
- The sync file is a raw SQLite upload of `keryx.db`.
- Sync targets: subscription list, read state, stars, tag structure, global settings.
- Non-sync targets: device-local settings, cloud authentication info.
- Import / export is OPML.
- **OneDrive supports personal Microsoft accounts only** — a work/school (Microsoft 365 / Entra ID)
  account cannot be connected. Keryx keeps its sync file in OneDrive's hidden per-app folder, and the
  Graph permission that grants access to only that folder is offered for personal accounts alone;
  supporting an organizational account would mean requesting access to the user's entire drive
  instead. See "Cloud Authentication" in [sync-architecture.md](sync-architecture.md).
- **Android supports Dropbox and OneDrive only** (both are PKCE public clients using the same
  `keryx://oauth2/callback` custom-URI redirect as desktop). Google Drive's desktop OAuth
  configuration (a "Desktop app" client using loopback redirect + `client_secret`) cannot be reused
  on Android — see "Cloud Authentication" in [sync-architecture.md](sync-architecture.md) for the
  investigation and "Future Consideration (Google Drive on Android)" for the evaluated paths — so it is not offered as a setup/settings option there.

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

On first launch, choose local-only / cloud sync (Dropbox / Google Drive / OneDrive — Android offers
Dropbox / OneDrive; Google Drive on Android is a future consideration, see §4). When cloud is selected, after OAuth authentication, if existing
data exists in the cloud it is automatically merged (imported) during the initial sync.

## 7. Basic Features

- Subscribe to feeds by URL, categorize with tags, OPML import/export. An `.opml` file can also be
  imported by opening it directly from another app — file-association double-click on desktop,
  "open with Keryx" from a file manager or mail attachment on Android
- With no feeds subscribed yet, the article list shows an empty-state message with an "Add feed"
  button rather than the ordinary "no articles" message; on Android's narrower widths, where the
  feed list's own "+" button lives inside a navigation drawer closed by default (see §9), the
  drawer also opens automatically the first time the app has no feeds and no cloud account
  configured, so the button is reachable without the user having to find the drawer themselves
- Feed health management: 301/308 auto-updates the subscription URL (notification), 410 Gone shows a warning in the notification center, consecutive errors show an indicator in the feed list
- In-app update (download and install, not just a link to the release page): supported for a macOS
  `.app`, a Windows MSI install or portable ZIP, a Linux portable ZIP (a deb/rpm or Snap install
  falls back to opening the release page, as does any install form the app can't recognize), and a sideloaded
  Android install (never through Google Play, which already updates the app itself). Presented from
  the moment an update is detected, via the notification-center bell, the desktop task tray, and
  the application menu's Help menu — the latter two showing one and the same entry, which is also
  where a check can be asked for on demand ("Check for updates" whenever nothing is pending);
  every download and every install remains a separate, explicit user action — nothing installs
  silently or automatically. The downloaded file is verified against the GitHub release's own
  SHA-256 digest before anything is installed. See [background-update.md](background-update.md)
  for the full design and [SECURITY.md](../SECURITY.md) for what that verification does and does
  not guarantee.
- Article list / article view (reader view). **Articles are marked as read the instant they are selected**. An action to mark as unread is available.
- Stars (persistent), open in external browser
- Local full-text search with SQLite FTS5 (trigram, 2+ characters — terms of 3+ characters use the trigram index, a query made up only of 2-character terms falls back to a `LIKE` scan ordered by recency; mixed queries with any 3+ character term use FTS5 relevance ranking; see [db-schema.md](db-schema.md))
- Desktop notifications, task tray residence (close minimizes to tray), notification center.
  On Linux the tray uses the D-Bus `org.kde.StatusNotifierItem` + `com.canonical.dbusmenu` protocols
  and notifications use `org.freedesktop.Notifications`, falling back to the AWT system tray when no
  StatusNotifierItem host is running. Desktop also composites the unread count directly onto the
  Dock/taskbar/window icon as a digit badge. On Android, new-article notifications are posted through
  `NotificationManagerCompat` (requesting the OS notification permission on Android 13+) and
  background refresh runs on `WorkManager`, at roughly the interval configured in Settings; the
  in-app "check for update" is hidden when the app was installed from an app store (currently just
  Google Play) rather than sideloaded, since that store already auto-updates the app. Android has no
  equivalent of the digit badge — the platform has no API to set an app-icon badge count independent
  of an active notification (unlike iOS's `setApplicationIconBadgeNumber`) — so the app icon instead
  shows the OS's own notification dot (tied to the presence of an active notification) plus a
  long-press count via `setNumber` on launchers that support it, a deliberate asymmetry rather than an oversight; see
  `background-update.md` for the full comparison.

### Behavior on Feed URL Change / Disappearance

| HTTP | Behavior |
| --- | --- |
| 301 / 308 (Permanent) | Auto-updates subscription URL (notification) |
| 302 / 303 / 307 (Temporary) | Follows but does not change subscription URL |
| 410 Gone | Warning in notification center (not auto-deleted) |
| Timeout | Error notification after a fixed number of retries |

> [!NOTE]
> Fixed the bug where only 301 was supported with no redirect loop guard, and now all redirect codes are supported + a maximum 5-time loop guard is implemented.

## 8. Accessibility & Internationalization

- All UI strings are managed via Compose Resources (`values/strings.xml`). Selected according to system locale, falling back to default (Japanese) if the language is not supported. Currently only Japanese is bundled.
- Font size setting (reflected in `LocalDensity` fontScale).

## 9. UI Direction

**Each platform follows its own native UI idiom rather than one shared design system.** macOS gets
a flat, SF-leaning look; Android gets Material 3's own components, shapes, and ripple feedback; iOS
will eventually get native SwiftUI. **Windows and Linux are the deliberate exception**: Java/Swing's
own platform integration is too limited to give either OS a comparably native treatment (see the
Look & Feel, context-menu, and file-dialog specifics below, and `docs/known-issues.md`), so both
share macOS's flat look instead of getting one of their own. Material 3 with the app's own teal
color scheme is Android's concrete instantiation of this principle, not a universal baseline the
other platforms deviate from — where this document says "Material 3", read it as Android-specific
unless stated otherwise. Light / dark / system support. 3-pane layout (feed list / article list /
article detail) + keyboard navigation, adapting down to fewer simultaneous panes on narrower widths
(see below).

### Adaptive layout (width) and touch input (Android)

The 3-pane layout is desktop's steady state — the window can never narrow below the width all three
panes need, so it always shows all three, with the feed list as a permanent sidebar pane. "The width
all three panes need" is measured at the panes' intended sizes rather than their bare minimums: a
screen just wide enough to cram all three onto their floors at once — an Android tablet held in
portrait, say — counts as narrower and shows two, while a large Android tablet in landscape is
genuinely wide enough and does get all three. At any narrower width, the feed list instead becomes a
Gmail-style navigation drawer — reached via a hamburger button, and opened automatically the very
first time the app has no feeds and no cloud account yet, so a new user sees it (and the "+" button
to add a first feed) without having to find it themselves. A phone-width screen shows one of the two
remaining panes (article list, article detail) at a time as a hierarchical stack with its own back
control; a tablet-width screen shows both together, permanently — there is no narrower state where
the reader loses its own back control without the article list gaining a permanent one beside it
(see the note on the swipe gesture below). Nothing about either pane's own content changes between
these — only how many are on screen together — with one exception: search. At the 3-pane width the
search field stays where it has always been, in the feed list's sidebar, with results appearing
reactively in the article list beside it. At a narrower width the drawer has no search field of its
own at all — a phone- or tablet-width screen both reach search through the article list's own
header, which is the one pane every narrower width always keeps on screen and therefore the one
place the field can live without duplicating an editable copy of the same query. Tapping it moves
the field to sit directly above the results it filters, in the same header a hamburger button
normally occupies, and it stays put there when the device is rotated between phone and tablet width.

Where a mouse and a touchscreen need different affordances, both are supported without changing
the underlying action: reordering a feed or folder is a plain click-and-drag with a mouse, and a
drag from a dedicated handle icon with touch (touch needs a distinct starting gesture so the rest
of the row can still be scrolled normally) — both work the same way inside the drawer as they did
in the sidebar it replaced; a right-click context menu on desktop is a long-press menu on Android.
Settings — reached from the desktop application menu — gets its own entry point on Android, which
has no menu bar: a labelled row fixed to the bottom of the feed list/drawer, below the scrolling
folder/tag/feed list, with the drawer's own header showing the app's name in its place; the settings
screen itself also carries its own back arrow, since its near-fullscreen dialog otherwise leaves no
other tappable way out. List rows (feeds, folders, tags, articles) grow to a taller, M3-minimum
touch density on Android. Keyboard navigation (arrow keys between panes, J/K between articles,
F2/Delete on the selected feed-list item) is not desktop-exclusive: an Android tablet can have a
physical keyboard attached, and the same shortcuts work there too, including reaching into the
feed-list drawer at a narrower layout. The selected row in whichever pane holds keyboard focus
gets a `secondary` outline to show where a keypress will land — Android's own M3 selection color
does not change with pane focus the way desktop's dimming does, so the outline is the only
on-screen difference between the focused and unfocused pane's own selection there. This outline
only ever appears once a hardware key has actually been pressed, so a touch-only session never
shows a focus indicator meant for keyboard navigation. Pressing ↓/↑ while the search field itself
is focused moves down into the result list instead of typing a caret movement the field has no use
for, so a keyboard can move straight from typing a query to browsing its results without an
intermediate tap.

At a narrow layout (both phone- and tablet-width), the article detail pane also gains a touch-only
affordance with no desktop counterpart: a horizontal swipe on the reader moves to the next/previous
article in the same order the list itself shows, following the finger as it drags and settling into
place once released; dragging past either end of the list still moves the content a little before
springing back, so the boundary is felt rather than the gesture simply doing nothing. This is
available whenever the reader is on screen at all at a narrower-than-desktop width — a phone-width
screen drilled into an article, and a tablet-width screen (in portrait, and in landscape on any
tablet not wide enough for all three panes), where the reader is a permanent neighbor of the article
list with no back control of its own, the same as the tablet's own reading pane in Gmail — since the
same reader, the same list order, and the same next/previous action apply regardless of whether the
reader happens to have a back button beside it. At the desktop-width 3-pane layout — which a large
tablet in landscape also reaches — the reader is a permanent, keyboard-driven pane instead (J/K —
see `app-architecture.md`), and the swipe gesture does not apply there.

The surfaces that are not drawn by Compose — the application menu bar, context menus, and the
dialog button row — are real Swing/AWT widgets, so they follow the platform's Look & Feel.
macOS and Windows use the system one; Linux uses FlatLaf tinted to the app's own teal theme,
because Java's Linux system L&F is a GTK2-era emulation that looks dated next to a modern
desktop. Light / dark follows the in-app theme setting without a restart. Context menus are
`java.awt.PopupMenu` (a genuine `NSMenu`) on macOS and `javax.swing.JPopupMenu` on Windows and
Linux — on Linux because AWT's popup ignores the Look & Feel entirely, and on Windows because the
JDK's AWT menu peer ignores display scaling, so above 100% it opens away from the cursor and paints
its own labels on top of each other (see `known-issues.md`). The tray menu follows the same split,
which is why Windows has its own `WindowsTray` instead of Compose's `Tray()`. The OPML
import/export file dialog follows a different split: `java.awt.FileDialog` on macOS **and
Windows**, and `javax.swing.JFileChooser` only on Linux, where AWT's own file dialog routes
through GTK native code that can crash the app (see `app-architecture.md` and `known-issues.md`).
The UI font is the OS's own: SF Pro on macOS, Segoe UI on Windows, and on Linux the font resolved by
the Look & Feel, then the desktop's configured font from XSettings, falling back to Adwaita Sans /
Cantarell / Ubuntu / Noto Sans / DejaVu Sans.

## 10. Privacy & Security

- No data sent to external servers, no account registration required, HTTPS only.
- Dropbox token is stored in the OS secure storage — Keychain on macOS (via the `security` CLI), Credential Manager / Secret Service on Windows/Linux via java-keyring, or, inside the Snap package specifically, a local store encrypted with a per-app key from the desktop's Secret portal (via libsecret, not java-keyring) — see `docs/sync-architecture.md`'s "Token Storage". Falls back to a file in the data directory when unavailable.

## 11. Technology Choices

| Layer | Technology |
| --- | --- |
| UI | Compose Multiplatform (Material 3 on Android; platform-specific UI elsewhere — see §9) |
| State management | androidx.lifecycle ViewModel + Koin |
| DB | SQLDelight (SQLite) + FTS5 (raw SQL) |
| HTTP | Ktor client (CIO) |
| RSS/HTML/XML parsing | ksoup |
| Serialization / datetime | kotlinx-serialization / kotlinx-datetime |
| Cloud sync | Ktor + Dropbox / Google Drive / OneDrive (Microsoft Graph) REST API (OAuth PKCE + refresh token) |
| i18n | Compose Resources |
| Testing | kotlin-test + kotlinx-coroutines-test + Ktor MockEngine |
| Build | Gradle 9.6 (Kotlin 2.4 / Compose 1.11 / JDK 25 toolchain) |
| Image loading | Coil3 (favicon display. SVG decode support, shared existing HttpClient, disk cache) |

Both the feed list and article list display favicons (`feeds.favicon_url`) using Coil3 `AsyncImage`. If not yet fetched or loading fails, fall back to a letter (initial) avatar or generic icon.

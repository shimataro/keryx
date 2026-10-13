# App Store submission materials

Draft copy and answers for **App Store Connect** (iOS / iPadOS and the Mac App Store). Nothing here
is read by any build or workflow — every value is copy-pasted into App Store Connect by hand. The
structure mirrors [`../play/`](../play/README.md).

## Layout

```text
listing/
├── en-US/
│   ├── name.txt              # ≤ 30 characters
│   ├── subtitle.txt          # ≤ 30 characters
│   ├── promotional-text.txt  # ≤ 170 characters
│   ├── description.txt       # ≤ 4000 characters
│   └── keywords.txt          # ≤ 100 bytes (UTF-8), comma-separated
└── ja/
    └── (same five files)
screenshots/
└── <en-US|ja>/<iphone|ipad>/{1,2,3}.png   # see "Screenshots" below
```

Content derives from [`README.md`](../../README.md) and [`PRIVACY.md`](../../PRIVACY.md) (and their
`.ja.md` twins); keep `en-US` and `ja` in step with them and with each other.

Two metadata rules (App Review Guidelines 2.3.7 and 2.3.10) shape the text:

- **Describe the Apple app only.** No other platform, store or download source is named — the Play
  listing's "Android" / "Google Play services" / "GitHub for update checks" lines are deliberately
  absent.
- **No third-party brand in the keywords.** Dropbox / OneDrive / Google Drive appear in the
  description only to name the services a user may connect, with a non-affiliation line.

## URLs

| Field | Value |
| --- | --- |
| Support URL | <https://github.com/shimataro/keryx/issues> |
| Marketing URL | <https://keryx.merc.works/> (Japanese: <https://keryx.merc.works/ja/>) |
| Privacy Policy URL | <https://keryx.merc.works/privacy> (Japanese: <https://keryx.merc.works/ja/privacy>) |

## App record (App Store Connect ▸ Apps ▸ New App)

One record covers iOS and macOS: all platforms in a record share the bundle ID, and each platform gets its
own page of metadata ([Add a new app](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app)).
The Account Holder must have signed the latest agreement, and the person adding the app needs the Account
Holder, App Manager or Admin role.

| Field | Value | Notes (from Apple's [App information](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information) reference) |
| --- | --- | --- |
| Platforms | iOS, macOS | |
| Name | `Keryx: RSS Reader` (Japanese: `Keryx: RSSリーダー`) | 2–30 characters; editable until the app is submitted for review and, after that, while the version's status allows it. `Keryx` alone was refused ("the name is already in use" — no public app has it, so it is a private or reserved record); the home-screen name stays `Keryx` (`CFBundleDisplayName`), which is a separate setting |
| Primary language | English (U.S.) | The fallback for metadata a locale doesn't provide; changeable at any time. Japanese is added as a localization |
| Bundle ID | `works.merc.keryx` | Must match the Xcode project; can't be changed after a build is uploaded |
| SKU | `keryx` | An internal tracking ID, not shown to customers; letters, numbers, hyphens, periods and underscores, not starting with a hyphen, period or underscore; **can't be changed once the app is added** |
| User access | Full Access | Every user on the team can see the app (Limited Access would restrict it to chosen users) |

## Answers to App Store Connect questions

- **App Privacy ("nutrition label"): Data Not Collected.** There is no developer server, no
  analytics and no tracking; the sync file goes to the user's own cloud account and the developer
  never receives it. Tracking: No.
- **Export compliance:** the Info.plist sets `ITSAppUsesNonExemptEncryption = NO` (only HTTPS and
  hashing), so the per-upload question is skipped.
- **Age rating:** answer for what the app can show. Keryx has no user-generated content, messaging,
  social features, ads, purchases or gambling, and **Unrestricted Web Access is "No"** — the
  reader opens only the article's own HTML and sends every page-replacing navigation to the
  external browser (see "The SwiftUI reader never navigates the page itself" in
  [`docs/app-architecture.md`](../../docs/app-architecture.md)). The content-theme questions
  (mature themes, violence, …) cover third-party feed content a user may subscribe to; answer them
  honestly for that and accept whatever rating App Store Connect computes.
- **Content rights:** the app shows third-party content (feeds the user chose); confirm the rights
  statement accordingly.
- **Category:** News (matches `LSApplicationCategoryType`). **Price:** free, no in-app purchases,
  no donation links anywhere in the app (guideline 3.1.1).
- **Sign-in required:** No.

## Review notes (paste into "App Review Information")

```text
Keryx is a local-first RSS reader. It has no accounts and no server of its own, so there is nothing
to sign in to. Everything can be reviewed without any account:

1. On first launch choose "Local only" in Setup.
2. Add a feed with the "+" button, e.g. https://www.apple.com/newsroom/rss-feed.rss
3. Select an article to read it; use the toolbar to star it or open it in the browser.

Cloud sync (Dropbox / OneDrive / Google Drive) is optional and needs the reviewer's own account at
that provider. Sign-in happens in the system web authentication sheet (ASWebAuthenticationSession);
Keryx never sees the password, and tokens are kept in the Keychain.

App Transport Security: Info.plist sets NSAllowsArbitraryLoads. Feed URLs are entered by the user
and the app fetches content from servers the developer does not control; some feeds are still
served only over plain http:// and cannot be upgraded by us. An explicit http:// feed URL is honored
(a URL without a scheme is upgraded to HTTPS). Keryx's own traffic (cloud sync, sign-in) is HTTPS only.

The article reader shows the article's own HTML in a WKWebView. It is not a browser: any navigation
that would replace the page is opened in the external browser (http/https only) when the user clicked
it, or dropped otherwise.

iOS: background refresh (BGAppRefreshTask) only fetches the user's subscribed feeds; new-article
notifications are local, and the permission is requested after Setup.
macOS: the app is sandboxed. Closing the window keeps Keryx running in the menu bar so refresh and
notifications continue; "Quit" in the menu bar item or Cmd+Q quits it. There is no login item and
no auto-launch.
```

## Screenshots

Three per device class and locale, uploaded under App Store Connect's 6.9-inch iPhone and 13-inch iPad
slots (smaller sizes are scaled from these). Taken in the iOS Simulator with the app's own data, in the
same order as the Android set (`fastlane/README.md`):

| File | iPhone 17 Pro Max (1320×2868) | iPad Pro 13-inch (M5) (2752×2064 landscape, 2064×2752 portrait) |
| --- | --- | --- |
| `1.png` | Article list | Three panes, landscape, with the picture of the day open |
| `2.png` | Reader (an English featured article) | Three panes, landscape, with a featured article open |
| `3.png` | Feed list with folders and tags | Three panes, portrait |

To retake them:

- Build the app for the simulator, create one simulator per device class, `simctl erase` it, install the
  app, and write `{"notificationEnabled":false}` to `Library/Application Support/Keryx/local_settings.json`
  in its data container (the file's presence marks Setup as done and nothing asks for notification
  permission). Set the language and region with `-AppleLanguages "(ja)" -AppleLocale ja_JP` when launching.
- Import an OPML with `simctl openurl <device> file://<path>.opml`. Use only feeds whose content is safe in a
  store listing and avoid commercial news sites, as for Android. The current set — en-US: NASA and English
  Wikipedia (featured articles, picture of the day); ja: JAXA press releases, NAOJ news and Japanese
  Wikipedia (featured articles), plus the two English Wikipedia feeds for the reader shots, because the
  simulator's web view draws Japanese text as empty boxes (Safari does too; the app is unaffected). Put
  the feeds in folders and give them tags so the feed list shows both. Open articles by their title rather
  than by position, and check every headline and every opened article (text and picture) in the shots:
  Wikipedia's daily picks change every day and can be unsuitable (violence, third-party products).
- Freeze the status bar with `simctl status_bar <device> override --time 9:41 --batteryState charged
  --batteryLevel 100 --wifiBars 3 --cellularBars 4 --cellularMode active`, drive the app with a throwaway
  XCUITest target that calls `XCUIScreen.main.screenshot()` (not committed — add it to `project.yml` with
  `platform: auto` and `supportedDestinations: [iOS]`, then restore `project.yml`), and wait about 40 s after
  launch so the feeds and their favicons have loaded. A landscape iPad screenshot is stored in portrait pixels
  with an EXIF orientation tag, so viewers show it upright but App Store Connect may not; turn the pixels with
  `sips -r 270` (the step below strips the tag).
- Shrink every file losslessly with `optipng -o7 -strip all` before committing.

## Before submitting

- [ ] Publish the updated privacy policy and terms on <https://keryx.merc.works/privacy> and
  `/terms` (and the `/ja/` pages) — the repo's [`PRIVACY.md`](../../PRIVACY.md) /
  [`TERMS.md`](../../TERMS.md) now cover iOS / iPadOS, the website copies live outside this repo.
- [ ] Check that <https://keryx.merc.works/> does not push other platforms' downloads in a way that
  would make the App Store listing look like it promotes them (guideline 2.3.10).
- [x] Brand names (guideline 5.2.1): checked against each company's terms on 2026-10-11. The app shows no
  provider logo or icon anywhere — Dropbox, OneDrive and Google Drive are named in text only, which all three
  allow without a license. Microsoft requires a trademark license for its Microsoft 365 app icons, OneDrive's
  included, and asks third-party marketing to put "Microsoft" before the app name on first mention
  ([Microsoft 365 Trademark Guidelines, June 2026](https://cdn-dynmedia-1.microsoft.com/is/content/microsoftcorp/microsoft/mscle/documents/presentations/FY27_Microsoft_365_trademark_guidelines.pdf)),
  so every store listing says "Microsoft OneDrive" the first time. Google asks for the full name "Google Drive"
  and a trademark attribution in the description, which both descriptions carry
  (<https://developers.google.com/drive/branding>).
- [x] Marketing version: the same number as the GitHub release (0.26.0 for the first App Store build), passed at
  archive time as `MARKETING_VERSION` — not the `0.1.0` left in `appleApp/project.yml`, which no release uses —
  with `CURRENT_PROJECT_VERSION` raised for every upload of the same version. Set the App Store Connect
  version to the same number before uploading.
- [ ] Verify the example feed in the review notes still resolves.
- [ ] If [`PRIVACY.md`](../../PRIVACY.md)'s effective date has changed since the last submission,
  re-check the App Privacy answer, the age-rating answers and the review notes above against it —
  App Store Connect keeps the old answers and will not notice the policy moved.
- [ ] Build the archive with the Dropbox, OneDrive and Google Drive (Apple) keys set — `dropbox.app.key`,
  `onedrive.client.id` and `googledrive.apple.client.id` in `local.properties`, or the matching
  environment variables (see [`docs/build.md`](../../docs/build.md)). A provider whose key is empty is
  hidden, which would contradict the listing and the review notes: check that Settings ▸ Cloud sync
  offers all three.
- [ ] Screenshots: iPhone and iPad are in `screenshots/` (see above). The Mac ones are not taken yet: they are
  listing assets, not part of the Mac App Store build, so take them when that build is ready, keep them in
  `screenshots/<en-US|ja>/mac/`, and upload them under the version's App Previews and Screenshots ▸ macOS in
  App Store Connect. The required sizes change with App Store Connect.
- [ ] Build with the Xcode version App Store Connect currently requires — Xcode 26 or later since
  2026-04-28; Apple raises it periodically, so check its "Upcoming requirements" news first — then run
  **Validate App** in Organizer: it must report no ITMS-90474 (orientations) and no ITMS-91053
  (privacy manifest) problems. This needs the team's signing identity.
- [ ] Mac App Store build only: remove the self-updater first — Sparkle, its `SU*` Info.plist keys,
  the "Check for Updates…" menu item and automatic-check toggle, and the `mach-lookup` temporary
  exception in `Keryx-macOS.entitlements` (guideline 2.4.5(vii)). When that lands, also note in
  [`PRIVACY.md`](../../PRIVACY.md) that the Mac App Store build never contacts GitHub.
- [ ] Mac App Store build only: `springLoadingDelay` (`appleApp/Keryx/Home/FeedListDropPresentation.swift`)
  reads the system's spring-loading preference from the global defaults domain, which the privacy
  manifest's UserDefaults reason `CA92.1` (the app's own data) does not cover. If App Store Connect
  enforces required-reason declarations for the macOS upload, drop that read (use the 0.5 s default,
  as iOS does) rather than declaring a reason that does not fit.
- [ ] Mac App Store build only: extend [`TERMS.md`](../../TERMS.md) / `TERMS.ja.md` to cover macOS —
  their **Applies to** line and the "If you got Keryx from Apple's App Store" section name only
  iOS / iPadOS — and update their effective date.

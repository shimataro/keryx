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
│   └── keywords.txt          # ≤ 100 characters, comma-separated
└── ja/
    └── (same five files)
```

Content derives from [`README.md`](../../README.md) and [`PRIVACY.md`](../../PRIVACY.md) (and their
`.ja.md` twins); keep `en-US` and `ja` in step with them and with each other.

Two metadata rules (App Review Guidelines 2.3.7 and 2.3.10) shape the text:

- **Describe the Apple app only.** No other platform, store or download source is named — the Play
  listing's "Android" / "Google Play services" / "GitHub for update checks" lines are deliberately
  absent.
- **No third-party brand in the keywords.** Dropbox / Google Drive / OneDrive appear in the
  description only to name the services a user may connect, with a non-affiliation line.

## URLs

| Field | Value |
| --- | --- |
| Support URL | <https://github.com/shimataro/keryx/issues> |
| Marketing URL | <https://keryx.merc.works/> (Japanese: <https://keryx.merc.works/ja/>) |
| Privacy Policy URL | <https://keryx.merc.works/privacy> (Japanese: <https://keryx.merc.works/ja/privacy>) |

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

Cloud sync (Dropbox / Google Drive / OneDrive) is optional and needs the reviewer's own account at
that provider. Sign-in happens in the system web authentication sheet (ASWebAuthenticationSession);
Keryx never sees the password, and tokens are kept in the Keychain.

App Transport Security: Info.plist sets NSAllowsArbitraryLoads. Feed URLs are entered by the user
and the app fetches content from servers the developer does not control; some feeds are still
served only over plain http:// and cannot be upgraded by us. An explicit http:// feed URL is honored
(a URL without a scheme is upgraded to HTTPS). Keryx's own traffic (cloud sync, sign-in) is HTTPS only.

The article reader shows the article's own HTML in a WKWebView. It is not a browser: any navigation
that would replace the page is opened in the external browser (http/https only) when the user clicked
it, or dropped otherwise; a click synthesized by a script is ignored.

iOS: background refresh (BGAppRefreshTask) only fetches the user's subscribed feeds; new-article
notifications are local, and the permission is requested after Setup.
macOS: the app is sandboxed. Closing the window keeps Keryx running in the menu bar so refresh and
notifications continue; "Quit" in the menu bar item or Cmd+Q quits it. There is no login item and
no auto-launch.
```

## Before submitting

- [ ] Publish the updated privacy policy and terms on <https://keryx.merc.works/privacy> and
  `/terms` (and the `/ja/` pages) — the repo's [`PRIVACY.md`](../../PRIVACY.md) /
  [`TERMS.md`](../../TERMS.md) now cover iOS / iPadOS, the website copies live outside this repo.
- [ ] Check that <https://keryx.merc.works/> does not push other platforms' downloads in a way that
  would make the App Store listing look like it promotes them (guideline 2.3.10).
- [ ] Confirm the Dropbox, Google (Drive) and Microsoft (OneDrive) logos in
  `appleApp/Keryx/Resources/Assets.xcassets` follow each company's current brand guidelines, and add
  the attribution they ask for to `THIRD-PARTY-LICENSES.md` if any.
- [ ] Decide the marketing version (`MARKETING_VERSION` in `appleApp/project.yml` is `0.1.0`; a 0.x
  number can read as pre-release software to a reviewer, guideline 2.2).
- [ ] Verify the example feed in the review notes still resolves.
- [ ] Screenshots for iPhone, iPad and Mac (the required sizes change with App Store Connect).
- [ ] Build with Xcode 26 or later (the minimum SDK for uploads since 2026-04-28), then run
  **Validate App** in Organizer: it must report no ITMS-90474 (orientations) and no ITMS-91053
  (privacy manifest) problems. This needs the team's signing identity.
- [ ] Mac App Store build only: remove the self-updater first — Sparkle, its `SU*` Info.plist keys,
  the "Check for Updates…" menu item and automatic-check toggle, and the `mach-lookup` temporary
  exception in `Keryx-macOS.entitlements` (guideline 2.4.5(vii)). When that lands, also note in
  [`PRIVACY.md`](../../PRIVACY.md) that the Mac App Store build never contacts GitHub.

---
paths:
  - "**/PlatformModule.desktop.kt"    # where each provider's OAuthConnectFlow transport is wired
  - "**/PlatformModule.android.kt"    # same wiring on Android (Dropbox/OneDrive only)
  - "**/OAuthRedirectTransport*.kt"   # CustomUri (keryx://) vs Loopback (127.0.0.1) transports
  - "**/OAuthConnectFlow*.kt"         # the shared, provider-agnostic PKCE orchestrator
  - "**/CloudStorageAvailability*.kt" # CloudStorageType enum + per-provider availability
  - "**/AndroidOAuthCallback.kt"      # Android's keryx:// redirect dispatch into the callback flow
---

# Design policy: OAuth redirect transport — prefer the custom URI scheme

When adding (or reconsidering) the OAuth redirect transport for a cloud sync provider, and
**both the loopback (`http://localhost`) and the custom URI scheme (`keryx://`) are viable**,
**prefer the custom URI scheme** — wire `CustomUriRedirectTransport(callbackFlow)` rather than
`LoopbackRedirectTransport(...)` in the provider's `OAuthConnectFlow`.

Fall back to `LoopbackRedirectTransport` **only** when the provider's OAuth rejects custom
schemes (e.g. Google's "Desktop app" clients accept only `http://127.0.0.1` loopback).

## Why

- Reuses the shared `keryx://oauth2/callback` scheme, which is already registered on all three
  desktop platforms and routed by `main.kt`; concurrent flows are disambiguated by `state`, so
  **no new plist / registry / desktop-entry registration is needed**.
- Consistent with the Dropbox provider; avoids spinning up a loopback HTTP server.

How the scheme gets registered differs per platform: macOS declares it in Info.plist
(`CFBundleURLTypes`) at packaging time, while Windows and Linux register it at startup from
`registerCustomUriScheme()` — the registry on Windows, a user-level `.desktop` entry plus a
`mimeapps.list` association on Linux (`LinuxUriSchemeRegistrar`).

## Accepted tradeoff

A custom-URI provider cannot be linked from `./gradlew :composeApp:run` on **any** desktop OS —
use `./gradlew :composeApp:createDistributable` and launch the packaged app to verify. On macOS
LaunchServices routes `keryx://` to the packaged `Keryx.app`; on Windows and Linux the runtime
registration deliberately no-ops unless the process is a packaged launcher, since registering the
JDK's own `java` binary as the handler would outlive the Gradle run. This is the same known
constraint already documented for Dropbox and is accepted.

## Current wiring (as of the Android Dropbox/OneDrive addition)

- Dropbox — custom URI (`CustomUriRedirectTransport`) on both desktop and Android.
- OneDrive — custom URI (`CustomUriRedirectTransport`) on both desktop and Android; Microsoft
  Identity platform supports custom schemes.
- Google Drive — loopback (`LoopbackRedirectTransport`) on desktop only; Google's Desktop-app
  client requires it. **Not available on Android at all** — Google's own OAuth policy deprecates
  both the custom-URI-scheme and loopback redirects for its Android/Chrome-app client type (this is
  a Google-specific policy decision, not a general Android restriction: Dropbox/OneDrive's custom-
  URI redirects work identically on Android). See `docs/sync-architecture.md`'s "Google Drive on
  Android" for the full investigation and why the platform's suggested replacement
  (Play services `AuthorizationClient`) is deferred rather than adopted.
- Android registers the shared `keryx://oauth2/callback` scheme declaratively via an
  `AndroidManifest.xml` `intent-filter` on `MainActivity` (no runtime registration step, unlike
  Windows/Linux), and forwards the redirect through `dispatchOAuthCallbackIfPresent` into the same
  `MutableSharedFlow<OAuthCallbackParams>` shape desktop uses — see `AndroidOAuthCallback.kt`
  and `di/PlatformModule.android.kt`.

---
paths:
  - "**/PlatformModule.desktop.kt"    # where each provider's OAuthConnectFlow transport is wired
  - "**/OAuthRedirectTransport*.kt"   # CustomUri (keryx://) vs Loopback (127.0.0.1) transports
  - "**/OAuthConnectFlow*.kt"         # the shared, provider-agnostic PKCE orchestrator
  - "**/CloudStorageAvailability*.kt" # CloudStorageType enum + per-provider availability
---

# Design policy: OAuth redirect transport — prefer the custom URI scheme

When adding (or reconsidering) the OAuth redirect transport for a cloud sync provider, and
**both the loopback (`http://localhost`) and the custom URI scheme (`keryx://`) are viable**,
**prefer the custom URI scheme** — wire `CustomUriRedirectTransport(callbackFlow)` rather than
`LoopbackRedirectTransport(...)` in the provider's `OAuthConnectFlow`.

Fall back to `LoopbackRedirectTransport` **only** when the provider's OAuth rejects custom
schemes (e.g. Google's "Desktop app" clients accept only `http://127.0.0.1` loopback).

## Why

- Reuses the already-registered shared `keryx://oauth2/callback` scheme (OS-registered on all
  three desktop platforms, routed by `main.kt`); concurrent flows are disambiguated by `state`,
  so **no new plist / registry / scheme registration is needed**.
- Consistent with the Dropbox provider; avoids spinning up a loopback HTTP server.

## Accepted tradeoff

On macOS, LaunchServices routes `keryx://` to the packaged `Keryx.app`, so
`./gradlew :composeApp:run` cannot complete linking for a custom-URI provider — use
`./gradlew :composeApp:createDistributable` and launch `Keryx.app` to verify. This is the same
known constraint already documented for Dropbox and is accepted.

## Current wiring (as of the OneDrive addition)

- Dropbox — custom URI (`CustomUriRedirectTransport`).
- OneDrive — custom URI (`CustomUriRedirectTransport`); Microsoft Identity platform supports
  custom schemes.
- Google Drive — loopback (`LoopbackRedirectTransport`); Google's Desktop-app client requires it.

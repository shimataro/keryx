package works.merc.keryx.app

import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.Koin
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.parseOAuthUri

/**
 * Dispatches a raw launch argument — an Android `Intent`'s data URI string — into the shared OAuth
 * callback flow if (and only if) it's a `keryx://` OAuth redirect. The Android counterpart of
 * desktop's `main.kt` `dispatchLaunchArg`/`dispatchOAuthCallback`.
 *
 * Public rather than `internal`: `MainActivity` lives in the separate `:androidApp` Gradle module,
 * which `internal`'s module-scoped visibility would put out of reach (same reason
 * `runAndroidStartupTasks` is public) — but this file itself is `androidMain`, part of
 * `:composeApp` like `commonMain`'s `LaunchArg.kt`, so it can see `classifyLaunchArg`/`LaunchArg`
 * directly despite their `internal` visibility.
 *
 * Only [LaunchArg.OAuthCallback] applies here — an `.opml` open (Android's counterpart to
 * desktop's file-association launch arg) is a separate `ACTION_VIEW` intent-filter, handled by
 * `handleOpmlOpenIfPresent` (`AndroidOpmlOpen.kt`) instead.
 *
 * @return `true` if [uri] was a `keryx://` callback and was dispatched (or was recognized but
 * malformed), so the caller can decide whether to clear the intent's data (avoiding reprocessing
 * it on a later recreation, e.g. a screen rotation replaying the same `Intent`).
 */
fun dispatchOAuthCallbackIfPresent(koin: Koin, uri: String?): Boolean {
    if (uri == null || classifyLaunchArg(uri) !is LaunchArg.OAuthCallback) return false
    // The `keryx://` intent-filter is not exclusively ours (see cloud-oauth-transport.md /
    // sync-architecture.md's "Android" section) — a malformed URI from another app or a manual
    // xdg-open-style invocation must not crash MainActivity. Same guard desktop's main.kt already
    // applies around parseOAuthUri via runCatching.
    runCatching { koin.get<MutableSharedFlow<OAuthCallbackParams>>().tryEmit(parseOAuthUri(uri)) }
    return true
}

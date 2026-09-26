package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.OAUTH_CUSTOM_URI_REDIRECT

/**
 * Delivers the OAuth 2.0 browser redirect back to the running app. The two
 * strategies differ by provider requirement: Dropbox accepts an arbitrary custom
 * URI scheme routed by the OS ([CustomUriRedirectTransport]); Google's "Desktop
 * app" clients only accept a loopback address, so a short-lived local HTTP server
 * receives it (`LoopbackRedirectTransport`).
 */
interface OAuthRedirectTransport {
    /**
     * Establishes the redirect endpoint, invokes [launchBrowser] with the concrete
     * `redirect_uri` to use (so the caller can build and open the authorize URL),
     * then suspends until the matching callback (by [state]) arrives or
     * [timeoutMillis] elapses. Returns null on timeout.
     */
    suspend fun capture(
        state: String,
        timeoutMillis: Long,
        launchBrowser: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams?
}

/**
 * Custom-URI-scheme transport (Dropbox). The redirect_uri is a fixed
 * `keryx://…` URL that the OS routes to the running instance; `main.kt` parses the
 * delivered URI and emits it into [callbackFlow]. This transport just waits for
 * the entry whose state matches.
 */
class CustomUriRedirectTransport(
    private val callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
    private val redirectUri: String = OAUTH_CUSTOM_URI_REDIRECT,
) : OAuthRedirectTransport {
    override suspend fun capture(
        state: String,
        timeoutMillis: Long,
        launchBrowser: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams? =
        // Launch the browser from onSubscription (i.e. *after* this collector is subscribed to the
        // shared flow) so a fast redirect can't be delivered before we're listening. callbackFlow is
        // replay=0, so an emission that lands before subscription would otherwise be lost forever and
        // the wait would run to the full timeout. See docs/sync-architecture.md.
        withTimeoutOrNull(timeoutMillis) {
            callbackFlow
                .onSubscription { launchBrowser(redirectUri) }
                .first { it.state == state }
        }
}

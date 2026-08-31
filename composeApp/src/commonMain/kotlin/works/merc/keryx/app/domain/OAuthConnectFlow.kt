package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.OAUTH_CONNECT_TIMEOUT_MS
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.Pkce
import works.merc.keryx.app.platform.BrowserOpener

/**
 * Generic desktop OAuth 2.0 authorization-code-with-PKCE connect flow, shared by
 * every provider. It generates the PKCE verifier/challenge and state, has the
 * [transport] deliver the redirect (custom URI scheme for Dropbox, loopback
 * server for Google), opens the authorize URL in the browser, waits for the
 * callback, validates the state, and exchanges the code for tokens via
 * [authManager].
 *
 * Only the redirect [transport] differs between providers; the OAuth
 * orchestration is identical, so this class is not duplicated per provider.
 */
class OAuthConnectFlow(
    private val authManager: CloudAuthManager,
    private val clientId: String,
    private val transport: OAuthRedirectTransport,
    private val timeoutMillis: Long = OAUTH_CONNECT_TIMEOUT_MS,
) : CloudConnectFlow {

    override suspend fun connect(): Result<OAuthTokens> {
        if (clientId.isEmpty()) {
            Log.warn(TAG, "Cloud provider is not configured (empty client id)")
            return Result.Err(CloudAuthException("Cloud provider is not configured"))
        }

        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = Pkce.generateVerifier().take(24)

        // The transport determines the redirect_uri (fixed for custom-URI, per-attempt for loopback),
        // so capture it here to reuse for the token exchange.
        var redirectUri: String? = null
        val callback = transport.capture(state, timeoutMillis) { uri ->
            redirectUri = uri
            BrowserOpener.open(authManager.buildAuthorizeUrl(clientId, uri, challenge, state))
        } ?: run {
            // Timed out: the OAuth redirect callback never reached the app within timeoutMillis.
            // On macOS this most often means the keryx:// URI was routed to a different/stale bundle
            // (or an App-Translocated copy) rather than the running instance. See docs/sync-architecture.md.
            Log.warn(TAG, "OAuth authorization timed out after ${timeoutMillis}ms — callback never received")
            return Result.Err(CloudAuthException("Authorization timed out"))
        }

        if (callback.error != null) {
            // Never log callback.code/state/token values here — only the provider-supplied error
            // fields (never secrets) so a rejection is diagnosable after release. Both fields come
            // from an attacker-controllable redirect URI, so strip CR/LF and bound their length
            // before interpolation to prevent forged log lines (CWE-117).
            val error = sanitizeForLog(callback.error, CLOUD_ERROR_BODY_PREVIEW_LENGTH)
            val description = callback.errorDescription?.let { sanitizeForLog(it, CLOUD_ERROR_BODY_PREVIEW_LENGTH) }
            Log.warn(TAG, "OAuth authorization callback returned an error: $error ($description)")
            return Result.Err(CloudAuthException(callback.error))
        }
        if (callback.state != state) {
            Log.warn(TAG, "OAuth callback state did not match the expected state (possible CSRF)")
            return Result.Err(CloudAuthException("State mismatch (possible CSRF)"))
        }
        val code = callback.code ?: run {
            Log.warn(TAG, "OAuth callback carried no authorization code")
            return Result.Err(CloudAuthException("No authorization code returned"))
        }
        val usedRedirectUri = redirectUri ?: run {
            Log.warn(TAG, "OAuth redirect URI was not established before the callback arrived")
            return Result.Err(CloudAuthException("Redirect URI was not established"))
        }

        return authManager.exchangeCode(clientId, code, verifier, usedRedirectUri)
    }

    private companion object {
        const val TAG = "OAuthConnect"
    }
}

private val LOG_LINE_BREAK_PATTERN = Regex("[\r\n]+")

/** Strips CR/LF (to prevent forged log lines) and truncates [value] to [maxLength] before it is logged. */
internal fun sanitizeForLog(value: String, maxLength: Int): String =
    value.replace(LOG_LINE_BREAK_PATTERN, " ").take(maxLength)

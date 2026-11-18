package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.GOOGLE_AUTHORIZE_ENDPOINT
import works.merc.keryx.app.core.GOOGLE_DRIVE_APPDATA_SCOPE
import works.merc.keryx.app.core.GOOGLE_REVOKE_ENDPOINT
import works.merc.keryx.app.core.GOOGLE_TOKEN_ENDPOINT
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock

/**
 * Handles the Google OAuth 2.0 authorization-code-with-PKCE flow. `access_type=offline` (plus
 * `prompt=consent`) requests a refresh token. PKCE (`code_verifier`) is used throughout, but
 * Google's token endpoint still rejects a "Desktop app" client without [clientSecret] on the token
 * request (`invalid_request: client_secret is missing`) — Desktop clients aren't treated as fully
 * public. An "iOS" client (the Apple app's, which also serves macOS) is: pass a `null`
 * [clientSecret] and no `client_secret` is sent. Only the [GOOGLE_DRIVE_APPDATA_SCOPE] hidden
 * app-data folder is requested.
 */
class GoogleDriveAuthManager(
    private val client: HttpClient,
    private val clientSecret: String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = SystemClock,
) : CloudAuthManager {
    override fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = buildPkceAuthorizeUrl(
        GOOGLE_AUTHORIZE_ENDPOINT, clientId, redirectUri, codeChallenge, state, GOOGLE_DRIVE_APPDATA_SCOPE,
    ) {
        append("access_type", "offline")
        // Force the consent screen so a refresh token is returned even on re-authorization.
        append("prompt", "consent")
    }

    override suspend fun exchangeCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<OAuthTokens> = tokenRequest(
        parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("client_id", clientId)
            clientSecret?.let { append("client_secret", it) }
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        },
    )

    /**
     * Refreshes OAuth tokens using an existing refresh token.
     *
     * @param clientId The OAuth client identifier.
     * @param refreshToken The refresh token used to request new tokens.
     * @return The refreshed OAuth tokens, preserving the supplied refresh token when the response omits one.
     */
    override suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens> = tokenRequest(
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
            clientSecret?.let { append("client_secret", it) }
        },
        keepRefreshToken = refreshToken,
    )

    /**
     * Revokes the specified Google OAuth access token.
     *
     * @param accessToken The access token to revoke.
     * @return A successful result if the token is revoked; otherwise, a failed result.
     */
    override suspend fun revoke(accessToken: String): Result<Unit> = revokeOAuthToken {
        client.submitForm(GOOGLE_REVOKE_ENDPOINT, parameters { append("token", accessToken) })
    }

    /**
     * Requests OAuth tokens from Google using the provided form parameters.
     *
     * @param form The form parameters for the token request.
     * @param keepRefreshToken The refresh token to preserve when the response omits one.
     * @return The resulting OAuth tokens or an authentication error.
     */
    private suspend fun tokenRequest(
        form: Parameters,
        keepRefreshToken: String? = null,
    ): Result<OAuthTokens> = requestOAuthTokens(
        client, json, clock, GOOGLE_TOKEN_ENDPOINT, form, keepRefreshToken,
        onFailure = tokenRequestFailureLogger(TAG, "Google"),
    )

    private companion object {
        const val TAG = "GoogleDriveAuth"
    }
}

/**
 * The redirect URI of a Google "iOS" OAuth client: its client id reversed into a custom URL scheme
 * (`NNNN-xxxx.apps.googleusercontent.com` → `com.googleusercontent.apps.NNNN-xxxx:/oauth2redirect`),
 * which Google accepts for that client without registering it separately.
 */
fun googleIosClientRedirectUri(clientId: String): String {
    val scheme = clientId.split('.').reversed().joinToString(".")
    return "$scheme:/oauth2redirect"
}

package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.Result

/**
 * OAuth 2.0 authorization-code-with-PKCE operations for a cloud storage
 * provider, shared by every provider (Dropbox, Google Drive, …): building the
 * authorize URL, exchanging the code for tokens, refreshing, and revoking.
 * Each provider requests offline access so a refresh token is issued; the
 * provider-specific scopes and endpoints live in the concrete implementation.
 */
interface CloudAuthManager {
    /** Builds the browser authorize URL for the PKCE flow. */
    fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String

    /** Exchanges an authorization [code] (with the PKCE [codeVerifier]) for tokens. */
    suspend fun exchangeCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<OAuthTokens>

    /** Refreshes an access token using a stored [refreshToken]. */
    suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens>

    /** Revokes the given [accessToken] (best-effort; used on disconnect). */
    suspend fun revoke(accessToken: String): Result<Unit>
}

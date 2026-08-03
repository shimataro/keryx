package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.DROPBOX_AUTHORIZE_ENDPOINT
import works.merc.keryx.app.core.DROPBOX_REVOKE_ENDPOINT
import works.merc.keryx.app.core.DROPBOX_TOKEN_ENDPOINT
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock

/**
 * Handles the Dropbox OAuth 2.0 authorization-code-with-PKCE flow: building the
 * authorize URL, exchanging the code for tokens, and refreshing. Requests
 * offline access so a refresh token is issued.
 */
class DropboxAuthManager(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = SystemClock,
) : CloudAuthManager {
    private val scopes = "files.content.write files.content.read account_info.read"

    override fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = URLBuilder(DROPBOX_AUTHORIZE_ENDPOINT).apply {
        parameters.append("client_id", clientId)
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge", codeChallenge)
        parameters.append("code_challenge_method", "S256")
        parameters.append("token_access_type", "offline")
        parameters.append("scope", scopes)
        parameters.append("state", state)
    }.buildString()

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
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        },
    )

    override suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens> = tokenRequest(
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
        },
        keepRefreshToken = refreshToken,
    )

    override suspend fun revoke(accessToken: String): Result<Unit> = revokeOAuthToken {
        client.post(DROPBOX_REVOKE_ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
        }
    }

    /**
     * Requests OAuth tokens using the supplied form parameters.
     *
     * @param form The form parameters for the token request.
     * @param keepRefreshToken The refresh token to retain in the result, if applicable.
     * @return The result of the OAuth token request.
     */
    private suspend fun tokenRequest(
        form: io.ktor.http.Parameters,
        keepRefreshToken: String? = null,
    ): Result<OAuthTokens> = requestOAuthTokens(client, json, clock, DROPBOX_TOKEN_ENDPOINT, form, keepRefreshToken)
}

package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.ONEDRIVE_AUTHORIZE_ENDPOINT
import works.merc.keryx.app.core.ONEDRIVE_SCOPES
import works.merc.keryx.app.core.ONEDRIVE_TOKEN_ENDPOINT
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock

/**
 * Handles the Microsoft Identity platform OAuth 2.0 authorization-code-with-PKCE
 * flow for OneDrive: building the authorize URL, exchanging the code for tokens,
 * and refreshing. `offline_access` (in [ONEDRIVE_SCOPES]) is requested so a
 * refresh token is issued.
 *
 * Unlike Google's "Desktop app" client, a Microsoft native/desktop app registered
 * as a public client uses PKCE **without a client secret**, so — like Dropbox —
 * this manager takes no secret.
 */
class OneDriveAuthManager(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = SystemClock,
) : CloudAuthManager {
    private val scopes = ONEDRIVE_SCOPES

    override fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = URLBuilder(ONEDRIVE_AUTHORIZE_ENDPOINT).apply {
        parameters.append("client_id", clientId)
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge", codeChallenge)
        parameters.append("code_challenge_method", "S256")
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
            append("scope", scopes)
        },
    )

    override suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens> = tokenRequest(
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
            append("scope", scopes)
        },
        keepRefreshToken = refreshToken,
    )

    /**
     * No-op: the Microsoft Identity platform has no standard OAuth token-revocation
     * endpoint, so disconnect relies on clearing the locally stored tokens (done by
     * [works.merc.keryx.app.domain.CloudSession.disconnect]). Reported as success so
     * disconnect proceeds cleanly.
     */
    override suspend fun revoke(accessToken: String): Result<Unit> = Result.Ok(Unit)

    /**
     * Requests OAuth tokens using the supplied form parameters.
     *
     * @param form The parameters for the token request.
     * @param keepRefreshToken The refresh token to preserve in the result, if provided.
     * @return The resulting OAuth tokens or a request failure.
     */
    private suspend fun tokenRequest(
        form: Parameters,
        keepRefreshToken: String? = null,
    ): Result<OAuthTokens> = requestOAuthTokens(client, json, clock, ONEDRIVE_TOKEN_ENDPOINT, form, keepRefreshToken)
}

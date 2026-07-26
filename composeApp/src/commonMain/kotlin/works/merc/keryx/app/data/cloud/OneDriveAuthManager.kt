package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
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

    private suspend fun tokenRequest(
        form: io.ktor.http.Parameters,
        keepRefreshToken: String? = null,
    ): Result<OAuthTokens> = try {
        val response = client.submitForm(ONEDRIVE_TOKEN_ENDPOINT, form)
        if (response.status.value !in 200..299) {
            Result.Err(CloudAuthException("Token request failed (HTTP ${response.status.value})"))
        } else {
            val dto = json.decodeFromString<TokenResponse>(response.bodyAsText())
            val access = dto.accessToken
            if (access == null) {
                Result.Err(CloudAuthException("Token response had no access_token"))
            } else {
                Result.Ok(
                    OAuthTokens(
                        accessToken = access,
                        refreshToken = dto.refreshToken ?: keepRefreshToken,
                        expiresAtMillis = dto.expiresInSeconds?.let { clock.nowMillis() + it * 1000L },
                    ),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.Err(CloudAuthException(e.message ?: "Token request failed"))
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresInSeconds: Long? = null,
    )
}

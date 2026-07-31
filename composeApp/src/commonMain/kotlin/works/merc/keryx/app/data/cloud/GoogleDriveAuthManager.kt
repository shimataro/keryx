package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.GOOGLE_AUTHORIZE_ENDPOINT
import works.merc.keryx.app.core.GOOGLE_DRIVE_APPDATA_SCOPE
import works.merc.keryx.app.core.GOOGLE_REVOKE_ENDPOINT
import works.merc.keryx.app.core.GOOGLE_TOKEN_ENDPOINT
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SystemClock

/**
 * Handles the Google OAuth 2.0 authorization-code-with-PKCE flow for a "Desktop
 * app" client. `access_type=offline` (plus `prompt=consent`) requests a refresh
 * token. PKCE (`code_verifier`) is used throughout, but Google's token endpoint
 * still rejects "Desktop app" clients without [clientSecret] on the token
 * request (`invalid_request: client_secret is missing`) — unlike iOS/Android
 * clients, Desktop clients aren't treated as fully public. Only the
 * [GOOGLE_DRIVE_APPDATA_SCOPE] hidden app-data folder is requested.
 */
class GoogleDriveAuthManager(
    private val client: HttpClient,
    private val clientSecret: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = SystemClock,
) : CloudAuthManager {
    private val scopes = GOOGLE_DRIVE_APPDATA_SCOPE

    override fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = URLBuilder(GOOGLE_AUTHORIZE_ENDPOINT).apply {
        parameters.append("client_id", clientId)
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge", codeChallenge)
        parameters.append("code_challenge_method", "S256")
        parameters.append("access_type", "offline")
        // Force the consent screen so a refresh token is returned even on re-authorization.
        parameters.append("prompt", "consent")
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
            append("client_secret", clientSecret)
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        },
    )

    override suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens> = tokenRequest(
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
            append("client_secret", clientSecret)
        },
        keepRefreshToken = refreshToken,
    )

    override suspend fun revoke(accessToken: String): Result<Unit> = try {
        // Google's revoke endpoint takes the token as a form parameter (not a Bearer header).
        val response = client.submitForm(GOOGLE_REVOKE_ENDPOINT, parameters { append("token", accessToken) })
        if (response.status.value in 200..299) {
            Result.Ok(Unit)
        } else {
            Result.Err(CloudAuthException("Revoke failed (HTTP ${response.status.value})"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.Err(CloudAuthException(e.message ?: "Revoke failed"))
    }

    /**
     * Requests OAuth tokens from Google using the provided form parameters.
     *
     * @param form The form parameters for the token request.
     * @param keepRefreshToken The refresh token to preserve when the response omits one.
     * @return The resulting OAuth tokens or an authentication error.
     */
    private suspend fun tokenRequest(
        form: io.ktor.http.Parameters,
        keepRefreshToken: String? = null,
    ): Result<OAuthTokens> = requestOAuthTokens(client, json, clock, GOOGLE_TOKEN_ENDPOINT, form, keepRefreshToken) { status, body ->
        Log.warn(TAG, "Google token request failed (HTTP $status): ${body.take(CLOUD_ERROR_BODY_PREVIEW_LENGTH)}")
    }

    private companion object {
        const val TAG = "GoogleDriveAuth"
    }
}

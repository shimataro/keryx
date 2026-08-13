package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.MILLIS_PER_SECOND
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.TOKEN_EXPIRY_SKEW_MS

/**
 * OAuth 2.0 tokens for a cloud storage provider (Dropbox, Google Drive, …). We
 * request offline access and keep the refresh token so the access token can be
 * renewed silently. The shape is provider-agnostic — every supported provider
 * issues an access token, an optional refresh token, and an expiry.
 */
@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Unix ms when the access token expires; null if unknown. */
    val expiresAtMillis: Long? = null,
) {
    /**
     * Determines whether the token has expired, accounting for the configured clock skew.
     *
     * @param nowMillis The current Unix time in milliseconds.
     * @param skewMillis The time interval in milliseconds used to account for clock skew.
     * @return `true` if an expiration time is set and the current time is at or after the adjusted expiration time, `false` otherwise.
     */
    fun isExpired(nowMillis: Long, skewMillis: Long = TOKEN_EXPIRY_SKEW_MS): Boolean =
        expiresAtMillis != null && nowMillis >= expiresAtMillis - skewMillis
}

@Serializable
private data class OAuthTokenResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
)

/**
 * Exchanges an OAuth form request for token data.
 *
 * @param tokenEndpoint The token endpoint URL.
 * @param form The form parameters submitted to the endpoint.
 * @param keepRefreshToken The refresh token to retain when the response omits one.
 * @param onFailure An optional callback invoked for non-2xx responses with the status code and body.
 * @return A successful result containing the tokens, or an error result when the request or response fails.
 */
internal suspend fun requestOAuthTokens(
    client: HttpClient,
    json: Json,
    clock: Clock,
    tokenEndpoint: String,
    form: Parameters,
    keepRefreshToken: String? = null,
    onFailure: ((status: Int, body: String) -> Unit)? = null,
): Result<OAuthTokens> = try {
    val response = client.submitForm(tokenEndpoint, form)
    if (response.status.value !in 200..299) {
        onFailure?.invoke(response.status.value, response.bodyAsText())
        Result.Err(CloudAuthException("Token request failed (HTTP ${response.status.value})"))
    } else {
        val dto = json.decodeFromString<OAuthTokenResponseDto>(response.bodyAsText())
        val access = dto.accessToken
        if (access == null) {
            Result.Err(CloudAuthException("Token response had no access_token"))
        } else {
            Result.Ok(
                OAuthTokens(
                    accessToken = access,
                    refreshToken = dto.refreshToken ?: keepRefreshToken,
                    expiresAtMillis = dto.expiresInSeconds?.let { clock.nowMillis() + it * MILLIS_PER_SECOND },
                ),
            )
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.Err(CloudAuthException(e.message ?: "Token request failed"))
}

/**
 * Sends a revoke request built by [makeRequest] and maps the outcome to a [Result].
 *
 * @param makeRequest Performs the provider-specific revoke HTTP call.
 * @return `Result.Ok` on a 2xx response, or a [CloudAuthException] on failure.
 */
internal suspend fun revokeOAuthToken(makeRequest: suspend () -> HttpResponse): Result<Unit> = try {
    val response = makeRequest()
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

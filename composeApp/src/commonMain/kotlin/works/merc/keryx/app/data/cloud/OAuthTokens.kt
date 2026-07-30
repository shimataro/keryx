package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Result

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
    fun isExpired(nowMillis: Long, skewMillis: Long = 60_000): Boolean =
        expiresAtMillis != null && nowMillis >= expiresAtMillis - skewMillis
}

@Serializable
private data class OAuthTokenResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
)

/**
 * Submits [form] to [tokenEndpoint] and decodes the response into [OAuthTokens], shared by every
 * [CloudAuthManager]'s token exchange/refresh. [keepRefreshToken] carries the prior refresh token
 * forward when the response omits one (a refresh response often doesn't return a new one).
 * [onFailure] is an optional hook for provider-specific failure logging (only Google logs the
 * response body on a non-2xx status; Dropbox/OneDrive don't read the body in that case at all).
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

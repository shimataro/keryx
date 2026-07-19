package works.merc.keryx.app.data.cloud

import kotlinx.serialization.Serializable

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

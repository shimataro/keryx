package works.merc.keryx.app.domain

/**
 * Parsed query parameters from an OAuth custom-scheme redirect URI
 * (e.g. `keryx://oauth2/callback?code=abc&state=xyz`).
 */
data class OAuthCallbackParams(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

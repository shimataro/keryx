package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.fold
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage

/**
 * Holds the current cloud connection across every supported provider. Only one
 * provider is active at a time — the selected one, read from
 * `local_settings.cloudStorageType` via [selectedType]. Produces a [CloudStorage]
 * for the selected provider whose access token is transparently refreshed
 * (offline tokens), and manages connect/disconnect. When nothing is selected or
 * no tokens are stored, [current] returns null (local-only).
 *
 * [providers] is a small registry keyed by [CloudStorageType]; each entry bundles
 * that provider's client id, token storage, auth manager, interactive connect
 * flow, and a factory that builds its [CloudStorage].
 *
 * Every token write goes through [saveTokensReportingFallback], which raises a
 * notification-center warning whenever a [TokenStorage] could not reach the OS secure store and
 * had to persist the token in the plaintext fallback file instead (see `SECURITY.md`). The
 * fallback itself is deliberate and not blocked; it just must not be silent.
 */
class CloudSession(
    private val providers: Map<CloudStorageType, Provider>,
    private val selectedType: () -> CloudStorageType?,
    private val clock: Clock,
    private val notificationCenter: NotificationCenter,
    private val notificationMessages: NotificationMessages,
) {
    /** Per-provider registry entry. */
    class Provider(
        val clientId: String,
        val tokenStorage: TokenStorage,
        val authManager: CloudAuthManager,
        val connectFlow: CloudConnectFlow,
        /** Builds the provider's [CloudStorage], given a valid-access-token supplier. */
        val createStorage: (accessTokenProvider: suspend () -> String?) -> CloudStorage,
    )

    /** True when a provider is selected, configured in this build, and has stored tokens. */
    fun isConnected(): Boolean = connectedType() != null

    /** The currently-connected provider, or null (nothing selected / not configured / no tokens). */
    fun connectedType(): CloudStorageType? {
        val type = selectedType() ?: return null
        val provider = providers[type] ?: return null
        return if (provider.clientId.isNotEmpty() && provider.tokenStorage.load() != null) type else null
    }

    /** The interactive connect flow for [type], or null if that provider isn't configured. */
    fun connectFlow(type: CloudStorageType): CloudConnectFlow? =
        providers[type]?.takeIf { it.clientId.isNotEmpty() }?.connectFlow

    /** The active provider's storage, or null when local-only. */
    fun current(): CloudStorage? {
        val type = connectedType() ?: return null
        val provider = providers[type] ?: return null
        return provider.createStorage { validAccessToken(provider) }
    }

    /** Persists freshly-obtained tokens for [type] (called right after a successful connect). */
    suspend fun saveTokens(type: CloudStorageType, tokens: OAuthTokens) {
        val provider = providers[type] ?: return
        saveTokensReportingFallback(provider, tokens)
    }

    /** Revokes and clears [type]'s stored tokens. */
    suspend fun disconnect(type: CloudStorageType) {
        val provider = providers[type] ?: return
        provider.tokenStorage.load()?.accessToken?.let { provider.authManager.revoke(it) }
        provider.tokenStorage.clear()
    }

    /**
     * Provides a current access token for the specified provider.
     *
     * Refreshes expired tokens when a refresh token is available and persists the refreshed tokens.
     *
     * @param provider The provider whose stored credentials supply the access token.
     * @return A valid access token, the existing token when it cannot be refreshed, or `null` when no token is stored or refreshing fails.
     */
    private suspend fun validAccessToken(provider: Provider): String? {
        val tokens = provider.tokenStorage.load() ?: return null
        if (!tokens.isExpired(clock.nowMillis())) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return tokens.accessToken
        return provider.authManager.refresh(provider.clientId, refreshToken).fold(
            ok = { refreshed ->
                saveTokensReportingFallback(provider, refreshed)
                refreshed.accessToken
            },
            err = { null },
        )
    }

    /**
     * Persists [tokens] via [provider]'s storage and, when the storage could only reach the
     * plaintext fallback file, records a warning in the notification center.
     *
     * Used by both the initial connect ([saveTokens]) and the background refresh
     * ([validAccessToken]): a refreshed token is exactly as sensitive as the original, and the
     * refresh path recurs — which is why the entry is added with
     * [NotificationCenter.addCoalescing], so a persistently unavailable secret store leaves one
     * bell entry rather than one per refresh.
     */
    private suspend fun saveTokensReportingFallback(provider: Provider, tokens: OAuthTokens) {
        if (provider.tokenStorage.save(tokens)) return
        notificationCenter.addCoalescing(
            AppNotification(
                id = IdGenerator.newId(),
                level = AppNotificationLevel.WARNING,
                message = notificationMessages.tokenStorageFallback(),
                timestampMillis = clock.nowMillis(),
                action = AppNotificationAction.ShowInfoDialog(notificationMessages.tokenStorageFallbackDetail()),
            ),
        )
    }
}

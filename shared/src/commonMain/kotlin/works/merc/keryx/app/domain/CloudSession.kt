package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.fold
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenSaveOutcome
import works.merc.keryx.app.data.cloud.TokenStorage

/**
 * Holds the current cloud connection across every supported provider. Only one
 * provider is active at a time — the selected one, read from
 * `local_settings.cloudStorageType` via [selectedType]. Produces a [CloudStorage]
 * for the selected provider whose credentials are kept valid transparently (an
 * OAuth access token is refreshed with its offline refresh token), and manages
 * connect/disconnect. When nothing is selected or no credentials are stored,
 * [current] returns null (local-only).
 *
 * [providers] is a small registry keyed by [CloudStorageType]; each entry bundles
 * whether that provider is configured in this build, its credential storage, its
 * interactive connect flow, how its credentials are kept valid and revoked
 * ([Provider.lifecycle]), and a factory that builds its [CloudStorage].
 *
 * Every credential write goes through [saveCredentialsReportingFallback], which raises a
 * notification-center warning whenever a [TokenStorage] could not reach the OS secure store —
 * with its own message for credentials that landed in the plaintext fallback file and for ones that
 * could not be persisted at all (see `SECURITY.md`). The fallback itself is deliberate and not
 * blocked; it just must not be silent. Credentials that could not be persisted at all are held in
 * memory by this (process-wide) instance instead, so the session keeps syncing until the app exits
 * — see [unpersisted]. Every credential write and the disconnect's clear are serialized by
 * [credentialLifecycleMutex], so a refresh in flight cannot bring back credentials a disconnect removed.
 */
class CloudSession(
    private val providers: Map<CloudStorageType, Provider>,
    private val selectedType: () -> CloudStorageType?,
    private val clock: Clock,
    private val notificationCenter: NotificationCenter,
) {
    /** Per-provider registry entry. */
    class Provider(
        /**
         * Whether this provider can be used in this build at all — for an OAuth provider, whether
         * its client id was configured. An unconfigured provider is never connected and offers no
         * connect flow.
         */
        val isConfigured: Boolean,
        val tokenStorage: TokenStorage,
        val connectFlow: CloudConnectFlow,
        /** How this provider's credentials are kept valid and revoked. */
        val lifecycle: CredentialLifecycle,
        /**
         * Builds the provider's [CloudStorage], given a supplier of currently-valid credentials. An
         * OAuth storage takes its access token through [works.merc.keryx.app.data.cloud.oauthAccessToken].
         */
        val createStorage: (credentialsProvider: suspend () -> CloudCredentials?) -> CloudStorage,
    )

    /** How a [Provider]'s credentials are kept valid while connected, and revoked on disconnect. */
    sealed interface CredentialLifecycle {
        /** A lifecycle whose credentials are OAuth tokens, revoked on disconnect through [authManager]. */
        sealed interface OAuthRevocable : CredentialLifecycle {
            val authManager: CloudAuthManager
        }

        /**
         * OAuth tokens this app owns: an expired access token is renewed with the stored refresh
         * token through [authManager] (as [clientId]) and the renewed tokens saved; the access token
         * is revoked on disconnect.
         */
        class OAuth(val clientId: String, override val authManager: CloudAuthManager) : OAuthRevocable

        /**
         * An access token the platform owns and mints on demand through [accessTokenProvider],
         * replacing the stored-token + refresh-token path. Nothing is refreshed or saved; the stored
         * credentials only mark the account connected, and their access token is what disconnect
         * revokes through [authManager].
         *
         * Android's Google Drive is the one user: Play services owns the authorization grant and
         * mints a fresh one-hour access token on demand, so there is no refresh token to store and
         * [OAuth]'s "expired with no refresh token" branch — which falls back to the stale token —
         * would hand out a token that is already dead.
         */
        class PlatformOwned(
            val accessTokenProvider: suspend () -> String?,
            override val authManager: CloudAuthManager,
        ) : OAuthRevocable

        /**
         * Credentials that do not expire (an app password): handed out as stored, never refreshed.
         * [revoke] is called with them on disconnect. Nextcloud's lifecycle: its revoke deletes the
         * app password on the server.
         */
        class Static(val revoke: suspend (CloudCredentials) -> Result<Unit>) : CredentialLifecycle
    }

    /**
     * Credentials whose last save returned [TokenSaveOutcome.NOT_PERSISTED], keyed by provider
     * instance. Without this, a connect whose credentials reached no store would report itself
     * connected in the UI while [connectedType] — which reads the store — saw nothing, so every sync
     * was silently skipped. Read in preference to the store: after a refresh whose save failed, the
     * store still holds the previous (possibly rotated-out) credentials.
     */
    private val unpersisted = MutableStateFlow<Map<Provider, CloudCredentials>>(emptyMap())

    /**
     * Serializes the credential lifecycle — the refresh-and-save in [validCredentials], the initial
     * save in [saveCredentials], and the revoke-and-clear in [disconnect]. Without it, a disconnect landing
     * while a refresh is suspended on the network would clear the store, and the refresh would then
     * save its renewed credentials back afterwards: credentials the user just removed, and not revoked
     * either.
     * Held only around the credential work itself, never around the cloud request that uses the
     * returned credentials.
     */
    private val credentialLifecycleMutex = Mutex()

    /** [provider]'s current credentials: the in-memory copy of an unpersisted save, else the store's. */
    private fun credentialsOf(provider: Provider): CloudCredentials? = unpersisted.value[provider] ?: provider.tokenStorage.load()

    /** True when a provider is selected, configured in this build, and has stored credentials. */
    fun isConnected(): Boolean = connectedType() != null

    /** The currently-connected provider, or null (nothing selected / not configured / no credentials). */
    fun connectedType(): CloudStorageType? {
        val type = selectedType() ?: return null
        val provider = providers[type] ?: return null
        return if (provider.isConfigured && credentialsOf(provider) != null) type else null
    }

    /**
     * The server [type]'s stored credentials point at — the base URL of an app-password provider
     * (Nextcloud), or null when there are none or they carry no server (OAuth tokens). Lets a
     * reconnect offer the server the user last connected to.
     */
    fun serverUrlOf(type: CloudStorageType): String? {
        val provider = providers[type] ?: return null
        return (credentialsOf(provider) as? AppPasswordCredentials)?.serverUrl
    }

    /** The interactive connect flow for [type], or null if that provider isn't configured. */
    fun connectFlow(type: CloudStorageType): CloudConnectFlow? =
        providers[type]?.takeIf { it.isConfigured }?.connectFlow

    /** The active provider's storage, or null when local-only. */
    fun current(): CloudStorage? {
        val type = connectedType() ?: return null
        val provider = providers[type] ?: return null
        return provider.createStorage { validCredentials(provider) }
    }

    /** Persists freshly-obtained credentials for [type] (called right after a successful connect). */
    suspend fun saveCredentials(type: CloudStorageType, credentials: CloudCredentials) {
        val provider = providers[type] ?: return
        credentialLifecycleMutex.withLock { saveCredentialsReportingFallback(provider, credentials) }
    }

    /**
     * Revokes and clears [type]'s stored credentials. The local clear happens even when the revoke fails
     * or is cancelled mid-request, so the provider never stays looking connected after a disconnect.
     */
    suspend fun disconnect(type: CloudStorageType) {
        val provider = providers[type] ?: return
        credentialLifecycleMutex.withLock {
            try {
                credentialsOf(provider)?.let { revoke(provider.lifecycle, it) }
            } finally {
                provider.tokenStorage.clear()
                unpersisted.update { it - provider }
            }
        }
    }

    /** Revokes [credentials] the way [lifecycle] says to; best-effort, so the outcome is not inspected. */
    private suspend fun revoke(lifecycle: CredentialLifecycle, credentials: CloudCredentials) {
        when (lifecycle) {
            // Nothing to revoke through the auth manager unless the stored credentials are OAuth tokens.
            is CredentialLifecycle.OAuthRevocable ->
                (credentials as? OAuthTokens)?.let { lifecycle.authManager.revoke(it.accessToken) }
            is CredentialLifecycle.Static -> lifecycle.revoke(credentials)
        }
    }

    /**
     * Provides currently-valid credentials for [provider], according to its [Provider.lifecycle].
     *
     * @param provider The provider whose credentials are wanted.
     * @return The credentials to use, or `null` when none are stored or they could not be renewed.
     */
    private suspend fun validCredentials(provider: Provider): CloudCredentials? = when (val lifecycle = provider.lifecycle) {
        is CredentialLifecycle.OAuth -> validOAuthTokens(provider, lifecycle)
        is CredentialLifecycle.PlatformOwned -> lifecycle.accessTokenProvider()?.let { OAuthTokens(accessToken = it) }
        is CredentialLifecycle.Static -> credentialLifecycleMutex.withLock { credentialsOf(provider) }
    }

    /**
     * Provides current OAuth tokens for the specified provider.
     *
     * Refreshes expired tokens when a refresh token is available and persists the refreshed tokens.
     *
     * @param provider The provider whose stored credentials supply the access token.
     * @param lifecycle [provider]'s OAuth lifecycle, supplying the client id and auth manager.
     * @return Tokens with a valid access token, the existing tokens when they cannot be refreshed, or
     *   `null` when no tokens are stored or refreshing fails.
     */
    private suspend fun validOAuthTokens(provider: Provider, lifecycle: CredentialLifecycle.OAuth): OAuthTokens? =
        credentialLifecycleMutex.withLock {
            val tokens = credentialsOf(provider) as? OAuthTokens ?: return@withLock null
            if (!tokens.isExpired(clock.nowMillis())) return@withLock tokens
            val refreshToken = tokens.refreshToken ?: return@withLock tokens
            lifecycle.authManager.refresh(lifecycle.clientId, refreshToken).fold(
                ok = { refreshed ->
                    saveCredentialsReportingFallback(provider, refreshed)
                    refreshed
                },
                err = { null },
            )
        }

    /**
     * Persists [credentials] via [provider]'s storage and records a warning in the notification center
     * unless they reached the OS secure store. The two degraded outcomes get their own message:
     * [TokenSaveOutcome.PLAINTEXT_FILE] means the credentials are readable in the plaintext fallback
     * file, [TokenSaveOutcome.NOT_PERSISTED] that they were not written anywhere and the account
     * has to be connected again after a restart.
     *
     * Used by both the initial connect ([saveCredentials]) and the background refresh
     * ([validOAuthTokens]): refreshed credentials are exactly as sensitive as the original, and the
     * refresh path recurs — which is why the entry is added with
     * [NotificationCenter.addCoalescing], so a persistently unavailable secret store leaves one
     * notification-center entry rather than one per refresh. Coalescing keys on `AlertKey` (level,
     * text and action), so the two outcomes above collapse independently of each other.
     */
    private suspend fun saveCredentialsReportingFallback(provider: Provider, credentials: CloudCredentials) {
        val outcome = provider.tokenStorage.save(credentials)
        unpersisted.update { if (outcome == TokenSaveOutcome.NOT_PERSISTED) it + (provider to credentials) else it - provider }
        val (text, detail) = when (outcome) {
            TokenSaveOutcome.SECURE -> return
            TokenSaveOutcome.PLAINTEXT_FILE ->
                NotificationText.TokenStorageFallback to InfoDialogText.TOKEN_STORAGE_FALLBACK
            TokenSaveOutcome.NOT_PERSISTED ->
                NotificationText.TokenStorageNotPersisted to InfoDialogText.TOKEN_STORAGE_NOT_PERSISTED
        }
        notificationCenter.addCoalescing(
            newNotification(
                level = AppNotificationLevel.WARNING,
                text = text,
                nowMillis = clock.nowMillis(),
                action = AppNotificationAction.ShowInfoDialog(detail),
            ),
        )
    }
}

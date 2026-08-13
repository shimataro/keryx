package works.merc.keryx.app.data.cloud

/**
 * Persists a cloud provider's OAuth tokens in the OS secure store (Keychain /
 * Credential Manager / Secret Service). One instance is created per provider —
 * they must not be shared, since some implementations cache the loaded value.
 * The provider is distinguished by a per-instance account/file name derived from
 * [works.merc.keryx.app.core.CloudStorageType.id].
 */
interface TokenStorage {
    fun save(tokens: OAuthTokens)
    fun load(): OAuthTokens?
    fun clear()
}

/** Shared [works.merc.keryx.app.core.Log] tag for every [TokenStorage] implementation. */
internal const val TOKEN_STORAGE_LOG_TAG = "TokenStorage"

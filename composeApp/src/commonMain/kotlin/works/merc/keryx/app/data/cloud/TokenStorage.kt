package works.merc.keryx.app.data.cloud

/**
 * Persists a cloud provider's OAuth tokens in the OS secure store (Keychain /
 * Credential Manager / Secret Service). One instance is created per provider —
 * they must not be shared, since some implementations cache the loaded value.
 * The provider is distinguished by a per-instance account/file name derived from
 * [works.merc.keryx.app.core.CloudStorageType.id].
 */
interface TokenStorage {
    /**
     * Persists [tokens]. Implementations never throw — a backend failure degrades to the
     * plaintext-file fallback instead (see `SECURITY.md`), so the connect flow can never be
     * aborted by a storage problem.
     *
     * @return true when the tokens landed in the OS secure store (or, on Android, the
     * Keystore-encrypted file); false when they were only persisted via the plaintext fallback.
     * This is a signal about *how securely* the tokens were stored, not about success or failure —
     * a false return still means the tokens are persisted.
     */
    fun save(tokens: OAuthTokens): Boolean
    fun load(): OAuthTokens?
    fun clear()
}

/** Shared [works.merc.keryx.app.core.Log] tag for every [TokenStorage] implementation. */
internal const val TOKEN_STORAGE_LOG_TAG = "TokenStorage"

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
     * @return where the tokens actually ended up. Not a success/failure flag: of the three
     * outcomes only [TokenSaveOutcome.NOT_PERSISTED] means nothing was written, and even then the
     * caller's in-memory session keeps working until the app exits.
     */
    fun save(tokens: OAuthTokens): TokenSaveOutcome
    fun load(): OAuthTokens?
    fun clear()
}

/**
 * Where a [TokenStorage.save] left the tokens. The two facts a caller needs — whether they survive
 * a restart, and whether anything readable is left on disk — are folded into one closed set
 * because only these three combinations are reachable.
 */
enum class TokenSaveOutcome {
    /**
     * In the OS secure store (on Android, the Keystore-encrypted file), with no plaintext copy
     * left on disk.
     */
    SECURE,

    /** Readable in the 0600 plaintext fallback file, because the secure store was unreachable. */
    PLAINTEXT_FILE,

    /**
     * Not persisted anywhere — the secure store could not be reached and the fallback write failed
     * too. The tokens work for this session only; the user has to connect the account again after
     * a restart.
     */
    NOT_PERSISTED,
}

/** Shared [works.merc.keryx.app.core.Log] tag for every [TokenStorage] implementation. */
internal const val TOKEN_STORAGE_LOG_TAG = "TokenStorage"

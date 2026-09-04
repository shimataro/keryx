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

    /**
     * Removes the tokens from every store this instance manages — its own, plus the plaintext
     * fallback it wraps, if any. Implementations never throw.
     *
     * @return whether anything readable may still be left behind. A caller cannot infer this from
     * [load] returning null afterwards: the plaintext fallback reports a file whose JSON no longer
     * decodes as "nothing stored", yet the token text inside it stays perfectly readable on disk.
     * Only the store that attempted the removal can tell those two apart, which is why the answer
     * is reported from here rather than re-derived by the caller.
     *
     * [works.merc.keryx.app.domain.CloudSession.disconnect] deliberately ignores it: a disconnect
     * that could not remove the file has no recovery path to offer the user yet (raising a warning
     * there would need its own localized message, which is a separate change). The one caller that
     * does act on it is Android's `KeystoreTokenStorage`, whose successful encrypted save downgrades
     * its own result to [TokenSaveOutcome.PLAINTEXT_FILE] when a stale plaintext copy survived.
     */
    fun clear(): TokenClearOutcome
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

    /**
     * Readable in the 0600 plaintext fallback file: either the secure store could not be reached,
     * or a secure write succeeded but a stale fallback copy could not be removed afterwards.
     */
    PLAINTEXT_FILE,

    /**
     * Not persisted anywhere — the secure store could not be reached and the fallback write failed
     * too. The tokens work for this session only; the user has to connect the account again after
     * a restart.
     */
    NOT_PERSISTED,
}

/**
 * Whether a [TokenStorage.clear] actually left the store empty. An enum rather than a `Boolean` for
 * the same reason [TokenSaveOutcome] is one: the call site then reads as the fact it asserts instead
 * of as an unlabelled true/false.
 */
enum class TokenClearOutcome {
    /** Nothing readable is left in any store this instance manages. */
    CLEARED,

    /**
     * A removal did not take effect, so the tokens may still be readable. Typically a `File.delete()`
     * that returned false, leaving the plaintext fallback file — and the long-lived refresh token
     * inside it — in place.
     */
    DATA_MAY_REMAIN,
}

/** Shared [works.merc.keryx.app.core.Log] tag for every [TokenStorage] implementation. */
internal const val TOKEN_STORAGE_LOG_TAG = "TokenStorage"

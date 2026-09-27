package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Log

/**
 * Shared save/load/clear contract for the secure-store backends that keep one secret per account
 * and degrade to [fallback] on any failure — currently desktop's `KeyringTokenStorage`
 * (java-keyring) and `LibSecretTokenStorage` (libsecret), and Android's `KeystoreTokenStorage`
 * (Android Keystore). A concrete subclass supplies only the three backend primitives
 * ([storeSecret]/[loadSecret]/[clearSecret]); the outcome composition itself — when a backend
 * write earns [TokenSaveOutcome.SECURE], when a stale fallback copy must be cleared and what a
 * failure to clear it downgrades to, when a [clear] only counts as [TokenClearOutcome.CLEARED] —
 * is the exact contract `CloudSession`'s notification-center warnings depend on (see
 * `error-design.md`), so it lives here once instead of being re-derived, and risking drifting,
 * per backend. In `commonMain` (rather than desktop-only) so this same policy is exercisable from
 * `commonTest` — Android has no JVM-testable unit-test source set (see `docs/testing.md`), so this
 * is the only way its own outcome logic gets covered by an automated test at all.
 */
abstract class SecretStoreTokenStorage(
    private val fallback: TokenStorage,
    private val json: Json,
) : TokenStorage {

    /** Writes [payload] to the backend. Never throws — a failure is reported as `false`. */
    protected abstract fun storeSecret(payload: String): Boolean

    /** Reads the backend's stored payload, or null if absent or unreachable. Never throws. */
    protected abstract fun loadSecret(): String?

    /**
     * Clears the backend's stored secret. Never throws; returns `true` only when the backend
     * confirms nothing readable is left — see each subclass's own KDoc for what "confirms" means
     * for its backend (e.g. [KeyringTokenStorage]'s conservative treatment of an ambiguous
     * missing-entry exception).
     */
    protected abstract fun clearSecret(): Boolean

    final override fun save(tokens: OAuthTokens): TokenSaveOutcome {
        val payload = json.encodeToString(tokens)
        if (!storeSecret(payload)) {
            // Report the fallback's own outcome rather than a flat "not secure": its write can
            // fail too, and that leaves the tokens nowhere at all instead of in a plaintext file.
            return fallback.save(tokens)
        }
        // A previous run may have written the plaintext fallback before the backend became
        // available again; clear it so a stale plaintext copy doesn't linger once secure storage
        // is working, and report a copy that survived — it still hands out a readable (stale, but
        // possibly still valid) refresh token, which is exactly what the caller's plaintext
        // warning exists for. The check has to be fallback.clear()'s own answer, not a follow-up
        // fallback.load(): FileTokenStorage.load() reports a file whose JSON no longer decodes as
        // "nothing stored", so a failed delete of a corrupt-but-readable file would have looked
        // like a successful cleanup and claimed SECURE with the tokens still on disk.
        return if (fallback.clear() == TokenClearOutcome.CLEARED) {
            TokenSaveOutcome.SECURE
        } else {
            TokenSaveOutcome.PLAINTEXT_FILE
        }
    }

    final override fun load(): OAuthTokens? {
        val decoded = loadSecret()?.let {
            runCatching { json.decodeFromString<OAuthTokens>(it) }
                // Logs only the exception's type, never the exception itself: kotlinx.serialization's
                // JsonDecodingException embeds the offending input (i.e. the token payload) in its
                // own message, which Log.warn(tag, message, throwable) would otherwise write
                // straight into the log file — defeating the whole point of a secure store.
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Stored token payload could not be decoded (${e::class.simpleName})") }
                .getOrNull()
        }
        return decoded ?: fallback.load()
    }

    final override fun clear(): TokenClearOutcome {
        val backendCleared = clearSecret()
        val fallbackCleared = fallback.clear() == TokenClearOutcome.CLEARED
        return if (backendCleared && fallbackCleared) TokenClearOutcome.CLEARED else TokenClearOutcome.DATA_MAY_REMAIN
    }
}

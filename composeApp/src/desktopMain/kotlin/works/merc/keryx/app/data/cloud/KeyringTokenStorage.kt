package works.merc.keryx.app.data.cloud

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log

/**
 * Stores a cloud provider's tokens in the OS secure store via java-keyring (macOS
 * Keychain, Windows Credential Manager, Linux Secret Service). The [account]
 * (per-provider, derived from [CloudStorageType.id]) distinguishes providers under
 * the shared [KEYCHAIN_SERVICE]. Falls back to [FileTokenStorage] if no backend is
 * available or an operation fails.
 */
class KeyringTokenStorage internal constructor(
    private val fallback: TokenStorage,
    private val account: String,
    private val json: Json,
    /**
     * The OS secret store, or null when none is available. Injectable so tests can exercise the
     * "no backend → plaintext fallback" path without touching (or depending on) a real keyring.
     */
    private val keyring: Keyring?,
) : TokenStorage {

    constructor(
        fallback: TokenStorage,
        account: String = CloudStorageType.DROPBOX.id,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(fallback, account, json, createKeyring())

    private val domain = KEYCHAIN_SERVICE

    override fun save(tokens: OAuthTokens): TokenSaveOutcome {
        val payload = json.encodeToString(tokens)
        val stored = keyring?.let {
            runCatching { it.setPassword(domain, account, payload) }
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring save failed; falling back to file storage", e) }
                .isSuccess
        } ?: false
        // Report the fallback's own outcome rather than a flat "not secure": its write can fail
        // too, and that leaves the tokens nowhere at all instead of in a plaintext file.
        return if (stored) TokenSaveOutcome.SECURE else fallback.save(tokens)
    }

    override fun load(): OAuthTokens? {
        val raw = keyring?.let {
            runCatching { it.getPassword(domain, account) }
                .onFailure { e ->
                    if (!isExpectedKeyringLoadFailure(e)) {
                        Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring load failed; falling back to file storage", e)
                    }
                }
                .getOrNull()
        }
        val decoded = raw?.let {
            runCatching { json.decodeFromString<OAuthTokens>(it) }
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Stored token payload could not be decoded", e) }
                .getOrNull()
        }
        return decoded ?: fallback.load()
    }

    override fun clear() {
        keyring?.let {
            runCatching { it.deletePassword(domain, account) }
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring clear failed", e) }
        }
        fallback.clear()
    }
}

/** Opens the platform's secret store, or returns null (and logs) when none is available. */
private fun createKeyring(): Keyring? = runCatching { Keyring.create() }
    .onFailure { Log.warn(TOKEN_STORAGE_LOG_TAG, "No OS secret store available; falling back to file storage", it) }
    .getOrNull()

/**
 * java-keyring reports a missing entry by throwing [PasswordAccessException]
 * (macOS: "No stored credentials match…", Windows: "Password not Found", Linux:
 * its own message) — the same type it uses for genuine read failures. A failed
 * [KeyringTokenStorage.load] is benign either way: it falls back to file storage
 * and yields null when nothing is stored, which is the normal "not connected"
 * state. So we do not log a warning for this type (a full stack trace on every
 * startup was pure noise); only unexpected throwables are surfaced.
 */
internal fun isExpectedKeyringLoadFailure(t: Throwable): Boolean = t is PasswordAccessException

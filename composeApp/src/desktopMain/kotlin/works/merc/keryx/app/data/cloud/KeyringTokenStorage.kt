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
class KeyringTokenStorage(
    private val fallback: TokenStorage,
    private val account: String = CloudStorageType.DROPBOX.id,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {

    private val domain = KEYCHAIN_SERVICE

    private val keyring: Keyring? = runCatching { Keyring.create() }
        .onFailure { Log.warn(TAG, "No OS secret store available; falling back to file storage", it) }
        .getOrNull()

    override fun save(tokens: OAuthTokens) {
        val payload = json.encodeToString(tokens)
        val stored = keyring?.let {
            runCatching { it.setPassword(domain, account, payload) }
                .onFailure { e -> Log.warn(TAG, "Keyring save failed; falling back to file storage", e) }
                .isSuccess
        } ?: false
        if (!stored) fallback.save(tokens)
    }

    override fun load(): OAuthTokens? {
        val raw = keyring?.let {
            runCatching { it.getPassword(domain, account) }
                .onFailure { e ->
                    if (!isExpectedKeyringLoadFailure(e)) {
                        Log.warn(TAG, "Keyring load failed; falling back to file storage", e)
                    }
                }
                .getOrNull()
        }
        val decoded = raw?.let {
            runCatching { json.decodeFromString<OAuthTokens>(it) }
                .onFailure { e -> Log.warn(TAG, "Stored token payload could not be decoded", e) }
                .getOrNull()
        }
        return decoded ?: fallback.load()
    }

    override fun clear() {
        keyring?.let {
            runCatching { it.deletePassword(domain, account) }
                .onFailure { e -> Log.warn(TAG, "Keyring clear failed", e) }
        }
        fallback.clear()
    }

    private companion object {
        const val TAG = "TokenStorage"
    }
}

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

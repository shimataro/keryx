package works.merc.keryx.app.data.cloud

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log

/**
 * Seam over the OS secret store, mirroring the three [Keyring] operations this class uses.
 * java-keyring's own [Keyring] is a concrete class whose only constructor is private, so a test
 * can neither subclass nor instantiate it — without this indirection the only reachable path
 * would be `keyring = null`, leaving a successful write and a failed delete untestable. Same
 * role (and same reason) as [CommandRunner] in `SecurityCliTokenStorage`.
 */
internal interface KeyringAccess {
    fun getPassword(service: String, account: String): String
    fun setPassword(service: String, account: String, password: String)
    fun deletePassword(service: String, account: String)
}

/** [KeyringAccess] backed by the real java-keyring backend. */
private class RealKeyringAccess(private val keyring: Keyring) : KeyringAccess {
    override fun getPassword(service: String, account: String): String =
        keyring.getPassword(service, account)

    override fun setPassword(service: String, account: String, password: String) =
        keyring.setPassword(service, account, password)

    override fun deletePassword(service: String, account: String) =
        keyring.deletePassword(service, account)
}

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
     * "no backend → plaintext fallback" path — and the working-backend paths — without touching
     * (or depending on) a real keyring.
     */
    private val keyring: KeyringAccess?,
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
                    if (!isExpectedKeyringMissingEntry(e)) {
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

    override fun clear(): TokenClearOutcome {
        // No backend at all means nothing was ever stored there, so only the fallback can still be
        // holding anything. A "not found" throw says the same thing — java-keyring reports a missing
        // entry with the very exception type it uses for genuine failures (see
        // isExpectedKeyringMissingEntry) — and counting that as a failed removal would make every
        // disconnect of a never-connected provider claim the tokens might still be there.
        val keyringCleared = keyring?.let {
            runCatching { it.deletePassword(domain, account) }.fold(
                onSuccess = { true },
                onFailure = { e ->
                    if (isExpectedKeyringMissingEntry(e)) {
                        true
                    } else {
                        Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring clear failed", e)
                        false
                    }
                },
            )
        } ?: true
        val fallbackCleared = fallback.clear() == TokenClearOutcome.CLEARED
        return if (keyringCleared && fallbackCleared) TokenClearOutcome.CLEARED else TokenClearOutcome.DATA_MAY_REMAIN
    }
}

/** Opens the platform's secret store, or returns null (and logs) when none is available. */
private fun createKeyring(): KeyringAccess? = runCatching { RealKeyringAccess(Keyring.create()) }
    .onFailure { Log.warn(TOKEN_STORAGE_LOG_TAG, "No OS secret store available; falling back to file storage", it) }
    .getOrNull()

/**
 * java-keyring reports a missing entry by throwing [PasswordAccessException]
 * (macOS: "No stored credentials match…", Windows: "Password not Found", Linux:
 * its own message) — the same type it uses for genuine failures. A missing entry is
 * benign for both operations that can hit it: [KeyringTokenStorage.load] falls back
 * to file storage and yields null when nothing is stored, which is the normal "not
 * connected" state, and [KeyringTokenStorage.clear] simply has nothing left to
 * remove. So this type is neither logged as a warning (a full stack trace on every
 * startup was pure noise) nor counted as a removal that failed; only unexpected
 * throwables are surfaced.
 */
internal fun isExpectedKeyringMissingEntry(t: Throwable): Boolean = t is PasswordAccessException

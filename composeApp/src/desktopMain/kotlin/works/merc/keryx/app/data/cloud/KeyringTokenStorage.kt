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
        if (!stored) {
            // Report the fallback's own outcome rather than a flat "not secure": its write can
            // fail too, and that leaves the tokens nowhere at all instead of in a plaintext file.
            return fallback.save(tokens)
        }
        // A previous run may have written the plaintext fallback before the keyring became
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
        // holding anything. A deletePassword failure is reported through the very same exception
        // type java-keyring uses for a missing entry (see isExpectedKeyringMissingEntry), with no
        // reliable cross-backend way to tell "already gone" apart from "the delete itself failed"
        // (permission error, locked keychain, …). So any such failure is conservatively treated as
        // data that may remain — even for the common case of disconnecting a never-connected
        // provider — rather than risk a false CLEARED while a secret is still readable in the OS
        // store. isExpectedKeyringMissingEntry is still used to suppress the warning log for that
        // common case.
        val keyringCleared = keyring?.let {
            runCatching { it.deletePassword(domain, account) }.fold(
                onSuccess = { true },
                onFailure = { e ->
                    if (!isExpectedKeyringMissingEntry(e)) {
                        Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring clear failed", e)
                    }
                    false
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
 * its own message) — the same type it uses for genuine failures, with no reliable
 * way to tell the two apart. A missing entry is benign for
 * [KeyringTokenStorage.load], which falls back to file storage and yields null when
 * nothing is stored — the normal "not connected" state — so this type is not logged
 * as a warning there (a full stack trace on every startup was pure noise); only
 * unexpected throwables are surfaced. [KeyringTokenStorage.clear] uses this same
 * carve-out to suppress that log noise, but — because the type can't be trusted to
 * mean "nothing to remove" — still counts a matching deletePassword failure as data
 * that may remain, rather than as a successful removal.
 */
internal fun isExpectedKeyringMissingEntry(t: Throwable): Boolean = t is PasswordAccessException

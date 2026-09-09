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
 * available or an operation fails. Outcome composition (when a write earns `SECURE`,
 * when a stale fallback copy must be cleared, etc.) lives in [SecretStoreTokenStorage].
 */
class KeyringTokenStorage internal constructor(
    fallback: TokenStorage,
    private val account: String,
    json: Json,
    /**
     * The OS secret store, or null when none is available. Injectable so tests can exercise the
     * "no backend → plaintext fallback" path — and the working-backend paths — without touching
     * (or depending on) a real keyring.
     */
    private val keyring: KeyringAccess?,
) : SecretStoreTokenStorage(fallback, json) {

    constructor(
        fallback: TokenStorage,
        account: String = CloudStorageType.DROPBOX.id,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(fallback, account, json, createKeyring())

    private val domain = KEYCHAIN_SERVICE

    override fun storeSecret(payload: String): Boolean = keyring?.let {
        runCatching { it.setPassword(domain, account, payload) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring save failed; falling back to file storage", e) }
            .isSuccess
    } ?: false

    override fun loadSecret(): String? = keyring?.let {
        runCatching { it.getPassword(domain, account) }
            .onFailure { e ->
                if (!isExpectedKeyringMissingEntry(e)) {
                    Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring load failed; falling back to file storage", e)
                }
            }
            .getOrNull()
    }

    // A deletePassword failure is reported through the very same exception type java-keyring uses
    // for a missing entry (see isExpectedKeyringMissingEntry), with no reliable cross-backend way
    // to tell "already gone" apart from "the delete itself failed" (permission error, locked
    // keychain, …). So any such failure is conservatively treated as data that may remain — even
    // for the common case of disconnecting a never-connected provider — rather than risk a false
    // CLEARED while a secret is still readable in the OS store. isExpectedKeyringMissingEntry is
    // still used to suppress the warning log for that common case. A null keyring gets the same
    // conservative treatment: this instance is rebuilt fresh on every app launch, so
    // createKeyring() returning null here only means the backend is unavailable *this* run — an
    // earlier run's backend may well have succeeded and left a secret sitting in the OS store,
    // which a null keyring can neither confirm nor deny.
    override fun clearSecret(): Boolean = keyring?.let {
        runCatching { it.deletePassword(domain, account) }.fold(
            onSuccess = { true },
            onFailure = { e ->
                if (!isExpectedKeyringMissingEntry(e)) {
                    Log.warn(TOKEN_STORAGE_LOG_TAG, "Keyring clear failed", e)
                }
                false
            },
        )
    } ?: false
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
 * [KeyringTokenStorage.loadSecret], which falls back to file storage and yields null when
 * nothing is stored — the normal "not connected" state — so this type is not logged
 * as a warning there (a full stack trace on every startup was pure noise); only
 * unexpected throwables are surfaced. [KeyringTokenStorage.clearSecret] uses this same
 * carve-out to suppress that log noise, but — because the type can't be trusted to
 * mean "nothing to remove" — still counts a matching deletePassword failure as data
 * that may remain, rather than as a successful removal.
 */
internal fun isExpectedKeyringMissingEntry(t: Throwable): Boolean = t is PasswordAccessException

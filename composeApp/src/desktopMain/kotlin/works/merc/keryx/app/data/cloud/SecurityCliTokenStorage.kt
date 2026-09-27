package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.KEYCHAIN_COMMAND_TIMEOUT_MS
import works.merc.keryx.app.core.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of running an external command. */
internal data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Seam over external process execution so tests can fake `security` without a real Keychain. */
internal fun interface CommandRunner {
    fun run(args: List<String>): CommandResult
}

/**
 * Real runner backed by [ProcessBuilder]. `security` is expected to finish quickly; if it's
 * blocked (e.g. on an unanswered Keychain-access dialog), [timeoutMillis] aborts it rather than
 * hanging the caller forever. Output is only read once the process has exited, which is safe
 * because it's tiny (a JSON token). [timeoutMillis] is a constructor parameter (rather than using
 * [KEYCHAIN_COMMAND_TIMEOUT_MS] directly) so tests can exercise the real timeout path quickly.
 */
internal class RealCommandRunner(
    private val timeoutMillis: Long = KEYCHAIN_COMMAND_TIMEOUT_MS,
) : CommandRunner {
    override fun run(args: List<String>): CommandResult {
        val proc = ProcessBuilder(args).start()
        if (!proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            // Never include the full `args` here: the payload (-w <token JSON>) is in it.
            throw IOException("security command timed out (${args.getOrNull(1)})")
        }
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        return CommandResult(proc.exitValue(), out, err)
    }
}

/**
 * macOS token storage that delegates to Apple's signed `/usr/bin/security` CLI.
 *
 * Delegating to `security` (a self-contained Apple-signed native process) avoids
 * java-keyring's failure mode, where the Keychain add happens inside a mismatched
 * adhoc-signed JNI dylib and is rejected under a Developer ID + hardened-runtime JVM.
 *
 * However, Keychain writes are still **best-effort under `./gradlew run`**: that
 * launches the app under a detached Gradle daemon (reparented to launchd), whose
 * security session cannot persist to the user's login Keychain — `security add`
 * returns success but the item never lands in the login Keychain. So [save]
 * verifies every write by reading it back from the login Keychain and, if it
 * cannot be confirmed (or the add errored), falls back to [FileTokenStorage] so
 * the token still persists and the app stays connected across restarts. In a
 * context where the Keychain works (a signed/packaged `.app`), writes are
 * confirmed and the file fallback is not used.
 *
 * All commands target the login Keychain explicitly; this is essential for the
 * read-back check — a find scoped to the login Keychain will not pick up a
 * phantom item left in a session-scoped keychain, so non-persistence is detected.
 *
 * A missing entry is reported by `security` as exit code 44 (errSecItemNotFound)
 * and treated as the normal "not connected" state (no warning).
 *
 * Results are cached in memory: [CloudSession] calls [load] on every access-token
 * fetch, and this is a single-instance desktop app with one writer, so caching
 * avoids spawning `security` (and re-triggering Keychain prompts) repeatedly.
 *
 * **Legacy-service migration.** [service] is the Keychain service name items are read from and
 * written to going forward. When [legacyService] is non-null and [service] has nothing yet stored
 * under [account], [load] copies the item over from [legacyService] and removes it there — see
 * [migrateFromLegacyServiceIfPresent]. This exists because the internal Compose macOS build and the
 * SwiftUI app both keep tokens in the Keychain, and letting them collide under the same service
 * name lets either app's disconnect (which also revokes the provider's refresh token) silently
 * break the other's sync; see "Distribution and coexistence" in `docs/app-architecture.md`.
 */
class SecurityCliTokenStorage internal constructor(
    private val fallback: TokenStorage,
    private val runner: CommandRunner,
    private val json: Json,
    private val account: String = CloudStorageType.DROPBOX.id,
    private val service: String = KEYCHAIN_SERVICE,
    private val legacyService: String? = null,
) : TokenStorage {

    constructor(
        fallback: TokenStorage,
        account: String = CloudStorageType.DROPBOX.id,
        json: Json = Json { ignoreUnknownKeys = true },
        service: String = KEYCHAIN_SERVICE,
        legacyService: String? = null,
    ) : this(fallback, RealCommandRunner(), json, account, service, legacyService)

    private val loginKeychain = System.getProperty("user.home") + "/Library/Keychains/login.keychain-db"

    private var cached: OAuthTokens? = null
    private var loaded: Boolean = false

    @Synchronized
    override fun save(tokens: OAuthTokens): TokenSaveOutcome {
        val payload = json.encodeToString(tokens)
        val storedInKeychain = writeToKeychainVerified(service, payload)
        if (!storedInKeychain) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Keychain write to service '$service' did not verify; using file storage")
        }
        // The cache is updated either way — this session must use the tokens it was just handed
        // even when nothing could be persisted.
        val outcome = if (!storedInKeychain) {
            // Report the fallback's own outcome: its write can fail too, and that leaves the
            // tokens nowhere at all instead of in a plaintext file.
            fallback.save(tokens)
        } else {
            // A previous run may have written the plaintext fallback before the Keychain became
            // available again (see the class doc's `gradlew run` caveat); clear it so a stale
            // plaintext copy doesn't linger once a verified Keychain write is working, and report
            // a copy that survived — it still hands out a readable (stale, but possibly still
            // valid) refresh token, which is exactly what the caller's plaintext warning exists
            // for. The check has to be fallback.clear()'s own answer, not a follow-up
            // fallback.load(): FileTokenStorage.load() reports a file whose JSON no longer decodes
            // as "nothing stored", so a failed delete of a corrupt-but-readable file would have
            // looked like a successful cleanup and claimed SECURE with the tokens still on disk.
            if (fallback.clear() == TokenClearOutcome.CLEARED) {
                TokenSaveOutcome.SECURE
            } else {
                TokenSaveOutcome.PLAINTEXT_FILE
            }
        }
        cached = tokens
        loaded = true
        return outcome
    }

    @Synchronized
    override fun load(): OAuthTokens? {
        if (loaded) return cached
        val raw = readKeychainRaw(service) ?: migrateFromLegacyServiceIfPresent()
        val fromKeychain = raw?.let { decodeTokens(it) }
        cached = fromKeychain ?: fallback.load()
        loaded = true
        return cached
    }

    @Synchronized
    override fun clear(): TokenClearOutcome {
        val currentCleared = deleteKeychainItem(service)
        // Best-effort: also remove any leftover legacy item, so a later load() in a fresh process
        // (where `loaded` has reset to false) can't resurrect it after this account was
        // disconnected. Not itself a reason to report DATA_MAY_REMAIN — the legacy service is the
        // other app's, not this instance's primary store — but attempted anyway since it is cheap
        // and this instance is the one that knows the legacy name.
        legacyService?.let { deleteKeychainItem(it) }
        val fallbackCleared = fallback.clear() == TokenClearOutcome.CLEARED
        cached = null
        loaded = true
        return if (currentCleared && fallbackCleared) TokenClearOutcome.CLEARED else TokenClearOutcome.DATA_MAY_REMAIN
    }

    private fun decodeTokens(raw: String): OAuthTokens? =
        runCatching { json.decodeFromString<OAuthTokens>(raw) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Stored token payload could not be decoded", e) }
            .getOrNull()

    /**
     * One-time migration for an item still sitting under [legacyService]: copies it to [service]
     * (verified the same way [save] verifies a fresh write) and, only once that copy is confirmed,
     * removes the legacy item. A migration that cannot be verified leaves the legacy item in place
     * and this call still returns the value read from it, so nothing is lost even when the copy
     * itself doesn't land — the next [load] (a later process run) simply retries the migration.
     */
    private fun migrateFromLegacyServiceIfPresent(): String? {
        val legacy = legacyService ?: return null
        val raw = readKeychainRaw(legacy) ?: return null
        if (writeToKeychainVerified(service, raw)) {
            deleteKeychainItem(legacy)
        } else {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Could not migrate the Keychain item from service '$legacy' to '$service'; still reading it from '$legacy'")
        }
        return raw
    }

    /**
     * Adds [payload] to [service] (`-U` upserts) and confirms the write by reading it back — the
     * same verification [save] and [migrateFromLegacyServiceIfPresent] both need, since a
     * detached-session `add` can report success without the item ever landing (see the class doc).
     */
    private fun writeToKeychainVerified(service: String, payload: String): Boolean {
        val add = runSecurity("add-generic-password", "-U", "-s", service, "-a", account, "-w", payload, loginKeychain)
        if (add?.exitCode != 0) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "security add-generic-password to service '$service' failed (${describe(add)})")
            return false
        }
        return readKeychainRaw(service) == payload
    }

    /** Reads the raw stored payload from [service] in the login Keychain, or null if absent/unreadable. */
    private fun readKeychainRaw(service: String): String? {
        val result = runSecurity("find-generic-password", "-s", service, "-a", account, "-w", loginKeychain)
        return when {
            result == null -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security find-generic-password could not run for service '$service'")
                null
            }
            result.exitCode == ITEM_NOT_FOUND -> null // normal "no entry" — quiet
            result.exitCode != 0 -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security find-generic-password for service '$service' failed (exit ${result.exitCode})")
                null
            }
            else -> result.stdout.trim()
        }
    }

    /** Deletes the item under [service], treating "not found" as success (there was nothing to remove). */
    private fun deleteKeychainItem(service: String): Boolean {
        val result = runSecurity("delete-generic-password", "-s", service, "-a", account, loginKeychain)
        return when {
            // A null result means `security` could not run, so the entry's fate is unknown.
            result == null -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security delete-generic-password for service '$service' could not run")
                false
            }
            result.exitCode == 0 || result.exitCode == ITEM_NOT_FOUND -> true
            else -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security delete-generic-password for service '$service' failed (${describe(result)})")
                false
            }
        }
    }

    private fun runSecurity(vararg args: String): CommandResult? =
        runCatching { runner.run(listOf(SECURITY) + args) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "security command failed to run", e) }
            .getOrNull()

    private fun describe(result: CommandResult?): String =
        if (result == null) "could not run" else "exit ${result.exitCode}: ${result.stderr.trim()}"

    private companion object {
        const val SECURITY = "/usr/bin/security"
        const val ITEM_NOT_FOUND = 44 // errSecItemNotFound
    }
}

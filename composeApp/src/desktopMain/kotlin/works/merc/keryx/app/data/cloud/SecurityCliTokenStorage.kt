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
 */
class SecurityCliTokenStorage internal constructor(
    private val fallback: TokenStorage,
    private val runner: CommandRunner,
    private val json: Json,
    private val account: String = CloudStorageType.DROPBOX.id,
) : TokenStorage {

    constructor(
        fallback: TokenStorage,
        account: String = CloudStorageType.DROPBOX.id,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(fallback, RealCommandRunner(), json, account)

    private val loginKeychain = System.getProperty("user.home") + "/Library/Keychains/login.keychain-db"

    private var cached: OAuthTokens? = null
    private var loaded: Boolean = false

    @Synchronized
    override fun save(tokens: OAuthTokens): Boolean {
        val payload = json.encodeToString(tokens)
        val storedInKeychain = writeToKeychainVerified(payload)
        if (!storedInKeychain) fallback.save(tokens)
        cached = tokens
        loaded = true
        return storedInKeychain
    }

    @Synchronized
    override fun load(): OAuthTokens? {
        if (loaded) return cached
        val raw = readKeychainRaw()
        val fromKeychain = raw?.let {
            runCatching { json.decodeFromString<OAuthTokens>(it) }
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Stored token payload could not be decoded", e) }
                .getOrNull()
        }
        cached = fromKeychain ?: fallback.load()
        loaded = true
        return cached
    }

    @Synchronized
    override fun clear() {
        val result = runSecurity("delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account, loginKeychain)
        // Exit 44 (not found) is a no-op; a null result means `security` could not run.
        if (result == null) Log.warn(TOKEN_STORAGE_LOG_TAG, "security delete-generic-password could not run")
        fallback.clear()
        cached = null
        loaded = true
    }

    /**
     * Adds to the login Keychain and confirms the write by reading it back.
     * Returns false (and logs) if the add errored or the value cannot be
     * confirmed — the caller then persists to the file fallback.
     */
    private fun writeToKeychainVerified(payload: String): Boolean {
        val add = runSecurity("add-generic-password", "-U", "-s", KEYCHAIN_SERVICE, "-a", account, "-w", payload, loginKeychain)
        if (add?.exitCode != 0) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "security add-generic-password failed (${describe(add)}); using file storage")
            return false
        }
        if (readKeychainRaw() != payload) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Keychain write could not be verified (add reported success but read-back mismatched); using file storage")
            return false
        }
        return true
    }

    /** Reads the raw stored payload from the login Keychain, or null if absent/unreadable. */
    private fun readKeychainRaw(): String? {
        val result = runSecurity("find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account, "-w", loginKeychain)
        return when {
            result == null -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security find-generic-password could not run; falling back to file storage")
                null
            }
            result.exitCode == ITEM_NOT_FOUND -> null // normal "no entry" — quiet
            result.exitCode != 0 -> {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "security find-generic-password failed (exit ${result.exitCode}); falling back to file storage")
                null
            }
            else -> result.stdout.trim()
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

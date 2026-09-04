package works.merc.keryx.app.data.cloud

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityCliTokenStorageTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "security-token-test-${Random.nextInt()}")
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT", expiresAtMillis = 123L)

    private fun file() = FileTokenStorage(dirOverride = dir)

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, ".dropbox_tokens.json"))
    }

    /** In-memory stand-in for `/usr/bin/security`. */
    private class FakeSecurity(
        val addExit: Int = 0,
        /** When false, `add` reports success (exit 0) but does not persist — the detached-session silent failure. */
        val persistOnAdd: Boolean = true,
        val throwOnRun: Boolean = false,
    ) : CommandRunner {
        val store = mutableMapOf<Pair<String, String>, String>()
        var findCalls = 0

        override fun run(args: List<String>): CommandResult {
            if (throwOnRun) throw java.io.IOException("security not available")
            val key = flag(args, "-s").orEmpty() to flag(args, "-a").orEmpty()
            return when (args.getOrElse(1) { "" }) {
                "add-generic-password" -> {
                    if (addExit == 0 && persistOnAdd) store[key] = flag(args, "-w").orEmpty()
                    CommandResult(addExit, "", if (addExit == 0) "" else "add failed")
                }
                "find-generic-password" -> {
                    findCalls++
                    store[key]?.let { CommandResult(0, it + "\n", "") }
                        ?: CommandResult(44, "", "could not be found")
                }
                "delete-generic-password" ->
                    CommandResult(if (store.remove(key) != null) 0 else 44, "", "")
                else -> CommandResult(1, "", "unknown")
            }
        }

        private fun flag(args: List<String>, name: String): String? =
            args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    }

    @Test
    fun saveThenLoadRoundTripsViaKeychainNotFile() {
        val storage = SecurityCliTokenStorage(file(), FakeSecurity(), json)
        assertEquals(TokenSaveOutcome.SECURE, storage.save(tokens), "a verified Keychain write must report a secure store")
        assertEquals(tokens, storage.load())
        assertNull(file().load()) // stored in keychain, not the file fallback
    }

    @Test
    fun loadFallsBackToFileWhenKeychainEmpty() {
        file().save(tokens) // pre-existing file token (e.g. before migration)
        val fake = FakeSecurity()
        val storage = SecurityCliTokenStorage(file(), fake, json)
        assertEquals(tokens, storage.load())
        assertEquals(1, fake.findCalls)
    }

    @Test
    fun loadReturnsNullWhenNothingStored() {
        assertNull(SecurityCliTokenStorage(file(), FakeSecurity(), json).load())
    }

    @Test
    fun saveOverwritesPreviousToken() {
        val fake = FakeSecurity()
        SecurityCliTokenStorage(file(), fake, json).save(tokens)
        val updated = tokens.copy(accessToken = "AT2")
        SecurityCliTokenStorage(file(), fake, json).save(updated)
        // fresh instance (no cache) reads the latest from the shared fake keychain
        assertEquals(updated, SecurityCliTokenStorage(file(), fake, json).load())
    }

    @Test
    fun saveFallsBackToFileWhenKeychainAddFails() {
        val outcome = SecurityCliTokenStorage(file(), FakeSecurity(addExit = 1), json).save(tokens)
        // keychain empty (add failed) -> a fresh backend reads from the file fallback
        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, outcome, "a failed Keychain add must report the plaintext fallback")
        assertEquals(tokens, SecurityCliTokenStorage(file(), FakeSecurity(), json).load())
    }

    /**
     * Both stores unavailable: the Keychain add fails *and* the file fallback cannot be written
     * either, so nothing is persisted. `CloudSession` warns about that differently from a plain
     * plaintext fallback, so the fallback's own outcome has to reach it. The in-memory cache is
     * still updated regardless — this session must keep using the tokens it was just handed.
     */
    @Test
    fun saveReportsNotPersistedWhenTheFileFallbackAlsoFails() {
        // A regular file where the data directory is expected makes the fallback's write fail.
        val blockingFile = FileIO.join(AppDirs.tempDir(), "security-token-block-${Random.nextInt()}")
        FileIO.writeText(blockingFile, "not a directory")
        try {
            val storage = SecurityCliTokenStorage(
                FileTokenStorage(dirOverride = blockingFile),
                FakeSecurity(addExit = 1),
                json,
            )

            assertEquals(TokenSaveOutcome.NOT_PERSISTED, storage.save(tokens))
            assertEquals(tokens, storage.load(), "the tokens must stay usable for this session")
        } finally {
            FileIO.delete(blockingFile)
        }
    }

    @Test
    fun saveFallsBackToFileWhenKeychainWriteNotVerified() {
        // `add` reports success but nothing persists (detached-session silent failure);
        // the read-back verification must catch it and route the token to the file.
        val outcome = SecurityCliTokenStorage(file(), FakeSecurity(persistOnAdd = false), json).save(tokens)
        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, outcome, "an unverifiable Keychain write must report the plaintext fallback")
        assertEquals(tokens, SecurityCliTokenStorage(file(), FakeSecurity(), json).load())
    }

    @Test
    fun loadFallsBackToFileWhenSecurityCannotRun() {
        file().save(tokens)
        val storage = SecurityCliTokenStorage(file(), FakeSecurity(throwOnRun = true), json)
        assertEquals(tokens, storage.load())
    }

    @Test
    fun clearRemovesFromKeychainAndFile() {
        val fake = FakeSecurity()
        val storage = SecurityCliTokenStorage(file(), fake, json)
        storage.save(tokens)
        storage.clear()
        assertNull(storage.load())
        assertNull(SecurityCliTokenStorage(file(), fake, json).load())
    }

    @Test
    fun loadCachesKeychainResultAcrossCalls() {
        val fake = FakeSecurity().apply {
            store[KEYCHAIN_SERVICE to CloudStorageType.DROPBOX.id] = json.encodeToString(tokens)
        }
        val storage = SecurityCliTokenStorage(file(), fake, json)
        assertEquals(tokens, storage.load())
        assertEquals(tokens, storage.load())
        assertEquals(1, fake.findCalls) // second load served from the in-memory cache
    }

    @Test
    fun loadCachesNotFoundResultAcrossCalls() {
        val fake = FakeSecurity()
        val storage = SecurityCliTokenStorage(file(), fake, json)
        assertNull(storage.load())
        assertNull(storage.load())
        assertEquals(1, fake.findCalls) // "not connected" state is cached too
    }

    /**
     * A prior run may have left the tokens readable in the plaintext fallback file (e.g. Keychain
     * writes were unverifiable under a detached `gradlew run` session — see the class doc). Once a
     * verified Keychain write succeeds, that stale copy must be cleared rather than left sitting on
     * disk, so a long-lived refresh token doesn't stay readable in plaintext once storage is secure.
     */
    @Test
    fun saveClearsAStalePlaintextFileOnceTheKeychainWorks() {
        file().save(tokens) // pre-existing stale plaintext copy
        val outcome = SecurityCliTokenStorage(file(), FakeSecurity(), json).save(tokens.copy(accessToken = "AT2"))

        assertEquals(TokenSaveOutcome.SECURE, outcome, "a verified Keychain write must clear the stale plaintext copy")
        assertNull(file().load())
    }

    /**
     * When the stale fallback copy cannot actually be removed, the caller must be told the tokens
     * are still readable in plaintext rather than being falsely reassured with
     * [TokenSaveOutcome.SECURE]. [StubFileFallback] stands in for the real [FileTokenStorage] here
     * because coercing a real file delete to fail deterministically would need filesystem
     * permission tricks that are brittle across platforms.
     */
    @Test
    fun saveReportsThePlaintextFileWhenTheStaleFallbackCopySurvivesCleanup() {
        val fallback = StubFileFallback(TokenClearOutcome.DATA_MAY_REMAIN)
        fallback.save(tokens)
        val outcome = SecurityCliTokenStorage(fallback, FakeSecurity(), json).save(tokens.copy(accessToken = "AT2"))

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, outcome)
    }

    /**
     * Minimal in-memory [TokenStorage] standing in for the plaintext fallback, used only to model
     * a [clear] that does not fully succeed.
     */
    private class StubFileFallback(
        private val clearOutcome: TokenClearOutcome,
    ) : TokenStorage {
        var stored: OAuthTokens? = null

        override fun save(tokens: OAuthTokens): TokenSaveOutcome {
            stored = tokens
            return TokenSaveOutcome.PLAINTEXT_FILE
        }

        override fun load(): OAuthTokens? = stored

        override fun clear(): TokenClearOutcome {
            if (clearOutcome == TokenClearOutcome.CLEARED) stored = null
            return clearOutcome
        }
    }
}

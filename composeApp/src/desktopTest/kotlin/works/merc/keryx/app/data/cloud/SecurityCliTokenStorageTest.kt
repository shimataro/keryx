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
        storage.save(tokens)
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
        SecurityCliTokenStorage(file(), FakeSecurity(addExit = 1), json).save(tokens)
        // keychain empty (add failed) -> a fresh backend reads from the file fallback
        assertEquals(tokens, SecurityCliTokenStorage(file(), FakeSecurity(), json).load())
    }

    @Test
    fun saveFallsBackToFileWhenKeychainWriteNotVerified() {
        // `add` reports success but nothing persists (detached-session silent failure);
        // the read-back verification must catch it and route the token to the file.
        SecurityCliTokenStorage(file(), FakeSecurity(persistOnAdd = false), json).save(tokens)
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
}

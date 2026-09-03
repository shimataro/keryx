package works.merc.keryx.app.data.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import works.merc.keryx.app.testContext
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [KeystoreTokenStorage.save]'s fallback path never leaves the stale encrypted file
 * shadowing the fresh tokens just written to `fallback`. A prior version relied on a plain
 * `File.delete()` to remove that stale file, which is not guaranteed to succeed on every Android
 * storage backend; when it failed, [KeystoreTokenStorage.load] kept returning the old,
 * already-rotated-away tokens instead of the ones actually written to `fallback`.
 *
 * Forces encryption itself to fail deterministically by overwriting the Keystore entry at the
 * same alias with an asymmetric key pair — [KeystoreTokenStorage] then casts the retrieved key
 * `as SecretKey?`, which throws `ClassCastException` against a `PrivateKey`.
 */
class KeystoreTokenStorageDeviceTest {
    private val cleanup = mutableListOf<File>()
    private val keyAliasesToCleanup = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        cleanup.forEach { it.deleteRecursively() }
        if (keyAliasesToCleanup.isNotEmpty()) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyAliasesToCleanup.forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
        }
    }

    private fun tempDir(name: String): File =
        File(testContext().cacheDir, "keystoretokenstorage-devicetest-$name-${UUID.randomUUID()}")
            .apply { mkdirs() }
            .also { cleanup += it }

    @Test
    fun saveFallsBackToFreshTokensEvenWhenTheStaleEncryptedFileSurvives() {
        val account = "devicetest-${UUID.randomUUID()}"
        val encryptedDir = tempDir("encrypted")
        val fallbackDir = tempDir("fallback")
        val fallback = FileTokenStorage(dirOverride = fallbackDir.absolutePath, fileName = ".${account}_tokens.json")
        val storage = KeystoreTokenStorage(fallback = fallback, account = account, dirOverride = encryptedDir.absolutePath)

        val keyAlias = "keryx_token_$account"
        keyAliasesToCleanup += keyAlias

        val oldTokens = OAuthTokens(accessToken = "old-access", refreshToken = "old-refresh")
        assertTrue(storage.save(oldTokens), "the first save must report a Keystore-encrypted store")
        val encryptedFile = File(encryptedDir, ".${account}_tokens.enc")
        assertTrue(encryptedFile.exists() && encryptedFile.length() > 0, "the first save must produce a real encrypted file")

        // Overwrite the Keystore entry at the same alias with an asymmetric key pair: the next
        // save()'s `getKey(alias, null) as SecretKey?` then throws ClassCastException, forcing
        // encryption — and therefore the whole save() — to fail.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        assertTrue(keyStore.getKey(keyAlias, null) !is SecretKey, "the replacement Keystore entry must not be a SecretKey")

        val newTokens = OAuthTokens(accessToken = "new-access", refreshToken = "new-refresh")
        // false = "only the plaintext fallback holds this token" — what CloudSession turns into a
        // notification-center warning.
        assertFalse(storage.save(newTokens), "a failed encryption must report the plaintext fallback")

        // Checked before calling load() below, deliberately: load()'s own truncated-file handling
        // (bytes.size <= GCM_IV_LENGTH_BYTES) deletes a file this short as its next side effect —
        // exactly the "cleans up on its own next read" save() itself documents — so asserting
        // existence *after* load() would be asserting a state load() just changed out from under it.
        assertTrue(encryptedFile.exists(), "the stale encrypted file must remain in place (zeroed out) right after save(), not be deleted")
        assertEquals(0L, encryptedFile.length(), "the stale encrypted file must be invalidated, not left holding decryptable old tokens")
        assertEquals(newTokens, storage.load(), "load() must return the fresh fallback tokens, not the stale encrypted ones")
    }

    /** A [KeystoreTokenStorage] + [FileTokenStorage] fallback pair, with both backing files exposed. */
    private class Fixture(val storage: KeystoreTokenStorage, val encryptedFile: File, val fallbackFile: File)

    /** Sets up a fresh [KeystoreTokenStorage] + [FileTokenStorage] fallback pair for one test. */
    private fun newStorage(account: String = "devicetest-${UUID.randomUUID()}"): Fixture {
        val encryptedDir = tempDir("encrypted")
        val fallbackDir = tempDir("fallback")
        val fallbackFileName = ".${account}_tokens.json"
        val fallback = FileTokenStorage(dirOverride = fallbackDir.absolutePath, fileName = fallbackFileName)
        keyAliasesToCleanup += "keryx_token_$account"
        val storage = KeystoreTokenStorage(fallback = fallback, account = account, dirOverride = encryptedDir.absolutePath)
        return Fixture(
            storage = storage,
            encryptedFile = File(encryptedDir, ".${account}_tokens.enc"),
            fallbackFile = File(fallbackDir, fallbackFileName),
        )
    }

    @Test
    fun saveThenLoadRoundTripsThroughRealKeystoreEncryption() {
        val fixture = newStorage()
        val tokens = OAuthTokens(accessToken = "access-1", refreshToken = "refresh-1", expiresAtMillis = 12345L)

        assertTrue(fixture.storage.save(tokens), "a Keystore-encrypted save must report a secure store")

        // Assert the round trip actually went through Keystore encryption, not KeystoreTokenStorage's
        // own fallback path: save() falls back to FileTokenStorage silently on encryption failure, and
        // load() prefers the fallback whenever the encrypted file is absent — so an equality check on
        // load()'s result alone would pass even if every save had silently gone straight to fallback.
        assertTrue(
            fixture.encryptedFile.exists() && fixture.encryptedFile.length() > 0,
            "save() must have produced a real encrypted file rather than falling back",
        )
        assertTrue(!fixture.fallbackFile.exists(), "a successful encrypted save() must clear() the fallback file")
        assertEquals(tokens, fixture.storage.load())
    }

    @Test
    fun loadReturnsNullWhenNothingWasEverSaved() {
        val fixture = newStorage()

        assertEquals(null, fixture.storage.load())
    }

    @Test
    fun clearDeletesTheEncryptedFileAndSubsequentLoadReturnsNull() {
        val account = "devicetest-${UUID.randomUUID()}"
        val encryptedDir = tempDir("encrypted")
        val fallbackDir = tempDir("fallback")
        val fallback = FileTokenStorage(dirOverride = fallbackDir.absolutePath, fileName = ".${account}_tokens.json")
        keyAliasesToCleanup += "keryx_token_$account"
        val storage = KeystoreTokenStorage(fallback = fallback, account = account, dirOverride = encryptedDir.absolutePath)

        storage.save(OAuthTokens(accessToken = "access-1"))
        val encryptedFile = File(encryptedDir, ".${account}_tokens.enc")
        assertTrue(encryptedFile.exists(), "save() must have produced an encrypted file")

        storage.clear()

        assertTrue(!encryptedFile.exists(), "clear() must delete the encrypted file")
        assertEquals(null, storage.load(), "load() must return null once cleared")
    }

    /**
     * Covers [KeystoreTokenStorage.load]'s truncated-file discard branch (`bytes.size <=
     * GCM_IV_LENGTH_BYTES`) — a file this short cannot even hold a full GCM IV, let alone any
     * ciphertext, so it must be treated as corrupt and cleaned up rather than passed to the cipher.
     */
    @Test
    fun loadDiscardsATruncatedEncryptedFileAndFallsBackToNull() {
        val account = "devicetest-${UUID.randomUUID()}"
        val encryptedDir = tempDir("encrypted")
        val fallbackDir = tempDir("fallback")
        val fallback = FileTokenStorage(dirOverride = fallbackDir.absolutePath, fileName = ".${account}_tokens.json")
        keyAliasesToCleanup += "keryx_token_$account"
        val storage = KeystoreTokenStorage(fallback = fallback, account = account, dirOverride = encryptedDir.absolutePath)

        val encryptedFile = File(encryptedDir, ".${account}_tokens.enc").apply {
            parentFile?.mkdirs()
            // Shorter than the 12-byte GCM IV alone (see KeystoreTokenStorage.GCM_IV_LENGTH_BYTES).
            writeBytes(ByteArray(4))
        }

        assertEquals(null, storage.load(), "a truncated encrypted file must not be handed to the cipher")
        assertTrue(!encryptedFile.exists(), "the truncated file must be discarded")
    }
}

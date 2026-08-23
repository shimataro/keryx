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

    @AfterTest
    fun tearDown() {
        cleanup.forEach { it.deleteRecursively() }
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

        val oldTokens = OAuthTokens(accessToken = "old-access", refreshToken = "old-refresh")
        storage.save(oldTokens)
        val encryptedFile = File(encryptedDir, ".${account}_tokens.enc")
        assertTrue(encryptedFile.exists() && encryptedFile.length() > 0, "the first save must produce a real encrypted file")

        // Overwrite the Keystore entry at the same alias with an asymmetric key pair: the next
        // save()'s `getKey(alias, null) as SecretKey?` then throws ClassCastException, forcing
        // encryption — and therefore the whole save() — to fail.
        val keyAlias = "keryx_token_$account"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        assertTrue(keyStore.getKey(keyAlias, null) !is SecretKey, "the replacement Keystore entry must not be a SecretKey")

        val newTokens = OAuthTokens(accessToken = "new-access", refreshToken = "new-refresh")
        storage.save(newTokens)

        assertEquals(newTokens, storage.load(), "load() must return the fresh fallback tokens, not the stale encrypted ones")
        assertEquals(0L, encryptedFile.length(), "the stale encrypted file must be invalidated, not left holding decryptable old tokens")
    }
}

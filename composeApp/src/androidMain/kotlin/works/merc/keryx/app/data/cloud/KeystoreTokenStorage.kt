package works.merc.keryx.app.data.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.AndroidAppContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
private const val GCM_TAG_LENGTH_BITS = 128
private const val GCM_IV_LENGTH_BYTES = 12

/**
 * Android's [TokenStorage]: an AES-256/GCM key held in the Android Keystore (the key material
 * never leaves secure hardware/TEE where the device supports it) encrypts the token JSON, which
 * is then written to a plain file — the same shape as [FileTokenStorage], just with an encrypted
 * payload instead of plaintext. One key alias and one file per provider (derived from
 * [works.merc.keryx.app.core.CloudStorageType.id]), matching [FileTokenStorage]'s own
 * per-provider naming and the "never share an instance across providers" rule in
 * [TokenStorage]'s KDoc.
 *
 * `setUserAuthenticationRequired(false)`: background sync (the periodic `WorkManager` refresh)
 * must be able to decrypt tokens with the device locked, so the key cannot require a recent
 * biometric/PIN unlock the way a per-transaction secret normally would.
 *
 * A decryption failure (key invalidated by a factory reset of the Keystore, a device/OS restore
 * that cannot carry hardware-backed keys, or any other Keystore inconsistency) is **not**
 * surfaced as an exception: [load] treats it identically to "no tokens saved" and deletes the
 * unreadable file, so the app falls back to prompting the user to reconnect rather than crashing
 * or looping on a permanently-undecryptable file.
 */
class KeystoreTokenStorage(
    private val fallback: TokenStorage,
    account: String,
    dirOverride: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {

    private val keyAlias = "keryx_token_$account"
    private val file = File(dirOverride ?: AndroidAppContext.application.filesDir.absolutePath, ".${account}_tokens.enc")

    override fun save(tokens: OAuthTokens) {
        val result = runCatching {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val plaintext = json.encodeToString(tokens).encodeToByteArray()
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            check(iv.size == GCM_IV_LENGTH_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
            file.parentFile?.mkdirs()
            file.writeBytes(iv + ciphertext)
        }
        if (result.isFailure) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Keystore token save failed, falling back to file storage", result.exceptionOrNull())
            fallback.save(tokens)
        } else {
            // A previous run may have written the plaintext fallback before Keystore became
            // available again; clear it so a stale plaintext copy doesn't linger once encrypted
            // storage is working.
            fallback.clear()
        }
    }

    override fun load(): OAuthTokens? {
        val bytes = file.takeIf { it.exists() }?.let {
            runCatching { it.readBytes() }
                .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Encrypted token file could not be read", e) }
                .getOrNull()
        }
        if (bytes == null) return fallback.load()
        if (bytes.size <= GCM_IV_LENGTH_BYTES) {
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Encrypted token file is truncated, discarding")
            file.delete()
            return fallback.load()
        }
        val decrypted = runCatching {
            val key = getExistingKey() ?: return@runCatching null
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            json.decodeFromString<OAuthTokens>(cipher.doFinal(ciphertext).decodeToString())
        }.onFailure { e ->
            // Key invalidated (Keystore reset, restore to a different device, OS upgrade that
            // dropped hardware-backed keys) — treat exactly like "nothing saved" rather than
            // crashing; the unreadable file is cleaned up so it doesn't linger forever.
            Log.warn(TOKEN_STORAGE_LOG_TAG, "Token decryption failed, treating as unauthenticated", e)
            file.delete()
        }.getOrNull()
        return decrypted ?: fallback.load()
    }

    override fun clear() {
        runCatching {
            if (file.exists() && !file.delete()) {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "Encrypted token file delete returned false")
            }
        }.onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Encrypted token file delete failed", e) }
        runCatching { keyStore().deleteEntry(keyAlias) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Keystore key delete failed", e) }
        fallback.clear()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getExistingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as SecretKey?

    private fun getOrCreateKey(): SecretKey = getExistingKey() ?: run {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE).apply {
            init(spec)
        }.generateKey()
    }
}

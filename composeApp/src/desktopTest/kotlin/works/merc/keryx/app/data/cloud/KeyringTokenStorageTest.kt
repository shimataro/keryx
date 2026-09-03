package works.merc.keryx.app.data.cloud

import com.github.javakeyring.PasswordAccessException
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyringTokenStorageTest {
    /**
     * With no OS secret store available, [KeyringTokenStorage.save] must route the token to the
     * fallback *and* report that it did — that false is what makes `CloudSession` raise its bell
     * warning. The keyring is injected as null rather than left to `Keyring.create()`: on a
     * developer machine that would open (and write to) the real login keyring.
     */
    @Test
    fun saveReportsThePlaintextFallbackWhenNoSecretStoreIsAvailable() {
        val fallback = RecordingTokenStorage()
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring = null)
        val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT")

        assertFalse(storage.save(tokens))
        assertEquals(tokens, fallback.stored)
    }

    /** Minimal in-memory [TokenStorage] standing in for the plaintext fallback. */
    private class RecordingTokenStorage : TokenStorage {
        var stored: OAuthTokens? = null

        override fun save(tokens: OAuthTokens): Boolean {
            stored = tokens
            return false
        }

        override fun load(): OAuthTokens? = stored

        override fun clear() {
            stored = null
        }
    }

    @Test
    fun notFoundIsTreatedAsExpectedAndNotWarned() {
        // macOS wording
        assertTrue(
            isExpectedKeyringLoadFailure(
                PasswordAccessException("No stored credentials match works.merc.keryx account: dropbox"),
            ),
        )
        // Windows wording — same type, still expected (message matching would miss this)
        assertTrue(isExpectedKeyringLoadFailure(PasswordAccessException("Password not Found")))
    }

    @Test
    fun unexpectedThrowableIsNotExpected() {
        assertFalse(isExpectedKeyringLoadFailure(RuntimeException("driver blew up")))
        assertFalse(isExpectedKeyringLoadFailure(IllegalStateException()))
    }
}

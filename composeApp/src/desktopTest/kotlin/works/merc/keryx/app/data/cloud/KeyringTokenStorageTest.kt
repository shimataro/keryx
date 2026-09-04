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
     * fallback *and* report that it did — that outcome is what makes `CloudSession` raise its bell
     * warning. The keyring is injected as null rather than left to `Keyring.create()`: on a
     * developer machine that would open (and write to) the real login keyring.
     */
    @Test
    fun saveReportsThePlaintextFallbackWhenNoSecretStoreIsAvailable() {
        val fallback = RecordingTokenStorage()
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring = null)
        val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT")

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(tokens))
        assertEquals(tokens, fallback.stored)
    }

    /**
     * The fallback's own write can fail too (an unwritable data directory, a pre-existing token
     * file owned by another user). `CloudSession` warns about that differently from "stored in
     * plaintext", so the fallback's outcome has to be propagated rather than flattened into a
     * plain "not secure".
     */
    @Test
    fun saveReportsNotPersistedWhenTheFallbackWriteAlsoFails() {
        val fallback = RecordingTokenStorage(TokenSaveOutcome.NOT_PERSISTED)
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring = null)

        assertEquals(TokenSaveOutcome.NOT_PERSISTED, storage.save(OAuthTokens(accessToken = "AT")))
    }

    /**
     * Minimal in-memory [TokenStorage] standing in for the plaintext fallback. [outcome] is what
     * its own [save] reports, so a test can model the fallback write itself failing.
     */
    private class RecordingTokenStorage(
        private val outcome: TokenSaveOutcome = TokenSaveOutcome.PLAINTEXT_FILE,
    ) : TokenStorage {
        var stored: OAuthTokens? = null

        override fun save(tokens: OAuthTokens): TokenSaveOutcome {
            stored = tokens
            return outcome
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

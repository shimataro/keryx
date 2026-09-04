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
     * its own [save] reports, so a test can model the fallback write itself failing. [clearOutcome]
     * is what its own [clear] reports; when it is [TokenClearOutcome.DATA_MAY_REMAIN], [stored] is
     * left in place to model a delete that did not actually remove the file.
     */
    private class RecordingTokenStorage(
        private val outcome: TokenSaveOutcome = TokenSaveOutcome.PLAINTEXT_FILE,
        private val clearOutcome: TokenClearOutcome = TokenClearOutcome.CLEARED,
    ) : TokenStorage {
        var stored: OAuthTokens? = null

        override fun save(tokens: OAuthTokens): TokenSaveOutcome {
            stored = tokens
            return outcome
        }

        override fun load(): OAuthTokens? = stored

        override fun clear(): TokenClearOutcome {
            if (clearOutcome == TokenClearOutcome.CLEARED) stored = null
            return clearOutcome
        }
    }

    /**
     * [KeyringAccess] fake for testing [KeyringTokenStorage.clear]'s outcome composition —
     * unreachable via the real `Keyring`, which cannot be instantiated or subclassed in a test
     * (see [KeyringAccess]'s own KDoc). [deletePasswordThrows] models a keyring delete outcome:
     * null succeeds, an exception is thrown as-is (letting a test pass either a genuine failure or
     * the [com.github.javakeyring.PasswordAccessException] java-keyring uses for "no such entry").
     */
    private class FakeKeyring(
        private val deletePasswordThrows: Throwable? = null,
    ) : KeyringAccess {
        override fun getPassword(service: String, account: String): String =
            throw PasswordAccessException("not used by these tests")

        override fun setPassword(service: String, account: String, password: String) = Unit

        override fun deletePassword(service: String, account: String) {
            deletePasswordThrows?.let { throw it }
        }
    }

    /**
     * Mirrors the "not found" carve-out already covered for [load] by
     * [notFoundIsTreatedAsExpectedAndNotWarned]: a keyring with nothing stored must not make
     * [KeyringTokenStorage.clear] claim the tokens might still be there.
     */
    @Test
    fun clearReportsClearedWhenKeyringHasNoEntryAndFallbackIsCleared() {
        val fallback = RecordingTokenStorage()
        val keyring = FakeKeyring(deletePasswordThrows = PasswordAccessException("Password not Found"))
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring)

        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
    }

    /**
     * A keyring deletion that genuinely fails must not be masked by a fallback that did clear —
     * the caller needs to know the tokens may still be readable from *some* store.
     */
    @Test
    fun clearReportsDataMayRemainWhenKeyringDeleteFailsUnexpectedly() {
        val fallback = RecordingTokenStorage()
        val keyring = FakeKeyring(deletePasswordThrows = RuntimeException("keyring backend blew up"))
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    /**
     * The keyring entry can be removed while the plaintext fallback file survives (e.g. a
     * `File.delete()` that returned false) — the composed outcome must still surface that.
     */
    @Test
    fun clearReportsDataMayRemainWhenTheFallbackFileSurvives() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.DATA_MAY_REMAIN)
        val keyring = FakeKeyring()
        val storage = KeyringTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, keyring)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    @Test
    fun notFoundIsTreatedAsExpectedAndNotWarned() {
        // macOS wording
        assertTrue(
            isExpectedKeyringMissingEntry(
                PasswordAccessException("No stored credentials match works.merc.keryx account: dropbox"),
            ),
        )
        // Windows wording — same type, still expected (message matching would miss this)
        assertTrue(isExpectedKeyringMissingEntry(PasswordAccessException("Password not Found")))
    }

    @Test
    fun unexpectedThrowableIsNotExpected() {
        assertFalse(isExpectedKeyringMissingEntry(RuntimeException("driver blew up")))
        assertFalse(isExpectedKeyringMissingEntry(IllegalStateException()))
    }
}

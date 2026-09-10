package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import kotlin.test.Test
import kotlin.test.assertEquals

class LibSecretTokenStorageTest {
    /**
     * With libsecret unavailable, [LibSecretTokenStorage.save] must route the token to the
     * fallback *and* report that it did — that outcome is what makes `CloudSession` raise its
     * bell warning. libsecret is injected as null rather than left to the real JNA binding: on a
     * developer machine that isn't a snap, `libsecret-1.so.0` may not even be installed.
     */
    @Test
    fun saveReportsThePlaintextFallbackWhenLibSecretIsUnavailable() {
        val fallback = RecordingTokenStorage()
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret = null)
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
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret = null)

        assertEquals(TokenSaveOutcome.NOT_PERSISTED, storage.save(OAuthTokens(accessToken = "AT")))
    }

    /**
     * A prior run without libsecret may have left the tokens readable in the plaintext fallback
     * file. Once libsecret becomes available again and a save succeeds there, that stale copy
     * must be cleared rather than left sitting on disk — otherwise a long-lived refresh token
     * stays readable in plaintext even though storage has since become secure.
     */
    @Test
    fun saveClearsAStalePlaintextFallbackCopyOnceLibSecretWorks() {
        val fallback = RecordingTokenStorage()
        fallback.save(OAuthTokens(accessToken = "STALE_AT", refreshToken = "STALE_RT"))
        val libSecret = FakeLibSecret()
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(TokenSaveOutcome.SECURE, storage.save(OAuthTokens(accessToken = "AT", refreshToken = "RT")))
        assertEquals("""{"accessToken":"AT","refreshToken":"RT"}""", libSecret.storedPassword)
        assertEquals(null, fallback.stored)
    }

    /**
     * When the stale fallback copy cannot actually be removed (e.g. `File.delete()` returning
     * false), the caller must be told the tokens are still readable in plaintext rather than
     * being falsely reassured with [TokenSaveOutcome.SECURE].
     */
    @Test
    fun saveReportsThePlaintextFileWhenTheStaleFallbackCopySurvivesCleanup() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.DATA_MAY_REMAIN)
        fallback.save(OAuthTokens(accessToken = "STALE_AT", refreshToken = "STALE_RT"))
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, FakeLibSecret())

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(OAuthTokens(accessToken = "AT", refreshToken = "RT")))
    }

    /**
     * The most common real Snap-side failure: libsecret is loaded (`libSecret != null`), but the
     * portal round-trip itself fails (portal unreachable, collection locked) and
     * `secret_password_store_sync` returns `FALSE`. This must degrade exactly like a missing
     * backend — routed to the fallback, not silently reported as [TokenSaveOutcome.SECURE].
     */
    @Test
    fun saveReportsThePlaintextFallbackWhenLibSecretStoreFails() {
        val fallback = RecordingTokenStorage()
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, FakeLibSecret(storeSucceeds = false))
        val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT")

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(tokens))
        assertEquals(tokens, fallback.stored)
    }

    /** An unexpected exception out of the JNA binding itself must degrade the same way as a clean `false`. */
    @Test
    fun saveReportsThePlaintextFallbackWhenLibSecretStoreThrows() {
        val fallback = RecordingTokenStorage()
        val storage = LibSecretTokenStorage(
            fallback, CloudStorageType.DROPBOX.id, Json,
            FakeLibSecret(storeThrows = RuntimeException("libsecret backend blew up")),
        )
        val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT")

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(tokens))
        assertEquals(tokens, fallback.stored)
    }

    /**
     * [LibSecretTokenStorage.load] must prefer libsecret's own value over the fallback when both
     * are present.
     */
    @Test
    fun loadPrefersLibSecretOverTheFallback() {
        val fallback = RecordingTokenStorage()
        fallback.save(OAuthTokens(accessToken = "FALLBACK_AT"))
        val libSecret = FakeLibSecret()
        libSecret.storedPassword = """{"accessToken":"SECRET_AT","refreshToken":"SECRET_RT"}"""
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(OAuthTokens(accessToken = "SECRET_AT", refreshToken = "SECRET_RT"), storage.load())
    }

    /**
     * A payload libsecret returns that no longer decodes (corrupted, or written by an
     * incompatible future version) must not surface as a crash or as "nothing stored" when a
     * usable fallback copy exists — it falls through to the fallback exactly like a missing
     * libsecret entry would.
     */
    @Test
    fun loadFallsBackWhenTheStoredPayloadCannotBeDecoded() {
        val fallback = RecordingTokenStorage()
        fallback.save(OAuthTokens(accessToken = "FALLBACK_AT"))
        val libSecret = FakeLibSecret()
        libSecret.storedPassword = "not valid json"
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(OAuthTokens(accessToken = "FALLBACK_AT"), storage.load())
    }

    /**
     * A missing libsecret entry (`lookup` returns null cleanly, no error) must fall through to
     * the fallback exactly like a decode failure or an unavailable backend would.
     */
    @Test
    fun loadFallsBackWhenLibSecretHasNoEntry() {
        val fallback = RecordingTokenStorage()
        fallback.save(OAuthTokens(accessToken = "FALLBACK_AT"))
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, FakeLibSecret())

        assertEquals(OAuthTokens(accessToken = "FALLBACK_AT"), storage.load())
    }

    /** An unexpected exception out of `lookup` must not crash `load` — it falls through to the fallback. */
    @Test
    fun loadFallsBackWhenLibSecretLookupThrows() {
        val fallback = RecordingTokenStorage()
        fallback.save(OAuthTokens(accessToken = "FALLBACK_AT"))
        val storage = LibSecretTokenStorage(
            fallback, CloudStorageType.DROPBOX.id, Json,
            FakeLibSecret(lookupThrows = RuntimeException("libsecret backend blew up")),
        )

        assertEquals(OAuthTokens(accessToken = "FALLBACK_AT"), storage.load())
    }

    /**
     * Both stores must be confirmed empty before reporting [TokenClearOutcome.CLEARED] — a
     * libsecret entry that fails to clear must not be masked by a fallback that did clear.
     */
    @Test
    fun clearReportsDataMayRemainWhenLibSecretClearFails() {
        val fallback = RecordingTokenStorage()
        val libSecret = FakeLibSecret(clearSucceeds = false)
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    /**
     * A null libsecret binding is not proof that nothing was ever stored there — this instance is
     * rebuilt on every app launch, so an earlier run's backend may have succeeded and left a
     * secret behind even though this run's `createLibSecretAccess()` failed. A successful
     * fallback clear alone must not be reported as [TokenClearOutcome.CLEARED].
     */
    @Test
    fun clearReportsDataMayRemainWhenNoLibSecretIsAvailableEvenIfFallbackClears() {
        val fallback = RecordingTokenStorage()
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret = null)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    /**
     * The libsecret entry can be removed while the plaintext fallback file survives (e.g. a
     * `File.delete()` that returned false) — the composed outcome must still surface that.
     */
    @Test
    fun clearReportsDataMayRemainWhenTheFallbackFileSurvives() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.DATA_MAY_REMAIN)
        val libSecret = FakeLibSecret()
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    @Test
    fun clearReportsClearedWhenBothStoresAreConfirmedEmpty() {
        val fallback = RecordingTokenStorage()
        val libSecret = FakeLibSecret()
        libSecret.storedPassword = """{"accessToken":"AT"}"""
        val storage = LibSecretTokenStorage(fallback, CloudStorageType.DROPBOX.id, Json, libSecret)

        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
    }

    /**
     * [LibSecretAccess] fake — unreachable via the real binding, which only exists behind JNA
     * `Native.load` (see [LibSecretAccess]'s own KDoc). [clearSucceeds]/[storeSucceeds] model a
     * clean libsecret failure (a `GError` set, operation returns `false`); [lookupThrows]/
     * [storeThrows] model an unexpected exception out of the JNA binding itself — both are real,
     * distinguishable failure shapes `LibSecretTokenStorage` must degrade identically for.
     */
    private class FakeLibSecret(
        private val storeSucceeds: Boolean = true,
        private val clearSucceeds: Boolean = true,
        private val storeThrows: Throwable? = null,
        private val lookupThrows: Throwable? = null,
    ) : LibSecretAccess {
        var storedPassword: String? = null

        override fun lookup(account: String): String? {
            lookupThrows?.let { throw it }
            return storedPassword
        }

        override fun store(account: String, payload: String): Boolean {
            storeThrows?.let { throw it }
            if (storeSucceeds) storedPassword = payload
            return storeSucceeds
        }

        override fun clear(account: String): Boolean {
            if (clearSucceeds) storedPassword = null
            return clearSucceeds
        }
    }
}

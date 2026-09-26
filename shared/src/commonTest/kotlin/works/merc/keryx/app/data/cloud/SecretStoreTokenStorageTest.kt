package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val SOME_TOKENS = OAuthTokens(accessToken = "at", refreshToken = "rt", expiresAtMillis = 1_000L)

/**
 * A fake [SecretStoreTokenStorage] subclass whose three backend primitives are scripted per test,
 * exercising the outcome-composition policy [save]/[load]/[clear] implement without needing a real
 * OS secret store. This is the CI-visible coverage for that policy: `androidDeviceTest` (the
 * closest thing Android's own KeystoreTokenStorage has to a test) is a separate source-set tree
 * that does not pull in commonTest and needs a connected device or emulator to run at all.
 */
private class FakeSecretBackend(
    fallback: TokenStorage,
    private val storeResult: Boolean = true,
    private val loadResult: String? = null,
    private val clearResult: Boolean = true,
) : SecretStoreTokenStorage(fallback, Json { ignoreUnknownKeys = true }) {
    var storedPayload: String? = null

    override fun storeSecret(payload: String): Boolean {
        if (storeResult) storedPayload = payload
        return storeResult
    }

    override fun loadSecret(): String? = loadResult

    override fun clearSecret(): Boolean = clearResult
}

class SecretStoreTokenStorageTest {

    @Test
    fun saveReportsSecureWhenBackendSucceedsAndFallbackHadNothingToClear() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.CLEARED)
        val storage = FakeSecretBackend(fallback, storeResult = true)

        assertEquals(TokenSaveOutcome.SECURE, storage.save(SOME_TOKENS))
    }

    @Test
    fun saveReportsPlaintextFileWhenBackendSucceedsButAStaleFallbackCopySurvives() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.DATA_MAY_REMAIN)
        val storage = FakeSecretBackend(fallback, storeResult = true)

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(SOME_TOKENS))
    }

    @Test
    fun saveFallsThroughToTheFallbacksOwnOutcomeWhenTheBackendWriteFails() {
        val fallback = RecordingTokenStorage(outcome = TokenSaveOutcome.PLAINTEXT_FILE)
        val storage = FakeSecretBackend(fallback, storeResult = false)

        assertEquals(TokenSaveOutcome.PLAINTEXT_FILE, storage.save(SOME_TOKENS))
        assertEquals(SOME_TOKENS, fallback.stored)
    }

    @Test
    fun saveReportsNotPersistedWhenBothTheBackendAndTheFallbackWriteFail() {
        val fallback = RecordingTokenStorage(outcome = TokenSaveOutcome.NOT_PERSISTED)
        val storage = FakeSecretBackend(fallback, storeResult = false)

        assertEquals(TokenSaveOutcome.NOT_PERSISTED, storage.save(SOME_TOKENS))
    }

    @Test
    fun loadDecodesTheBackendsOwnPayloadWhenPresent() {
        val fallback = RecordingTokenStorage()
        val payload = Json.encodeToString(OAuthTokens.serializer(), SOME_TOKENS)
        val storage = FakeSecretBackend(fallback, loadResult = payload)

        assertEquals(SOME_TOKENS, storage.load())
    }

    @Test
    fun loadFallsBackWhenTheBackendHasNothingStored() {
        val fallback = RecordingTokenStorage()
        fallback.save(SOME_TOKENS)
        val storage = FakeSecretBackend(fallback, loadResult = null)

        assertEquals(SOME_TOKENS, storage.load())
    }

    @Test
    fun loadFallsBackWhenTheBackendsPayloadIsNotValidJson() {
        val fallback = RecordingTokenStorage()
        fallback.save(SOME_TOKENS)
        val storage = FakeSecretBackend(fallback, loadResult = "not json")

        assertEquals(SOME_TOKENS, storage.load())
    }

    @Test
    fun loadReturnsNullWhenNeitherTheBackendNorTheFallbackHasAnything() {
        val fallback = RecordingTokenStorage()
        val storage = FakeSecretBackend(fallback, loadResult = null)

        assertNull(storage.load())
    }

    @Test
    fun clearReportsClearedOnlyWhenBothTheBackendAndTheFallbackClear() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.CLEARED)
        val storage = FakeSecretBackend(fallback, clearResult = true)

        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
    }

    @Test
    fun clearReportsDataMayRemainWhenTheBackendClearFails() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.CLEARED)
        val storage = FakeSecretBackend(fallback, clearResult = false)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }

    @Test
    fun clearReportsDataMayRemainWhenTheFallbackClearFails() {
        val fallback = RecordingTokenStorage(clearOutcome = TokenClearOutcome.DATA_MAY_REMAIN)
        val storage = FakeSecretBackend(fallback, clearResult = true)

        assertEquals(TokenClearOutcome.DATA_MAY_REMAIN, storage.clear())
    }
}

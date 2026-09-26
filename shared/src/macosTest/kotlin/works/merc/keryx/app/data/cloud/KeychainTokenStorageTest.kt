package works.merc.keryx.app.data.cloud

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeychainTokenStorageTest {

    // A throwaway service per run, so the test never meets (or removes) a real Keryx item.
    private val service = "works.merc.keryx.test.${Random.nextLong().toULong()}"
    private val storage = KeychainTokenStorage(account = "dropbox", service = service)

    @AfterTest
    fun tearDown() {
        storage.clear()
    }

    @Test
    fun tokensRoundTripThroughTheKeychain() {
        val tokens = OAuthTokens(accessToken = "AT", refreshToken = "RT", expiresAtMillis = 42L)

        assertEquals(TokenSaveOutcome.SECURE, storage.save(tokens))
        assertEquals(tokens, storage.load())
    }

    @Test
    fun savingAgainReplacesTheItem() {
        storage.save(OAuthTokens(accessToken = "old"))
        storage.save(OAuthTokens(accessToken = "new"))

        assertEquals("new", storage.load()?.accessToken)
    }

    @Test
    fun clearRemovesTheItemAndToleratesNone() {
        storage.save(OAuthTokens(accessToken = "AT"))

        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
        assertNull(storage.load())
        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
    }

    @Test
    fun providersAreKeptApart() {
        val other = KeychainTokenStorage(account = "onedrive", service = service)
        storage.save(OAuthTokens(accessToken = "dropbox-token"))
        other.save(OAuthTokens(accessToken = "onedrive-token"))
        try {
            assertEquals("dropbox-token", storage.load()?.accessToken)
            assertEquals("onedrive-token", other.load()?.accessToken)
        } finally {
            other.clear()
        }
    }
}

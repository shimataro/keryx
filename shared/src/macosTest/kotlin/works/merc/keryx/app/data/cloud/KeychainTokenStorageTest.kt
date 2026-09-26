package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.CloudStorageType
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
    fun savingOverAnExistingItemUpdatesItInPlace() {
        val first = OAuthTokens(accessToken = "AT1", refreshToken = "RT1", expiresAtMillis = 1L)
        val second = OAuthTokens(accessToken = "AT2", refreshToken = "RT2", expiresAtMillis = 2L)

        // The first save adds the item; the second takes the update path (the item already exists).
        assertEquals(TokenSaveOutcome.SECURE, storage.save(first))
        assertEquals(TokenSaveOutcome.SECURE, storage.save(second))
        assertEquals(second, storage.load())

        // Still exactly one item: a single clear leaves nothing behind.
        assertEquals(TokenClearOutcome.CLEARED, storage.clear())
        assertNull(storage.load())
    }

    @Test
    fun googleDriveGetsAnAppleOnlyKeychainAccount() {
        assertEquals("google_drive_apple", appleKeychainAccount(CloudStorageType.GOOGLE_DRIVE))
        for (type in CloudStorageType.entries - CloudStorageType.GOOGLE_DRIVE) {
            assertEquals(type.id, appleKeychainAccount(type), "$type shares the desktop app's account")
        }
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

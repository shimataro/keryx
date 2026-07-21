package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTokenStorageTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "token-storage-test-${Random.nextInt()}")
    private val storage = FileTokenStorage(dirOverride = dir)

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, ".dropbox_tokens.json"))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val tokens = OAuthTokens(
            accessToken = "access-123",
            refreshToken = "refresh-456",
            expiresAtMillis = 1_700_000_000_000L,
        )
        storage.save(tokens)
        val loaded = storage.load()
        assertEquals(tokens, loaded)
    }

    @Test
    fun loadReturnsNullWhenNoFileExists() {
        assertNull(storage.load())
    }

    @Test
    fun loadReturnsNullOnCorruptContent() {
        FileIO.writeText(FileIO.join(dir, ".dropbox_tokens.json"), "{ not valid json")
        assertNull(storage.load())
    }

    @Test
    fun saveDoesNotThrowWhenFileCannotBeWritten() {
        // dirOverride points at an existing regular FILE, so the token path's parent is not a
        // directory and writeText fails. save() must swallow the failure rather than propagate it
        // (which would otherwise abort the connect flow after the token is already held in memory).
        val blockingFile = FileIO.join(AppDirs.tempDir(), "token-block-${Random.nextInt()}")
        FileIO.writeText(blockingFile, "not a directory")
        try {
            val storage = FileTokenStorage(dirOverride = blockingFile)
            storage.save(OAuthTokens(accessToken = "access-123")) // must not throw
            assertNull(storage.load())
        } finally {
            FileIO.delete(blockingFile)
        }
    }

    @Test
    fun clearDeletesFileAndSubsequentLoadReturnsNull() {
        storage.save(OAuthTokens(accessToken = "access-123"))
        assertEquals("access-123", storage.load()?.accessToken)

        storage.clear()

        assertNull(storage.load())
    }
}

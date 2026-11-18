package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallbackTokenFileNameTest {

    @Test
    fun fileNameIsDotIdUnderscoreTokensJsonForEveryProvider() {
        val expected = mapOf(
            CloudStorageType.DROPBOX to ".dropbox_tokens.json",
            CloudStorageType.ONEDRIVE to ".onedrive_tokens.json",
            CloudStorageType.GOOGLE_DRIVE to ".google_drive_tokens.json",
            CloudStorageType.NEXTCLOUD to ".nextcloud_tokens.json",
        )
        assertEquals(CloudStorageType.entries.toSet(), expected.keys, "a new provider needs an expectation here")
        for ((type, name) in expected) assertEquals(name, fallbackTokenFileName(type))
    }

    @Test
    fun defaultFileTokenStorageUsesTheDropboxFileName() {
        val dir = FileIO.join(AppDirs.tempDir(), "fallback-name-test-${Random.nextInt()}")
        try {
            val storage = FileTokenStorage(dirOverride = dir)
            storage.save(OAuthTokens("AT", "RT", 1L))
            assertTrue(FileIO.exists(FileIO.join(dir, ".dropbox_tokens.json")))
        } finally {
            FileIO.delete(FileIO.join(dir, ".dropbox_tokens.json"))
        }
    }
}

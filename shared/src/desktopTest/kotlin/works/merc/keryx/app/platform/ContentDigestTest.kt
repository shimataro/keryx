package works.merc.keryx.app.platform

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The change detector behind the sync upload skip. Its only real requirements are that identical
 * content hashes identically and different content does not — a false "identical" would drop a
 * local edit.
 */
class ContentDigestTest {

    private fun fileWith(bytes: ByteArray): String =
        File.createTempFile("keryx-digest-", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }.absolutePath

    @Test
    fun identicalContentHashesIdentically() {
        val bytes = Random(1).nextBytes(300 * 1024)
        assertEquals(ContentDigest.sha256File(fileWith(bytes)), ContentDigest.sha256File(fileWith(bytes)))
    }

    @Test
    fun aSingleChangedByteChangesTheDigest() {
        val bytes = Random(2).nextBytes(300 * 1024)
        val altered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertNotEquals(ContentDigest.sha256File(fileWith(bytes)), ContentDigest.sha256File(fileWith(altered)))
    }

    @Test
    fun contentSpanningManyReadChunksIsHashedWhole() {
        // The digest reads in fixed-size chunks; a change in the final chunk must still register,
        // which it would not if only the first read were hashed.
        val bytes = Random(3).nextBytes(500 * 1024)
        val altered = bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertNotEquals(ContentDigest.sha256File(fileWith(bytes)), ContentDigest.sha256File(fileWith(altered)))
    }

    @Test
    fun knownVectorMatchesSha256() {
        // Pins the algorithm itself, so a future reimplementation cannot silently change what the
        // stored digests mean.
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            ContentDigest.sha256File(fileWith("test".encodeToByteArray())),
        )
    }

    @Test
    fun anEmptyFileStillHashes() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentDigest.sha256File(fileWith(byteArrayOf())),
        )
    }

    @Test
    fun aMissingFileYieldsNoDigest() {
        // Callers treat null as "nothing to compare against" and upload, which is the safe
        // direction — never a match that would skip an upload.
        assertNull(ContentDigest.sha256File("/definitely/not/a/real/path/keryx.db"))
    }
}

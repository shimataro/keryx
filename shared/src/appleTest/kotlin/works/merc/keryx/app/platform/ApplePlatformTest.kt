package works.merc.keryx.app.platform

import kotlinx.io.IOException
import works.merc.keryx.app.tempFilePath
import works.merc.keryx.app.tempFileWith
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Apple file/compression/digest actuals, against the same expectations the JVM tests pin. */
class ApplePlatformTest {

    @Test
    fun gzipRoundTripsAMultiChunkFile() {
        val payload = Random(3).nextBytes(300 * 1024)
        val source = tempFileWith(payload, "keryx-gz-src-")
        val gz = tempFilePath("keryx-gz-", ".gz")
        val out = tempFilePath("keryx-gz-out-")

        Gzip.compressFile(source, gz)
        // The gzip magic bytes: a real .gz file, not raw deflate.
        val header = FileIO.readBytes(gz)!!
        assertEquals(0x1f.toByte(), header[0])
        assertEquals(0x8b.toByte(), header[1])
        Gzip.decompressFile(gz, out, maxBytes = payload.size.toLong())

        assertContentEquals(payload, FileIO.readBytes(out))
    }

    @Test
    fun gzipDecompressionStopsAtTheByteLimit() {
        val source = tempFileWith(ByteArray(64 * 1024), "keryx-gz-src-")
        val gz = tempFilePath("keryx-gz-", ".gz")
        Gzip.compressFile(source, gz)

        assertFailsWith<IOException> { Gzip.decompressFile(gz, tempFilePath("keryx-gz-out-"), maxBytes = 1024) }
    }

    @Test
    fun gzipRejectsATruncatedFile() {
        val source = tempFileWith(Random(4).nextBytes(64 * 1024), "keryx-gz-src-")
        val gz = tempFilePath("keryx-gz-", ".gz")
        Gzip.compressFile(source, gz)
        val truncated = tempFileWith(FileIO.readBytes(gz)!!.copyOf(100), "keryx-gz-trunc-", ".gz")

        assertFailsWith<IOException> { Gzip.decompressFile(truncated, tempFilePath("keryx-gz-out-"), maxBytes = Long.MAX_VALUE) }
    }

    @Test
    fun contentDigestIsTheHexSha256OfTheFile() {
        val path = tempFileWith("abc".encodeToByteArray(), "keryx-digest-")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentDigest.sha256File(path))
        assertNull(ContentDigest.sha256File(tempFilePath("keryx-digest-missing-")))
    }

    @Test
    fun sha1AndSha256MatchKnownVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".encodeToByteArray()).toHex())
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest(ByteArray(0)).toHex())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(ByteArray(0)).toHex())
    }

    @Test
    fun secureRandomBytesAreFreshEachCall() {
        val a = secureRandomBytes(32)
        val b = secureRandomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun deleteRecursivelyRemovesATreeAndToleratesAMissingPath() {
        val root = tempFilePath("keryx-tree-", "")
        FileIO.writeText(FileIO.join(root, "a", "b.txt"), "x")

        assertTrue(FileSystemExtras.deleteRecursively(root))
        assertFalse(FileIO.exists(root))
        assertTrue(FileSystemExtras.deleteRecursively(root))
    }

    @Test
    fun moveRenamesWithinAVolume() {
        val from = tempFileWith("payload".encodeToByteArray(), "keryx-move-")
        val to = tempFilePath("keryx-moved-")

        assertTrue(FileSystemExtras.move(from, to))
        assertFalse(FileIO.exists(from))
        assertEquals("payload", FileIO.readText(to))
    }

    @Test
    fun theTempDirectoryIsWritable() {
        assertTrue(FileSystemExtras.isDirectoryWritable(AppDirs.tempDir()))
        assertTrue(FileSystemExtras.usableSpaceBytes(AppDirs.tempDir()) > 0)
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

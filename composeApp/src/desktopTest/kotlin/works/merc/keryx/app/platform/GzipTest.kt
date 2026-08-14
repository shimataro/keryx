package works.merc.keryx.app.platform

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Gzip is the only thing standing between a downloaded cloud file and disk: a small, highly
 * compressed payload can expand enormously, so `decompressFile` must reject that before it
 * exhausts disk rather than after.
 */
class GzipTest {

    private fun tempFile(suffix: String): File =
        File.createTempFile("keryx-gzip-test-", suffix).apply { deleteOnExit() }

    @Test
    fun decompressionWithinTheLimitRoundTrips() {
        val original = ByteArray(64 * 1024) { it.toByte() }
        val src = tempFile(".bin").apply { writeBytes(original) }
        val gz = tempFile(".gz")
        Gzip.compressFile(src.absolutePath, gz.absolutePath)
        val dest = tempFile(".bin")

        Gzip.decompressFile(gz.absolutePath, dest.absolutePath, maxBytes = original.size.toLong())

        assertContentEquals(original, dest.readBytes())
    }

    @Test
    fun decompressionExceedingTheLimitThrows() {
        // Highly repetitive input compresses to a small fraction of its size, so a tiny gzip file
        // can still exceed a limit set below its true decompressed size.
        val original = ByteArray(1024 * 1024) // 1 MB of zero bytes
        val src = tempFile(".bin").apply { writeBytes(original) }
        val gz = tempFile(".gz")
        Gzip.compressFile(src.absolutePath, gz.absolutePath)
        val dest = tempFile(".bin")

        assertFailsWith<IOException> {
            Gzip.decompressFile(gz.absolutePath, dest.absolutePath, maxBytes = 100 * 1024)
        }
    }
}

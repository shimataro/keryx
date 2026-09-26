package works.merc.keryx.app.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteFileTest {

    private val validHeader = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    @Test
    fun nullIsRejected() {
        assertFalse(looksLikeSqliteFile(null))
    }

    @Test
    fun emptyIsRejected() {
        assertFalse(looksLikeSqliteFile(byteArrayOf()))
    }

    @Test
    fun truncatedHeaderIsRejected() {
        assertFalse(looksLikeSqliteFile(validHeader.copyOf(15)))
    }

    @Test
    fun wrongLeadingBytesAreRejected() {
        val wrong = validHeader.copyOf()
        wrong[0] = 0x00
        assertFalse(looksLikeSqliteFile(wrong))
    }

    @Test
    fun exactHeaderIsAccepted() {
        assertTrue(looksLikeSqliteFile(validHeader))
    }

    @Test
    fun headerFollowedByMoreDataIsAccepted() {
        assertTrue(looksLikeSqliteFile(validHeader + byteArrayOf(1, 2, 3)))
    }

    // --- the path-based overload, which is the form the sync flow uses now that the payload is
    // streamed to disk instead of being held in memory ---

    private fun fileWith(bytes: ByteArray): String =
        File.createTempFile("keryx-sqlitefile-", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }.absolutePath

    @Test
    fun missingFileIsRejected() {
        assertFalse(looksLikeSqliteFile("/definitely/not/a/real/path/keryx.db"))
    }

    @Test
    fun emptyFileIsRejected() {
        assertFalse(looksLikeSqliteFile(fileWith(byteArrayOf())))
    }

    @Test
    fun fileWithTruncatedHeaderIsRejected() {
        assertFalse(looksLikeSqliteFile(fileWith(validHeader.copyOf(15))))
    }

    @Test
    fun fileWithWrongLeadingBytesIsRejected() {
        val wrong = validHeader.copyOf()
        wrong[0] = 0x00
        assertFalse(looksLikeSqliteFile(fileWith(wrong)))
    }

    @Test
    fun fileWithExactHeaderIsAccepted() {
        assertTrue(looksLikeSqliteFile(fileWith(validHeader)))
    }

    @Test
    fun fileLargerThanTheHeaderIsAcceptedWithoutReadingItAll() {
        // A payload far bigger than any buffer the check uses: it must decide from the first 16
        // bytes rather than loading the file, which is the whole point of the path-based form.
        assertTrue(looksLikeSqliteFile(fileWith(validHeader + ByteArray(4 * 1024 * 1024))))
    }
}

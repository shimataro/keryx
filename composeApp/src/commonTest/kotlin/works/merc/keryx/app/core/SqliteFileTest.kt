package works.merc.keryx.app.core

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
}

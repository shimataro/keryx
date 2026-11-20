package works.merc.keryx.app.platform

import works.merc.keryx.app.core.LocalWriteFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** [localWriteFailureKind]'s Apple actual: classification from the SQLite result code. */
class LocalWriteFailureAppleTest {

    @Test
    fun busyAndLockedAreBusy() {
        assertEquals(LocalWriteFailureKind.BUSY, localWriteFailureKindOf(5))
        assertEquals(LocalWriteFailureKind.BUSY, localWriteFailureKindOf(6))
    }

    @Test
    fun fullIsDiskFull() {
        assertEquals(LocalWriteFailureKind.DISK_FULL, localWriteFailureKindOf(13))
    }

    @Test
    fun anExtendedCodeIsClassifiedByItsPrimaryCode() {
        // SQLITE_BUSY_SNAPSHOT (517) and SQLITE_LOCKED_SHAREDCACHE (262).
        assertEquals(LocalWriteFailureKind.BUSY, localWriteFailureKindOf(517))
        assertEquals(LocalWriteFailureKind.BUSY, localWriteFailureKindOf(262))
    }

    @Test
    fun otherCodesAndNoCodeAreOther() {
        // SQLITE_IOERR, SQLITE_READONLY, SQLITE_CORRUPT, SQLITE_CANTOPEN.
        listOf(10, 8, 11, 14).forEach { assertEquals(LocalWriteFailureKind.OTHER, localWriteFailureKindOf(it), "code $it") }
        assertEquals(LocalWriteFailureKind.OTHER, localWriteFailureKindOf(null))
    }

    @Test
    fun theRawConnectionsExceptionIsFoundInTheCauseChain() {
        val busy = SqliteException(5, "database is locked")
        assertEquals(LocalWriteFailureKind.BUSY, busy.localWriteFailureKind())
        assertEquals(LocalWriteFailureKind.DISK_FULL, IllegalStateException("wrapped", SqliteException(13, "full")).localWriteFailureKind())
        assertEquals(LocalWriteFailureKind.OTHER, IllegalStateException("not SQLite").localWriteFailureKind())
    }
}

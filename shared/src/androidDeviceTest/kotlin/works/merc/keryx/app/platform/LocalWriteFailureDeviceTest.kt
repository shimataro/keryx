package works.merc.keryx.app.platform

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import works.merc.keryx.app.core.LocalWriteFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [localWriteFailureKind]'s Android actual, which classifies by the `android.database.sqlite`
 * exception's class — checked against synthetic instances, as `MergeFailureClassificationDeviceTest`
 * checks `failureCategory`.
 */
class LocalWriteFailureDeviceTest {

    @Test
    fun lockedDatabaseAndLockedTableAreBusy() {
        assertEquals(LocalWriteFailureKind.BUSY, SQLiteDatabaseLockedException("busy").localWriteFailureKind())
        assertEquals(LocalWriteFailureKind.BUSY, SQLiteTableLockedException("locked").localWriteFailureKind())
    }

    @Test
    fun fullIsDiskFull() {
        assertEquals(LocalWriteFailureKind.DISK_FULL, SQLiteFullException("full").localWriteFailureKind())
    }

    @Test
    fun otherSqliteFailuresAreOther() {
        listOf(
            SQLiteDiskIOException("io"),
            SQLiteReadOnlyDatabaseException("read-only"),
            SQLiteDatabaseCorruptException("corrupt"),
            SQLiteException("generic"),
        ).forEach { assertEquals(LocalWriteFailureKind.OTHER, it.localWriteFailureKind(), it::class.simpleName) }
    }

    @Test
    fun aWrappedFailureIsClassifiedByItsCauseAndANonSqliteOneIsOther() {
        assertEquals(LocalWriteFailureKind.BUSY, IllegalStateException("wrapped", SQLiteDatabaseLockedException("busy")).localWriteFailureKind())
        assertEquals(LocalWriteFailureKind.OTHER, IllegalStateException("not SQLite").localWriteFailureKind())
    }
}

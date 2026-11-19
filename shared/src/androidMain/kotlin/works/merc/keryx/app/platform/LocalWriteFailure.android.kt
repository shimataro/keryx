package works.merc.keryx.app.platform

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteTableLockedException
import works.merc.keryx.app.core.LocalWriteFailureKind

/**
 * Classifies by the exception's class, as [DatabaseMerger]'s own `failureCategory` does: the
 * requery driver throws `android.database.sqlite` exceptions, which carry no numeric result code
 * but one subclass per code — `SQLiteDatabaseLockedException` for `SQLITE_BUSY`,
 * `SQLiteTableLockedException` for `SQLITE_LOCKED`, `SQLiteFullException` for `SQLITE_FULL`.
 * Walks the cause chain (bounded), in case a wrapper sits in front of it.
 */
actual fun Throwable.localWriteFailureKind(): LocalWriteFailureKind {
    var current: Throwable? = this
    repeat(CAUSE_CHAIN_MAX_DEPTH) {
        when (val c = current ?: return LocalWriteFailureKind.OTHER) {
            is SQLiteDatabaseLockedException, is SQLiteTableLockedException -> return LocalWriteFailureKind.BUSY
            is SQLiteFullException -> return LocalWriteFailureKind.DISK_FULL
            else -> current = c.cause
        }
    }
    return LocalWriteFailureKind.OTHER
}

/** Bounds the cause-chain walk against a theoretical cause cycle. */
private const val CAUSE_CHAIN_MAX_DEPTH = 8

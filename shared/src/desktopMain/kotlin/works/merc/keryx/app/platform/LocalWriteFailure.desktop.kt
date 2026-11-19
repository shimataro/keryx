package works.merc.keryx.app.platform

import works.merc.keryx.app.core.LocalWriteFailureKind

/** Reads the JDBC driver's result code; its primary code is the extended code's low byte. */
actual fun Throwable.localWriteFailureKind(): LocalWriteFailureKind {
    val code = findSqliteCause()?.resultCode?.code ?: return LocalWriteFailureKind.OTHER
    return when (code and 0xFF) {
        SQLITE_BUSY, SQLITE_LOCKED -> LocalWriteFailureKind.BUSY
        SQLITE_FULL -> LocalWriteFailureKind.DISK_FULL
        else -> LocalWriteFailureKind.OTHER
    }
}

// SQLite primary result codes, named locally (see org.sqlite.SQLiteErrorCode for the full list).
private const val SQLITE_BUSY = 5
private const val SQLITE_LOCKED = 6
private const val SQLITE_FULL = 13

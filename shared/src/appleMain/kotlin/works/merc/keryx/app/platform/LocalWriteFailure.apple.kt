package works.merc.keryx.app.platform

import works.merc.keryx.app.core.LocalWriteFailureKind

/** Classifies by the result code [findSqliteResultCode] finds, on its primary code (the low byte). */
actual fun Throwable.localWriteFailureKind(): LocalWriteFailureKind =
    localWriteFailureKindOf(findSqliteResultCode())

/**
 * The [LocalWriteFailureKind] of SQLite result code [resultCode] (extended or primary), or of no
 * code at all (`null`). Split out so a test can check the mapping without a failing database.
 */
internal fun localWriteFailureKindOf(resultCode: Int?): LocalWriteFailureKind = when (resultCode?.and(0xFF)) {
    SQLITE_BUSY, SQLITE_LOCKED -> LocalWriteFailureKind.BUSY
    SQLITE_FULL -> LocalWriteFailureKind.DISK_FULL
    else -> LocalWriteFailureKind.OTHER
}

// SQLite primary result codes, named locally (sqlite3.h).
private const val SQLITE_BUSY = 5
private const val SQLITE_LOCKED = 6
private const val SQLITE_FULL = 13
